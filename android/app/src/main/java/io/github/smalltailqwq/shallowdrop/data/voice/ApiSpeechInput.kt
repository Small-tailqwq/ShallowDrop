package io.github.smalltailqwq.shallowdrop.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.smalltailqwq.shallowdrop.data.api.DeepSeekSpeechApi
import io.github.smalltailqwq.shallowdrop.data.api.SpeechApiException
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrError
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrEvent
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrException
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechInput
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

/** 16kHz 单声道 16-bit PCM 的录音来源；read 阻塞到读满一帧，返回读到的样本数 */
interface PcmSource : Closeable {
    fun read(frame: ShortArray): Int
}

/**
 * 语音识别：本地录音 + 端点检测，说完后整句上传 DeepSeek ASR（/v1/tools/asr）。
 * 接口不支持流式识别，所以没有中间结果；开口时发 [AsrEvent.SpeechStarted]，说完开始上传时发 [AsrEvent.Recognizing]。
 *
 * 持续录音：首次聆听时打开录音来源，之后整个语音对话期间保持打开，帧数据进入有界缓冲（最多约 10 秒）。
 * 每次聆听从上一次停下的位置接着读，识别、等待回复期间用户继续说的话不会丢；
 * [release]（静音、挂断）时关闭录音并丢弃缓冲。
 *
 * @param openSource 打开录音来源；失败抛 [AsrException]
 */
class ApiSpeechInput(
    private val api: DeepSeekSpeechApi,
    private val apiKey: suspend () -> String?,
    private val openSource: () -> PcmSource,
    private val detector: (bargeIn: Boolean) -> EndpointDetector = { bargeIn ->
        // 插话时扬声器还在播放：提高门槛，避免把残余回声当成开口
        if (bargeIn) EndpointDetector(startMs = 200, marginDb = 18.0, minSpeechMs = 300, noSpeechMs = Int.MAX_VALUE) else EndpointDetector()
    },
) : SpeechInput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在运行的录音：后台持续读帧放入缓冲；录音出错时以异常关闭缓冲 */
    private inner class Capture(val source: PcmSource) {
        val frames = Channel<ShortArray>(BUFFER_FRAMES, BufferOverflow.DROP_OLDEST)
        val job = scope.launch {
            try {
                while (isActive) {
                    val frame = ShortArray(FRAME_SIZE)
                    if (source.read(frame) < 0) {
                        frames.close(AsrException(AsrError.Unavailable, "录音中断"))
                        break
                    }
                    frames.send(frame)
                }
            } catch (error: Throwable) {
                frames.close(error as? AsrException ?: AsrException(AsrError.Unavailable, error.message))
            }
        }

        fun close() {
            frames.close()
            // 关闭来源以解除阻塞中的 read
            runCatching { source.close() }
            job.cancel()
        }
    }

    @Volatile
    private var capture: Capture? = null

    @Synchronized
    private fun capture(): Capture = capture ?: Capture(openSource()).also { capture = it }

    override fun isAvailable(): Boolean = true

    @Synchronized
    override fun release() {
        capture?.close()
        capture = null
    }

    override fun listen(languageTag: String, bargeIn: Boolean): Flow<AsrEvent> = flow {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw AsrException(AsrError.Rejected, "未设置 API Key")
        val vad = detector(bargeIn)
        check(vad.frameSize == FRAME_SIZE) { "端点检测的帧长必须与录音一致" }
        val frames = capture().frames
        emit(AsrEvent.Ready)
        var count = 0
        var started = false
        while (true) {
            val frame = try {
                frames.receive()
            } catch (error: ClosedReceiveChannelException) {
                release()
                throw AsrException(AsrError.Unavailable, "录音已停止")
            } catch (error: AsrException) {
                release()
                throw error
            }
            val result = vad.feed(frame)
            // 每 3 帧（60ms）更新一次音量，足够驱动动画
            if (++count % 3 == 0) emit(AsrEvent.Level(vad.level))
            when (result) {
                EndpointDetector.Result.Timeout -> throw AsrException(AsrError.NoSpeech)
                EndpointDetector.Result.Ended -> break
                EndpointDetector.Result.Speaking -> if (!started) {
                    started = true
                    emit(AsrEvent.SpeechStarted)
                }
                EndpointDetector.Result.Waiting -> started = false
            }
        }
        val samples = vad.speech()
        emit(AsrEvent.Recognizing)
        val text = try {
            api.transcribe(key, encodeWav(samples, DeepSeekSpeechApi.ASR_SAMPLE_RATE))
        } catch (error: SpeechApiException) {
            throw AsrException(mapStatus(error.status), error.message)
        } catch (error: IOException) {
            throw AsrException(AsrError.Network, error.message)
        }
        if (text.isBlank()) throw AsrException(AsrError.NoSpeech)
        emit(AsrEvent.Final(text.trim()))
    }.flowOn(Dispatchers.IO)

    private fun mapStatus(status: Int?): AsrError = when (status) {
        401, 402, 403 -> AsrError.Rejected
        408, 429, 500, 502, 503, 504 -> AsrError.Busy
        else -> AsrError.Other
    }

    private companion object {
        /** 20ms @ 16kHz */
        const val FRAME_SIZE = DeepSeekSpeechApi.ASR_SAMPLE_RATE / 50

        /** 缓冲上限约 10 秒，超出时丢弃最早的帧 */
        const val BUFFER_FRAMES = 500
    }
}

