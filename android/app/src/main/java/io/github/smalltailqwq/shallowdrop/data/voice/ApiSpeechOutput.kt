package io.github.smalltailqwq.shallowdrop.data.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import io.github.smalltailqwq.shallowdrop.data.api.DeepSeekSpeechApi
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechOutput
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Response

/** 24kHz 单声道 16-bit PCM 的播放端 */
interface PcmPlayer {
    /** 阻塞写入（缓冲满时等待播放） */
    fun write(data: ByteArray, offset: Int, length: Int)

    /** 阻塞到已写入的音频播放完 */
    fun drain()

    /** 立即停止并丢弃缓冲 */
    fun flush()

    fun release()
}

/**
 * 语音合成：DeepSeek TTS（/v1/tools/tts）返回流式 PCM，边收边播。
 *
 * 延迟优化：每段文字入队即发起请求，同时最多 [prefetch] 段在途（通常是一段在播、一段预取），
 * 上一句播完时下一句往往已收到首帧，句间几乎没有停顿；每段播完才释放名额。
 * [stop] 取消所有请求、清空队列与播放缓冲。
 */
class ApiSpeechOutput(
    private val api: DeepSeekSpeechApi,
    private val apiKey: suspend () -> String?,
    private val voice: () -> TtsVoice,
    private val createPlayer: () -> PcmPlayer,
    private val prefetch: Int = 2,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SpeechOutput {
    private inner class Item(val text: String) {
        val response = CompletableDeferred<Response>()
        val permit = AtomicBoolean(false)
        var job: Job? = null
    }

    /** 一次连续朗读的会话；stop 时整体丢弃，换新的 */
    private inner class Session(val key: String, val player: PcmPlayer) {
        val scope = CoroutineScope(SupervisorJob() + io)
        val queue = Channel<Item>(Channel.UNLIMITED)
        val slots = Semaphore(prefetch)

        init {
            scope.launch { play() }
        }

        fun enqueue(text: String) {
            val item = Item(text)
            item.job = scope.launch {
                slots.acquire()
                item.permit.set(true)
                try {
                    item.response.complete(api.synthesize(key, text, voice()))
                } catch (error: Throwable) {
                    item.response.completeExceptionally(error)
                }
            }
            queue.trySend(item)
        }

        private suspend fun play() {
            val buffer = ByteArray(8 * 1024)
            for (item in queue) {
                try {
                    item.response.await().use { response ->
                        val input = response.body.byteStream()
                        // 网络分块常是奇数字节：多出的一个字节留到下一次，保证按 16-bit 样本对齐写入。
                        // 用独立标志记录是否有暂存：字节有符号，不能用 -1 之类的值表示「没有」，
                        // 否则负值字节会被丢掉，之后整个流错位一个字节，高低位颠倒成刺耳的噪声。
                        var hasCarry = false
                        var carry: Byte = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val start = if (hasCarry) 1 else 0
                            val read = input.read(buffer, start, buffer.size - start)
                            if (read < 0) break
                            if (hasCarry) buffer[0] = carry
                            var n = start + read
                            hasCarry = n % 2 == 1
                            if (hasCarry) carry = buffer[--n]
                            if (n > 0) player.write(buffer, 0, n)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // 单段失败（网络、限流等）跳过，不中断后续朗读
                    Log.w(TAG, "朗读失败：${item.text.take(20)}", error)
                } finally {
                    if (item.permit.getAndSet(false)) slots.release()
                    // stop 之后旧会话的收尾不能影响新会话的计数
                    if (session === this@Session) {
                        if (pending.value == 1) runCatching { player.drain() }
                        pending.update { (it - 1).coerceAtLeast(0) }
                    }
                }
            }
        }

        fun cancel() {
            scope.cancel()
            queue.close()
            while (true) {
                val item = queue.tryReceive().getOrNull() ?: break
                item.job?.cancel()
                if (item.response.isCompleted && !item.response.isCancelled) {
                    runCatching { item.response.getCompleted().close() }
                }
            }
        }
    }

    private val pending = MutableStateFlow(0)
    private var player: PcmPlayer? = null
    private var key: String? = null
    @Volatile
    private var session: Session? = null

    override suspend fun prepare(languageTag: String): Boolean {
        key = apiKey()?.takeIf { it.isNotBlank() } ?: return false
        if (player == null) player = runCatching(createPlayer).getOrNull() ?: return false
        return true
    }

    @Synchronized
    override fun enqueue(text: String) {
        val key = key ?: return
        val player = player ?: return
        val current = session ?: Session(key, player).also { session = it }
        pending.update { it + 1 }
        current.enqueue(text)
    }

    override suspend fun awaitIdle() {
        pending.first { it == 0 }
    }

    @Synchronized
    override fun stop() {
        session?.cancel()
        session = null
        pending.value = 0
        player?.flush()
    }

    override fun release() {
        stop()
        player?.release()
        player = null
    }

    private companion object {
        const val TAG = "ApiSpeechOutput"
    }
}

/**
 * AudioTrack 流式播放 24kHz PCM。
 *
 * 起播缓冲：每轮先攒 [startMs] 再开始播放（对照 readAloud.ts 的 START_DELAY），避免首帧刚到就播、
 * 网络稍慢即断流产生的咔哒声；一轮读完（[drain]）后暂停，下一轮重新攒。缓冲区留 1 秒余量吸收网络抖动。
 */
class AudioTrackPlayer(
    private val sampleRate: Int = DeepSeekSpeechApi.TTS_SAMPLE_RATE,
    startMs: Int = 150,
) : PcmPlayer {
    private val startFrames = sampleRate * startMs / 1000L
    private val bufferBytes = maxOf(
        AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4,
        sampleRate * 2,
    )
    private val track: AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()

    /** 自上次 flush 以来写入的帧数（单声道 16-bit：字节数 / 2） */
    @Volatile
    private var written = 0L

    @Volatile
    private var playing = false

    override fun write(data: ByteArray, offset: Int, length: Int) {
        // 暂停状态下写满缓冲会一直阻塞：放不下时先开播
        if (!playing && (written * 2 + length) > bufferBytes / 2) start()
        var done = 0
        while (done < length) {
            val n = track.write(data, offset + done, length - done)
            if (n <= 0) return
            done += n
        }
        written += length / 2
        if (!playing && written >= startFrames) start()
    }

    private fun start() {
        playing = true
        track.play()
    }

    override fun drain() {
        // 不足起播缓冲的短句：直接开播
        if (!playing && written > 0) start()
        val target = written
        // 留出余量：按剩余时长再加 1 秒等待播放头追上
        val deadline = SystemClock.elapsedRealtime() + (target - track.playbackHeadPosition.toLong()).coerceAtLeast(0) * 1000 / sampleRate + 1000
        while (track.playbackHeadPosition.toLong() < target && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(20)
        }
        // 这一轮读完：停下并清零，下一轮重新攒起播缓冲
        reset()
    }

    override fun flush() = reset()

    private fun reset() {
        runCatching {
            track.pause()
            track.flush()
            track.stop()
        }
        playing = false
        written = 0
    }

    override fun release() {
        runCatching { track.stop() }
        track.release()
    }
}

/** 仅 debug 构建：把写入播放器的 PCM 同时追加到 [file]，用于检查实际播放的音频是否完好 */
class TeePcmPlayer(private val inner: PcmPlayer, file: java.io.File) : PcmPlayer {
    private val out = java.io.FileOutputStream(file, true)

    override fun write(data: ByteArray, offset: Int, length: Int) {
        runCatching { out.write(data, offset, length) }
        inner.write(data, offset, length)
    }

    override fun drain() = inner.drain()

    override fun flush() = inner.flush()

    override fun release() {
        runCatching { out.close() }
        inner.release()
    }
}
