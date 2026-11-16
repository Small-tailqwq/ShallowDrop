package io.github.smalltailqwq.shallowdrop.data.voice

import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import io.github.smalltailqwq.shallowdrop.data.api.DeepSeekSpeechApi
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrError
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrEvent
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 本地端点检测、WAV 编码与 DeepSeek 语音接口引擎。 */
@RunWith(RobolectricTestRunner::class)
class SpeechEnginesTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DeepSeekSpeechApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = DeepSeekSpeechApi(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.close()

    // ---------- 合成音频 ----------

    private val rate = 16_000

    private fun noise(ms: Int) = ShortArray(rate * ms / 1000) { ((it * 7919 % 61) - 30).toShort() }

    private fun tone(ms: Int, amplitude: Double = 8000.0) =
        ShortArray(rate * ms / 1000) { (sin(2 * PI * 440 * it / rate) * amplitude).toInt().toShort() }

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    /** 逐帧喂入，返回首次出现每种结果的帧序号 */
    private fun run(detector: EndpointDetector, audio: ShortArray): Map<EndpointDetector.Result, Int> {
        val firsts = LinkedHashMap<EndpointDetector.Result, Int>()
        var index = 0
        while ((index + 1) * detector.frameSize <= audio.size) {
            val frame = audio.copyOfRange(index * detector.frameSize, (index + 1) * detector.frameSize)
            val result = detector.feed(frame)
            firsts.putIfAbsent(result, index)
            if (result == EndpointDetector.Result.Ended || result == EndpointDetector.Result.Timeout) break
            index++
        }
        return firsts
    }

    // ---------- 端点检测 ----------

    @Test
    fun detectsUtteranceAndEndsAfterSilence() {
        val detector = EndpointDetector()
        val firsts = run(detector, concat(noise(1000), tone(1000), noise(1500)))
        // 开口约在第 1000ms（50 帧）后 60ms 内判定
        assertTrue(firsts.getValue(EndpointDetector.Result.Speaking) in 50..55)
        // 说完：语音结束（100 帧）后约 900ms（45 帧）
        assertTrue(firsts.getValue(EndpointDetector.Result.Ended) in 140..150)
        // 保留了开口前的音频，并包含整段语音
        val speech = detector.speech()
        assertTrue(speech.size >= rate * 1000 / 1000 + rate * 300 / 1000)
    }

    @Test
    fun silenceTimesOutAndShortClickIsIgnored() {
        val quiet = EndpointDetector(noSpeechMs = 2_000)
        assertTrue(EndpointDetector.Result.Timeout in run(quiet, noise(3000)))

        val click = EndpointDetector(noSpeechMs = 3_000)
        val firsts = run(click, concat(noise(500), tone(100), noise(4000)))
        // 100ms 的声响不足以成句：不会结束，最终超时
        assertTrue(EndpointDetector.Result.Ended !in firsts)
        assertTrue(EndpointDetector.Result.Timeout in firsts)
    }

    @Test
    fun wavHeaderMatchesAsrFormat() {
        val wav = encodeWav(shortArrayOf(1, -2, 0x1234), 16_000)
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        assertArrayEquals(byteArrayOf(1, 0, 0xfe.toByte(), 0xff.toByte(), 0x34, 0x12), wav.copyOfRange(44, 50))
        // 采样率 16000（0x3E80）
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x3e, 0, 0), wav.copyOfRange(24, 28))
    }

    // ---------- 识别 ----------

    private class FakeSource(private val audio: ShortArray) : PcmSource {
        private var position = 0
        var closed = false

        override fun read(frame: ShortArray): Int {
            // 音频部分快速送出；之后像真实麦克风一样按实时速度送静音，避免撑满持续录音的缓冲
            if (position >= audio.size) Thread.sleep(frame.size * 1000L / 16_000)
            for (i in frame.indices) frame[i] = if (position < audio.size) audio[position++] else 0
            return frame.size
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun speechIsUploadedAsWavAndTranscribed() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"text":" 你好 "}""").build())
        val source = FakeSource(concat(noise(500), tone(800), noise(1000)))
        val input = ApiSpeechInput(api, { "sk-test" }, { source })
        val events = withTimeout(10_000) { input.listen("zh-CN").toList() }

        assertEquals(AsrEvent.Ready, events.first())
        assertTrue(AsrEvent.Recognizing in events)
        assertEquals(AsrEvent.Final("你好"), events.last())
        assertTrue("持续录音：一句结束后麦克风保持打开", !source.closed)
        input.release()
        assertTrue("release 后释放麦克风", source.closed)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/tools/asr", request.url.encodedPath)
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        val body = request.body!!.utf8()
        assertTrue(body.contains("filename=\"recording.wav\""))
        assertTrue(body.contains("RIFF"))
    }

    @Test
    fun speechBetweenListensIsNotLost() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"text":"第一句"}""").build())
        server.enqueue(MockResponse.Builder().body("""{"text":"第二句"}""").build())
        // 两句之间只隔 1 秒：第一句识别期间第二句已经开始，必须由下一次聆听接着读到
        val source = FakeSource(concat(noise(300), tone(800), noise(1000), tone(800, 6000.0), noise(1500)))
        val input = ApiSpeechInput(api, { "sk-test" }, { source })
        val first = withTimeout(10_000) { input.listen("zh-CN").toList() }
        val second = withTimeout(10_000) { input.listen("zh-CN").toList() }
        input.release()
        assertEquals(AsrEvent.Final("第一句"), first.last())
        assertEquals(AsrEvent.Final("第二句"), second.last())
        // 第二次上传的音频包含完整的第二句（约 0.8 秒语音 + 前后预留），而不是从中途开始
        server.takeRequest(1, TimeUnit.SECONDS)
        val secondUpload = server.takeRequest(1, TimeUnit.SECONDS)!!.body!!.size
        assertTrue("第二句音频不完整：$secondUpload 字节", secondUpload > 16_000 * 2 * 1)
    }

    @Test
    fun asrErrorsAreMapped() = runBlocking {
        suspend fun expect(error: AsrError, response: MockResponse?, key: String? = "sk") {
            response?.let(server::enqueue)
            try {
                ApiSpeechInput(api, { key }, { FakeSource(concat(tone(600), noise(1000))) }).listen("zh-CN").toList()
                fail("应抛出 $error")
            } catch (e: AsrException) {
                assertEquals(error, e.error)
            }
        }
        expect(AsrError.Rejected, MockResponse.Builder().code(401).body("""{"error":"invalid key"}""").build())
        expect(AsrError.Busy, MockResponse.Builder().code(429).build())
        expect(AsrError.NoSpeech, MockResponse.Builder().body("""{"text":"  "}""").build())
        expect(AsrError.Rejected, null, key = null)
    }

    // ---------- 合成 ----------

    private class FakePlayer : PcmPlayer {
        val out = ByteArrayOutputStream()
        val writes = mutableListOf<Int>()
        var flushes = 0

        @Synchronized
        override fun write(data: ByteArray, offset: Int, length: Int) {
            writes += length
            out.write(data, offset, length)
        }

        override fun drain() = Unit

        override fun flush() {
            flushes++
        }

        override fun release() = Unit
    }

    private fun pcm(size: Int, seed: Int) = ByteArray(size) { (it * 31 + seed).toByte() }

    /** 全部为负值的字节，覆盖奇数分块边界上暂存的有符号字节 */
    private fun negativePcm(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) or 0x80).toByte() }

    @Test
    fun segmentsArePlayedInOrderWithSampleAlignment() = runBlocking {
        val first = negativePcm(3001, 1)
        val second = negativePcm(4000, 2)
        // 两段并发请求，到达顺序不定：按请求里的文字返回对应音频。第一段故意切成奇数大小的分块
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.body!!.utf8().contains("第一句")) {
                    MockResponse.Builder().body(Buffer().write(first)).throttleBody(777, 5, TimeUnit.MILLISECONDS).build()
                } else {
                    MockResponse.Builder().body(Buffer().write(second)).build()
                }
        }
        val player = FakePlayer()
        val output = ApiSpeechOutput(api, { "sk-test" }, { TtsVoice.Tide }, { player }, io = Dispatchers.IO)
        assertTrue(output.prepare("zh-CN"))
        output.enqueue("第一句。")
        output.enqueue("第二句。")
        withTimeout(10_000) { output.awaitIdle() }

        // 奇数结尾的最后一个字节不能拼到下一段：第一段按样本对齐只播 3000 字节
        assertArrayEquals(first.copyOf(3000) + second, player.out.toByteArray())
        assertTrue(player.writes.all { it % 2 == 0 })
        val requests = listOf(server.takeRequest(1, TimeUnit.SECONDS)!!, server.takeRequest(1, TimeUnit.SECONDS)!!)
        assertEquals(setOf("/v1/tools/tts"), requests.map { it.url.encodedPath }.toSet())
        assertTrue(requests.all { it.headers["Authorization"] == "Bearer sk-test" })
        val bodies = requests.map { it.body!!.utf8() }
        assertTrue(bodies.all { it.contains("\"voice_id\":\"tide\"") && it.contains("\"format\":\"pcm\"") && it.contains("\"filter_markdown\":true") })
        assertTrue(bodies.any { it.contains("第一句") } && bodies.any { it.contains("第二句") })
    }

    @Test
    fun prefetchIsLimitedAndStopClearsQueue() = runBlocking {
        // 每段都很慢（约 1 秒）：两段并发时哪一段拿到哪个响应不确定，全部放慢才能保证观察窗口内都没播完
        repeat(4) {
            server.enqueue(MockResponse.Builder().body(Buffer().write(pcm(2000, it))).throttleBody(200, 100, TimeUnit.MILLISECONDS).build())
        }
        val player = FakePlayer()
        val output = ApiSpeechOutput(api, { "sk-test" }, { TtsVoice.Stella }, { player }, io = Dispatchers.IO)
        output.prepare("zh-CN")
        repeat(4) { output.enqueue("第${it}句。") }
        kotlinx.coroutines.delay(400)
        assertEquals("同时在途不超过 2 段", 2, server.requestCount)

        output.stop()
        val playedAtStop = player.out.size()
        assertEquals(1, player.flushes)
        withTimeout(1_000) { output.awaitIdle() }
        kotlinx.coroutines.delay(300)
        assertEquals("停止后不再请求排队的段落", 2, server.requestCount)
        assertEquals("停止后不再播放迟到的音频", playedAtStop, player.out.size())
    }

    @Test
    fun prepareFailsWithoutApiKey() = runBlocking {
        val output = ApiSpeechOutput(api, { null }, { TtsVoice.Stella }, { FakePlayer() })
        assertTrue(!output.prepare("zh-CN"))
    }
}
