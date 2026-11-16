package io.github.smalltailqwq.shallowdrop.data.voice

import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack

/**
 * Robolectric 自带的 AudioTrack 写入即视为播完、缓冲无限大；这里默认让播放位置停在 0，
 * 模拟「音频已写入缓冲、还没播到」的状态，记下缓冲里还有多少字节，并可限定缓冲容量（[capacity]）：
 * 非阻塞写入只收放得下的部分，满了返回 0。
 * [beforeWrite] 在下一次实际写入之前执行一次，用来在「核对轮次」与「写入」之间插入别的操作。
 */
@Implements(AudioTrack::class)
class StalledAudioTrack : ShadowAudioTrack() {
    @Implementation
    override fun getPlaybackHeadPosition(): Int = if (stalled) 0 else buffered / 2

    @Implementation
    override fun native_write_byte(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, format: Int, isBlocking: Boolean): Int {
        beforeWrite?.also { beforeWrite = null }?.invoke()
        if (isBlocking) blockingWrites++
        val size = if (isBlocking) sizeInBytes else minOf(sizeInBytes, capacity - buffered)
        if (size <= 0) return 0
        val n = super.native_write_byte(audioData, offsetInBytes, size, format, isBlocking)
        if (n > 0) buffered += n
        return n
    }

    @Implementation
    override fun flush() {
        super.flush()
        buffered = 0
    }

    companion object {
        @Volatile var stalled = true

        @Volatile var buffered = 0

        @Volatile var capacity = Int.MAX_VALUE

        @Volatile var blockingWrites = 0

        @Volatile var beforeWrite: (() -> Unit)? = null
    }
}

