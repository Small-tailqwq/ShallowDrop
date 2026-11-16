package io.github.smalltailqwq.shallowdrop.data.voice

import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 本地端点检测（VAD）。DeepSeek 的 ASR 只能整句上传、一次返回全文，不能边说边识别，
 * 因此由客户端判断「开始说话」与「一句说完」，说完立即上传，尽量缩短等待：
 *
 * - 每帧计算 dBFS 能量，并跟踪环境噪声底；高于噪声底 [marginDb]（且不低于 [floorDb]）视为有声。
 * - 连续 [startMs] 有声判为开口；开口前保留 [preRollMs] 的音频，避免吞掉第一个字。
 * - 开口后连续 [endSilenceMs] 静音判为说完（0.9 秒：再短会把句间的自然停顿当成说完，切断一句话）；有声总时长不足 [minSpeechMs] 视为噪声，回到等待。
 * - [noSpeechMs] 内没有开口返回 [Result.Timeout]；单句达到 [maxSpeechMs]（ASR 上限 120 秒内）强制结束。
 */
class EndpointDetector(
    private val sampleRate: Int = 16_000,
    private val frameMs: Int = 20,
    private val startMs: Int = 60,
    private val endSilenceMs: Int = 900,
    private val preRollMs: Int = 300,
    private val minSpeechMs: Int = 250,
    private val noSpeechMs: Int = 8_000,
    private val maxSpeechMs: Int = 60_000,
    private val marginDb: Double = 12.0,
    private val floorDb: Double = -50.0,
) {
    enum class Result { Waiting, Speaking, Ended, Timeout }

    /** 每帧样本数 */
    val frameSize = sampleRate * frameMs / 1000

    private var noiseDb = -60.0
    private var started = false
    private var voicedRun = 0
    private var silentRun = 0
    private var voicedTotal = 0
    private var waited = 0
    private var spoken = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private val speech = ArrayList<ShortArray>()

    /** 最近一帧的输入音量，0..1，用于动画 */
    var level = 0f
        private set

    fun feed(frame: ShortArray): Result {
        val db = dbfs(frame)
        level = ((db + 60) / 50).coerceIn(0.0, 1.0).toFloat()
        val voiced = db > max(noiseDb + marginDb, floorDb)
        if (!started) {
            // 噪声底只在未开口时更新：安静时快速下降，嘈杂时缓慢上升
            if (!voiced) noiseDb = if (db < noiseDb) db else noiseDb * 0.95 + db * 0.05
            preRoll.addLast(frame.copyOf())
            while (preRoll.size > frames(preRollMs) + frames(startMs)) preRoll.removeFirst()
            voicedRun = if (voiced) voicedRun + 1 else 0
            if (voicedRun >= frames(startMs)) {
                started = true
                speech.addAll(preRoll)
                preRoll.clear()
                voicedTotal = voicedRun
                silentRun = 0
                spoken = 0
                return Result.Speaking
            }
            waited++
            return if (waited >= frames(noSpeechMs)) Result.Timeout else Result.Waiting
        }
        speech.add(frame.copyOf())
        spoken++
        if (voiced) {
            voicedTotal++
            silentRun = 0
        } else {
            silentRun++
        }
        if (spoken >= frames(maxSpeechMs)) return Result.Ended
        if (silentRun >= frames(endSilenceMs)) {
            if (voicedTotal < frames(minSpeechMs)) {
                // 太短，多半是咳嗽、碰撞声，丢弃并继续等待
                reset()
                return Result.Waiting
            }
            return Result.Ended
        }
        return Result.Speaking
    }

    /** 本句音频（含开口前的预留与结尾静音） */
    fun speech(): ShortArray {
        val out = ShortArray(speech.sumOf { it.size })
        var offset = 0
        for (frame in speech) {
            frame.copyInto(out, offset)
            offset += frame.size
        }
        return out
    }

    private fun reset() {
        started = false
        voicedRun = 0
        silentRun = 0
        voicedTotal = 0
        speech.clear()
    }

    private fun frames(ms: Int) = max(1, ms / frameMs)

    private companion object {
        fun dbfs(frame: ShortArray): Double {
            if (frame.isEmpty()) return -100.0
            var sum = 0.0
            for (sample in frame) sum += sample.toDouble() * sample
            val rms = sqrt(sum / frame.size)
            return if (rms < 1) -100.0 else 20 * log10(rms / 32768.0)
        }
    }
}

/** 编码为 ASR 接受的 WAV：单声道、16-bit PCM、小端（对照 recorder.ts encodeWav） */
fun encodeWav(samples: ShortArray, sampleRate: Int): ByteArray {
    val out = ByteArrayOutputStream(44 + samples.size * 2)
    fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
    fun le32(value: Int) = out.write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()))
    fun le16(value: Int) = out.write(byteArrayOf(value.toByte(), (value shr 8).toByte()))
    val dataSize = samples.size * 2
    ascii("RIFF")
    le32(36 + dataSize)
    ascii("WAVE")
    ascii("fmt ")
    le32(16)
    le16(1) // PCM
    le16(1) // 单声道
    le32(sampleRate)
    le32(sampleRate * 2)
    le16(2)
    le16(16)
    ascii("data")
    le32(dataSize)
    val bytes = ByteArray(dataSize)
    for (i in samples.indices) {
        bytes[i * 2] = samples[i].toInt().toByte()
        bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
    }
    out.write(bytes)
    return out.toByteArray()
}
