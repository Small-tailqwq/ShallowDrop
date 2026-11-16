package io.github.smalltailqwq.shallowdrop.data.voice

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmEnvelopeTest {
    private val rate = 24_000

    /** 16-bit 小端单声道 PCM：440Hz 正弦，amplitude 为满幅的比例 */
    private fun tone(ms: Int, amplitude: Double): ByteArray {
        val frames = rate * ms / 1000
        val out = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val sample = (sin(2 * PI * 440 * i / rate) * amplitude * 32767).toInt()
            out[i * 2] = sample.toByte()
            out[i * 2 + 1] = (sample shr 8).toByte()
        }
        return out
    }

    private fun PcmEnvelope.write(data: ByteArray) = write(data, 0, data.size)

    private fun frameAt(ms: Int) = rate.toLong() * ms / 1000

    @Test
    fun levelFollowsPlaybackPositionNotLatestWrite() {
        val envelope = PcmEnvelope(rate)
        envelope.write(tone(200, 0.0))
        envelope.write(tone(200, 0.3))
        envelope.write(tone(200, 0.0))

        assertEquals(0f, envelope.levelAt(frameAt(100)), 0.001f)
        val loud = envelope.levelAt(frameAt(300))
        assertTrue("有声段应有明显响度，实际 $loud", loud > 0.6f)
        assertEquals(0f, envelope.levelAt(frameAt(500)), 0.001f)
    }

    @Test
    fun louderAudioGivesHigherLevelWithinRange() {
        val envelope = PcmEnvelope(rate)
        envelope.write(tone(100, 0.05))
        envelope.write(tone(100, 0.2))
        envelope.write(tone(100, 1.0))
        val quiet = envelope.levelAt(frameAt(50))
        val normal = envelope.levelAt(frameAt(150))
        val full = envelope.levelAt(frameAt(250))
        assertTrue(quiet > 0.1f && quiet < normal && normal < full)
        assertEquals(1f, full, 0f)
    }

    @Test
    fun negativeSamplesAreDecodedAsSigned() {
        // 恒为 -4096 的直流：均方根应是 0.125，而不是把高字节当无符号后的接近满幅
        val data = ByteArray(rate / 10 * 2) { if (it % 2 == 0) 0x00 else 0xF0.toByte() }
        val envelope = PcmEnvelope(rate)
        envelope.write(data)
        assertEquals(0.125f * 5.5f, envelope.levelAt(frameAt(50)), 0.01f)
    }

    @Test
    fun unwrittenOverwrittenAndResetPositionsAreSilent() {
        val envelope = PcmEnvelope(rate, historyMs = 1_000)
        envelope.write(tone(500, 0.3))
        assertEquals("还没写到的位置", 0f, envelope.levelAt(frameAt(600)), 0f)
        assertEquals(0f, envelope.levelAt(-1), 0f)

        // 再写 1.5 秒静音：最初 0.5 秒的记录已被环形缓冲覆盖，不能读到别的时间段的值
        envelope.write(tone(1_500, 0.0))
        assertEquals(0f, envelope.levelAt(frameAt(100)), 0f)

        envelope.write(tone(200, 0.3))
        assertTrue(envelope.levelAt(frameAt(2_100)) > 0.6f)
        envelope.reset()
        assertEquals(0f, envelope.levelAt(frameAt(2_100)), 0f)
        assertEquals(0f, envelope.levelAt(0), 0f)
    }

    @Test
    fun chunkBoundariesDoNotAffectWindows() {
        val data = tone(300, 0.3)
        val whole = PcmEnvelope(rate).apply { write(data) }
        val chunked = PcmEnvelope(rate)
        var offset = 0
        // 分块大小与时间窗不成整数倍
        while (offset < data.size) {
            val n = minOf(1_234, data.size - offset)
            chunked.write(data, offset, n)
            offset += n
        }
        for (ms in listOf(10, 90, 150, 290)) {
            assertEquals(whole.levelAt(frameAt(ms)), chunked.levelAt(frameAt(ms)), 0f)
        }
    }
}
