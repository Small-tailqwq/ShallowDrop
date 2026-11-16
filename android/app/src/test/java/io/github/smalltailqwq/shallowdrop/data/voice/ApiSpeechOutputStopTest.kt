package io.github.smalltailqwq.shallowdrop.data.voice

import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import io.github.smalltailqwq.shallowdrop.data.api.DeepSeekSpeechApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 打断（stop）之后上一轮朗读的线程还没退出：stop 只取消、不等待，旧线程可能正卡在等播完、
 * 或卡在读网络上，与新一轮同时存在。这里用门闩把它们卡在指定位置，确定地复现这些交错。
 * 不经过网络：请求由拦截器直接应答。
 */
@RunWith(RobolectricTestRunner::class)
class ApiSpeechOutputStopTest {
    private val gates = mutableListOf<CountDownLatch>()

    private fun gate() = CountDownLatch(1).also { gates += it }

    @After
    fun tearDown() = gates.forEach { it.countDown() }

    private fun CountDownLatch.expect(what: String) = assertTrue(what, await(5, TimeUnit.SECONDS))

    /** 按请求次序应答：[answer] 的参数是第几个请求（从 1 起），可以在里面阻塞 */
    private fun api(answer: (Int) -> ResponseBody): DeepSeekSpeechApi {
        val count = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(answer(count.incrementAndGet()))
                        .build()
                },
            )
            .build()
        return DeepSeekSpeechApi(client, "http://tts.test")
    }

    /** 音频分几次读到；[beforeRead] 在每次读之前调用（参数为第几次，从 0 起），可以在里面阻塞 */
    private class StreamBody(private val chunks: List<ByteArray>, private val beforeRead: (Int) -> Unit = {}) : ResponseBody() {
        val closed = CountDownLatch(1)

        private val source = object : Source {
            var step = 0

            override fun read(sink: Buffer, byteCount: Long): Long {
                beforeRead(step)
                val chunk = chunks.getOrNull(step++) ?: return -1
                sink.write(chunk)
                return chunk.size.toLong()
            }

            override fun close() = closed.countDown()

            override fun timeout(): Timeout = Timeout.NONE
        }.buffer()

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = source
    }

    /** 按 [PcmPlayer] 的约定：带旧轮次的写入不播，只记下来了多少 */
    private class Player : PcmPlayer {
        val out = ByteArrayOutputStream()
        val firstWrite = CountDownLatch(1)

        @Volatile
        var flushes = 0

        @Volatile
        var staleBytes = 0

        override val round: Int get() = flushes

        /** 第一次等播完时卡住，直到 [drainRelease] */
        val drainEntered = CountDownLatch(1)
        val drainRelease = CountDownLatch(1)
        val drainReturned = CountDownLatch(1)
        private val drains = AtomicInteger()

        @Synchronized
        override fun write(data: ByteArray, offset: Int, length: Int, round: Int) {
            if (round != flushes) {
                staleBytes += length
                return
            }
            out.write(data, offset, length)
            firstWrite.countDown()
        }

        override fun drain(round: Int) {
            if (drains.incrementAndGet() != 1) return
            drainEntered.countDown()
            drainRelease.await()
            drainReturned.countDown()
        }

        @Synchronized
        override fun flush() {
            flushes++
        }

        override fun release() = Unit

        @Synchronized
        fun played(): ByteArray = out.toByteArray()
    }

    private fun pcm(size: Int, seed: Int) = ByteArray(size) { (it * 31 + seed).toByte() }

    private fun output(api: DeepSeekSpeechApi, player: Player) =
        ApiSpeechOutput(api, { "sk-test" }, { TtsVoice.Stella }, { player }, io = Dispatchers.IO)

    @Test
    fun staleDrainDoesNotEndTheNextRound() = runBlocking {
        val first = pcm(2000, 1)
        val second = pcm(3000, 2)
        val secondRequested = gate()
        val secondAnswer = gate()
        val api = api { index ->
            if (index == 1) {
                StreamBody(listOf(first))
            } else {
                secondRequested.countDown()
                secondAnswer.await()
                StreamBody(listOf(second))
            }
        }
        val player = Player().also { gates += it.drainRelease }
        val output = output(api, player)
        assertTrue(output.prepare("zh-CN"))

        // 上一轮：唯一的一句已写完，正在等它播完
        output.enqueue("第一句。")
        player.drainEntered.expect("上一轮应进入等待播完")

        // 打断，下一轮随即开始：请求已发出、音频还没回来
        output.stop()
        output.enqueue("第二句。")
        secondRequested.expect("下一轮的请求应已发出")

        // 上一轮的等待此时才返回
        player.drainRelease.countDown()
        player.drainReturned.expect("上一轮的等待应返回")
        assertNull("下一轮还没读，不能因上一轮收尾而被当作读完", withTimeoutOrNull(500) { output.awaitIdle() })

        secondAnswer.countDown()
        withTimeout(5_000) { output.awaitIdle() }
        assertArrayEquals(first + second, player.played())
        assertEquals(0, player.staleBytes)
    }

    @Test
    fun audioArrivingAfterStopIsNotPlayed() = runBlocking {
        val early = pcm(2000, 1)
        val late = pcm(2000, 2)
        val waitingForMore = gate()
        val more = gate()
        val body = StreamBody(listOf(early, late)) { step ->
            if (step == 1) {
                waitingForMore.countDown()
                more.await()
            }
        }
        val player = Player()
        val output = output(api { body }, player)
        assertTrue(output.prepare("zh-CN"))

        // 前半句已播，朗读线程正等后半句从网络到来
        output.enqueue("很长的一句。")
        player.firstWrite.expect("前半句应已写入")
        waitingForMore.expect("朗读线程应在等后续音频")

        output.stop()
        assertEquals(1, player.flushes)
        val playedAtStop = player.played().size

        // 后半句此时才到：朗读线程读到它之后才发现已被打断
        more.countDown()
        body.closed.expect("朗读线程应结束并关闭响应")
        assertEquals("停止后到达的音频不能再播出来", playedAtStop, player.played().size)
        assertEquals("它带着上一轮的轮次到达播放器，由播放器丢弃", late.size, player.staleBytes)
    }
}