/** 麦克风录音；优先 VOICE_COMMUNICATION 以启用系统回声消除，失败退回 MIC */
class MicrophoneSource private constructor(private val record: AudioRecord) : PcmSource {
    private val effects = listOfNotNull(
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null,
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null,
    )

    override fun read(frame: ShortArray): Int {
        var offset = 0
        while (offset < frame.size) {
            val n = record.read(frame, offset, frame.size - offset)
            if (n < 0) return -1
            offset += n
        }
        return offset
    }

    override fun close() {
        effects.forEach { runCatching { it.release() } }
        runCatching { record.stop() }
        record.release()
    }

    companion object {
        /** 调用前需已获得 RECORD_AUDIO 权限 */
        @SuppressLint("MissingPermission")
        fun open(sampleRate: Int = DeepSeekSpeechApi.ASR_SAMPLE_RATE): MicrophoneSource {
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw AsrException(AsrError.Unavailable, "不支持的录音参数")
            for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
                val record = try {
                    AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, sampleRate / 5 * 2))
                } catch (error: SecurityException) {
                    throw AsrException(AsrError.Permission, error.message)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                try {
                    record.startRecording()
                } catch (error: IllegalStateException) {
                    record.release()
                    throw AsrException(AsrError.Busy, error.message)
                }
                return MicrophoneSource(record)
            }
            throw AsrException(AsrError.Unavailable, "无法打开麦克风")
        }
    }
}

/**
 * 仅 debug 构建：用推送到设备的 WAV（16kHz 单声道 16-bit）代替麦克风，便于在没有麦克风的模拟器上测试完整链路。
 * 按实时速度送出静音，直到 [file] 出现就把它当作一次说话送出（之后改名为 .done，避免重复），再继续送静音。
 * 文件可以在任何时候推送，包括模型正在朗读时（用于测试插话）。
 */
class WavFileSource(private val file: File, private val sampleRate: Int = DeepSeekSpeechApi.ASR_SAMPLE_RATE) : PcmSource {
    private var pcm = ShortArray(0)
    private var position = 0

    private fun load(): ShortArray {
        val bytes = file.readBytes()
        // 按块查找 data（fmt 块不一定是 16 字节）
        var at = 12
        var data = ByteArray(0)
        while (at + 8 <= bytes.size) {
            val size = (bytes[at + 4].toInt() and 0xff) or ((bytes[at + 5].toInt() and 0xff) shl 8) or
                ((bytes[at + 6].toInt() and 0xff) shl 16) or ((bytes[at + 7].toInt() and 0xff) shl 24)
            if (String(bytes, at, 4, Charsets.US_ASCII) == "data") {
                data = bytes.copyOfRange(at + 8, minOf(bytes.size, at + 8 + size))
                break
            }
            at += 8 + size + (size and 1)
        }
        val done = File(file.parentFile, file.name + ".done")
        done.delete()
        file.renameTo(done)
        return ShortArray(data.size / 2) { ((data[it * 2].toInt() and 0xff) or (data[it * 2 + 1].toInt() shl 8)).toShort() }
    }

    override fun read(frame: ShortArray): Int {
        if (position >= pcm.size && file.isFile) {
            pcm = load()
            position = 0
        }
        for (i in frame.indices) frame[i] = if (position < pcm.size) pcm[position++] else 0
        Thread.sleep(frame.size * 1000L / sampleRate)
        return frame.size
    }

    override fun close() = Unit
}