/** 播放器在「打断」前后的状态：上一轮的线程（收尾、迟到的写入）不能影响下一轮。 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [StalledAudioTrack::class])
class AudioTrackPlayerTest {
    private val rate = 24_000

    @Before
    fun setUp() {
        StalledAudioTrack.stalled = true
        StalledAudioTrack.buffered = 0
        StalledAudioTrack.capacity = Int.MAX_VALUE
        StalledAudioTrack.blockingWrites = 0
        StalledAudioTrack.beforeWrite = null
    }

    private fun tone(ms: Int): ByteArray {
        val frames = rate * ms / 1000
        val out = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val sample = (sin(2 * PI * 440 * i / rate) * 0.3 * 32767).toInt()
            out[i * 2] = sample.toByte()
            out[i * 2 + 1] = (sample shr 8).toByte()
        }
        return out
    }

    private fun bytes(ms: Int) = rate * ms / 1000 * 2

    private fun AudioTrackPlayer.write(audio: ByteArray, round: Int = this.round) = write(audio, 0, audio.size, round)

    /** 等到条件成立（最多 2 秒） */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(what, condition())
    }

    private fun Thread.expectDone(what: String, ms: Long = 500) {
        join(ms)
        assertFalse(what, isAlive)
    }

    @Test
    fun levelIsSilentUntilPlaybackStartsAndAfterFlush() {
        val player = AudioTrackPlayer(rate)
        assertEquals(0f, player.level(), 0f)
        player.write(tone(400))
        assertTrue("开播后应能读到响度", player.level() > 0f)
        player.flush()
        assertEquals(0f, player.level(), 0f)
        assertEquals("写入一律用非阻塞方式", 0, StalledAudioTrack.blockingWrites)
        player.release()
    }

    @Test
    fun interruptedDrainReturnsPromptlyAndLeavesNextRoundAlone() {
        val player = AudioTrackPlayer(rate)
        val audio = tone(400)
        val old = player.round
        player.write(audio, old)
        // 上一轮最后一句写完，正在等它播完（播放位置不前进，会一直等到超时）
        val draining = thread { player.drain(old) }
        Thread.sleep(100)

        // 打断，下一轮随即开始播放
        player.flush()
        player.write(audio)
        assertTrue(player.level() > 0f)

        draining.expectDone("被打断后应立即结束等待，而不是等到超时")
        assertTrue("上一轮收尾不能清掉下一轮正在播放的音频", player.level() > 0f)
        assertEquals(audio.size, StalledAudioTrack.buffered)
        player.release()
    }

    @Test
    fun callsFromAnInterruptedRoundDoNothing() {
        val player = AudioTrackPlayer(rate)
        val audio = tone(400)
        val old = player.round
        player.flush()

        // 上一轮的线程在打断之后才走到这里（例如刚从网络读到一块音频）
        player.write(audio, old)
        assertEquals("上一轮迟到的音频不能进播放器", 0, StalledAudioTrack.buffered)
        assertEquals(0f, player.level(), 0f)

        player.write(audio)
        // 上一轮迟到的收尾：不等待，也不清掉这一轮
        thread { player.drain(old) }.expectDone("上一轮迟到的收尾不应等待")
        assertEquals(audio.size, StalledAudioTrack.buffered)
        assertTrue(player.level() > 0f)
        player.release()
    }

    @Test
    fun staleDrainDoesNotStartTheNextRoundEarly() {
        val player = AudioTrackPlayer(rate)
        val old = player.round
        player.flush()
        // 下一轮刚写了一点，还在攒起播缓冲
        player.write(tone(50))
        assertEquals(0f, player.level(), 0f)

        player.drain(old)
        assertEquals("上一轮迟到的收尾不能替下一轮提前开播", 0f, player.level(), 0f)
        player.release()
    }

    @Test
    fun flushCannotLandBetweenRoundCheckAndWrite() {
        val player = AudioTrackPlayer(rate)
        val audio = tone(400)
        val old = player.round
        var stopping: Thread? = null
        StalledAudioTrack.beforeWrite = {
            // 上一轮已核对过轮次、正要往播放器里写，此刻另一线程发起打断：
            // 它必须排在这次写入之后，否则清空之后才写进去的这块音频会留给下一轮
            val flushing = thread { player.flush() }.also { it.join(200) }
            stopping = flushing
            assertTrue("打断不能插进核对与写入之间", flushing.isAlive)
        }
        player.write(audio, old)
        stopping!!.expectDone("写入一结束打断就应完成", 2_000)

        assertEquals("上一轮的音频已被打断清掉", 0, StalledAudioTrack.buffered)
        assertEquals(0f, player.level(), 0f)
        // 下一轮从干净的缓冲开始
        player.write(audio)
        assertEquals(audio.size, StalledAudioTrack.buffered)
        player.release()
    }

    @Test
    fun fullBufferWaitsOutsideTheLockAndStopsWhenInterrupted() {
        val player = AudioTrackPlayer(rate)
        val audio = tone(400)
        StalledAudioTrack.capacity = bytes(200)
        val old = player.round
        // 缓冲只放得下一半：写入线程在等播放腾出空间
        val writing = thread { player.write(audio, old) }
        await("缓冲应已写满") { StalledAudioTrack.buffered == bytes(200) }

        thread { player.flush() }.expectDone("等待缓冲期间不能占着打断要用的锁")
        writing.expectDone("被打断后写入线程应退出")
        // 空间有了，但余下的一半属于上一轮，不能再写进去
        assertEquals(0, StalledAudioTrack.buffered)
        assertEquals(0f, player.level(), 0f)
        assertEquals(0, StalledAudioTrack.blockingWrites)
        player.release()
    }

    @Test
    fun fullBufferStartsPlaybackAndWritingResumesAsItDrains() {
        val player = AudioTrackPlayer(rate)
        val audio = tone(400)
        // 缓冲比起播缓冲（150ms）还小：不开播就永远腾不出空间
        StalledAudioTrack.capacity = bytes(100)
        val writing = thread { player.write(audio) }
        await("缓冲应已写满") { StalledAudioTrack.buffered == bytes(100) }
        await("放不下时应先开播") { player.level() > 0f }
        assertTrue(writing.isAlive)

        // 播放腾出了空间：余下的写完
        StalledAudioTrack.capacity = Int.MAX_VALUE
        writing.expectDone("腾出空间后应写完", 2_000)
        assertEquals(audio.size, StalledAudioTrack.buffered)
        player.release()
    }

    @Test
    fun sameRoundKeepsWorkingAfterDrain() {
        StalledAudioTrack.stalled = false
        val player = AudioTrackPlayer(rate)
        val audio = tone(400)
        val round = player.round
        player.write(audio, round)
        player.drain(round)
        assertEquals("读完后清零，下一句重新攒起播缓冲", 0, StalledAudioTrack.buffered)

        // 同一轮里稍后又来一句（回复还在生成）：轮次没变，照常播放
        assertEquals(round, player.round)
        player.write(audio, round)
        assertEquals(audio.size, StalledAudioTrack.buffered)
        player.release()
    }

    @Test
    fun releaseEndsPendingWorkAndLaterCallsAreIgnored() {
        val player = AudioTrackPlayer(rate)
        val audio = tone(400)
        val round = player.round
        StalledAudioTrack.capacity = bytes(600)
        player.write(audio, round)
        val draining = thread { runCatching { player.drain(round) } }
        // 另一个线程卡在缓冲已满的写入上
        val writing = thread { runCatching { player.write(audio, round) } }
        await("缓冲应已写满") { StalledAudioTrack.buffered == bytes(600) }
        Thread.sleep(50)
        player.release()
        draining.expectDone("释放播放器后不应还有线程在等它播完")
        writing.expectDone("释放播放器后不应还有线程在等着写")

        // 释放后迟到的写入不再碰播放器
        StalledAudioTrack.capacity = Int.MAX_VALUE
        val before = StalledAudioTrack.buffered
        player.write(audio, round)
        assertEquals(before, StalledAudioTrack.buffered)
    }
}
