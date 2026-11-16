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
    /**
     * 当前轮次，每次 [flush] 加一。朗读方在一轮开始时取一次，之后的 [write]、[drain] 都带上它。
     * 打断来自别的线程，且不等朗读线程退出：旧线程随后的调用带的是旧轮次，播放器据此什么也不做，
     * 上一轮的音频不会写进下一轮，上一轮的收尾也不会清掉下一轮。
     */
    val round: Int

    /** 阻塞写入（缓冲满时等待播放）；[round] 已作废则不写 */
    fun write(data: ByteArray, offset: Int, length: Int, round: Int)

    /** 阻塞到已写入的音频播放完；[round] 已作废则立即返回 */
    fun drain(round: Int)

    /** 立即停止并丢弃缓冲，开始新的一轮 */
    fun flush()

    /** 此刻正在播放的音频的响度，0..1；没有在播放时为 0。供界面动效使用 */
    fun level(): Float = 0f

    fun release()
}

/**
 * 记录写入的 PCM（16-bit 小端单声道）在各时间窗内的响度，按播放位置查询。
 * 写入领先于播放（播放器有缓冲），所以不能直接用最新写入的响度，要按播放头所在的帧去取。
 * 写入、清零与查询可在不同线程。
 */
class PcmEnvelope(sampleRate: Int, windowMs: Int = 20, historyMs: Int = 4_000) {
    private val window = (sampleRate * windowMs / 1000).coerceAtLeast(1)
    private val slots = java.util.concurrent.atomic.AtomicIntegerArray(historyMs / windowMs)

    @Volatile
    private var frames = 0L
    private var sum = 0.0
    private var count = 0

    @Synchronized
    fun write(data: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length - 1
        var frame = frames
        while (i < end) {
            val sample = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)) / 32768.0
            sum += sample * sample
            i += 2
            frame++
            if (++count == window) {
                val rms = kotlin.math.sqrt(sum / window).toFloat()
                slots.set((((frame - 1) / window) % slots.length()).toInt(), rms.toRawBits())
                sum = 0.0
                count = 0
            }
        }
        frames = frame
    }

    /** 第 [frame] 帧所在时间窗的响度；尚未写到、或早已被覆盖的位置为 0 */
    fun levelAt(frame: Long): Float {
        val written = frames
        if (frame < 0 || frame >= written) return 0f
        val index = frame / window
        if (written / window - index >= slots.length()) return 0f
        val rms = Float.fromBits(slots.get((index % slots.length()).toInt()))
        // 语音的均方根大多在满幅的 2%~20%：线性放大到 0..1，保留音节之间的起伏（开方会把起伏压平）
        return (rms * LOUDNESS_GAIN).coerceAtMost(1f)
    }

    @Synchronized
    fun reset() {
        frames = 0
        sum = 0.0
        count = 0
        for (i in 0 until slots.length()) slots.set(i, 0)
    }

    private companion object {
        const val LOUDNESS_GAIN = 5.5f
    }
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
        /** 创建时（[enqueue] 持锁，与 [stop] 互斥）播放器所在的轮次 */
        val round = player.round

        @Volatile var failure: Throwable? = null
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
                            if (n > 0) player.write(buffer, 0, n, round)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // 单段失败（网络、限流等）跳过，不中断后续朗读
                    if (failure == null) failure = error
                    Log.w(TAG, "朗读失败", error)
                } finally {
                    if (item.permit.getAndSet(false)) slots.release()
                    // 最后一段：等它播完才算读完。等待会阻塞，期间可能已被 stop 并开始了新会话
                    if (session === this@Session && pending.value == 1) runCatching { player.drain(round) }
                    finished(this@Session)
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

    override val failure: Throwable? get() = session?.failure

    override val level: Float get() = player?.level() ?: 0f

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

    /**
     * [from] 的一段读完（或失败）。stop 之后旧会话的收尾不能动新会话的计数：
     * 「还是当前会话」的判断与减一须和 [enqueue]、[stop] 互斥，否则新一轮会被提前当作读完。
     */
    @Synchronized
    private fun finished(from: Session) {
        if (session === from) pending.update { (it - 1).coerceAtLeast(0) }
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

    /** 自上次清零以来写入的帧数（单声道 16-bit：字节数 / 2） */
    @Volatile
    private var written = 0L

    @Volatile
    private var playing = false

    private val envelope = PcmEnvelope(sampleRate)

    /**
     * 保护轮次判断与随之进行的操作（往播放器里写、[written]、[playing]、起播、清零）。打断与释放也要拿它，
     * 所以锁内只做立即返回的操作：写入用非阻塞方式，缓冲满时的等待、等播完的等待都在锁外。
     * 「核对轮次」与「写进播放器」在同一次持锁内完成，打断插不进两者之间：清空之后不会再有上一轮的音频写进来。
     */
    private val lock = Any()

    @Volatile
    override var round = 0
        private set

    /** 播放位置；播放器已释放时取不到，按「已播完」算 */
    private fun head(): Long = runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(Long.MAX_VALUE)

    override fun level(): Float =
        if (playing) envelope.levelAt(runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(-1L)) else 0f

    override fun write(data: ByteArray, offset: Int, length: Int, round: Int) {
        var done = 0
        while (done < length) {
            val n = synchronized(lock) {
                if (round != this.round) return
                val left = length - done
                // 非阻塞：缓冲里放得下多少写多少，立即返回
                val n = track.write(data, offset + done, left, AudioTrack.WRITE_NON_BLOCKING)
                if (n > 0) {
                    envelope.write(data, offset + done, n)
                    written += n / 2
                }
                // 攒够起播缓冲就开播；还没开播缓冲就放不下了也开播，否则永远腾不出空间
                if (!playing && n >= 0 && (written >= startFrames || n < left)) start()
                n
            }
            if (n < 0) return
            done += n
            // 缓冲满：在锁外等播放腾出空间，每次回来重新核对轮次，被打断即退出
            if (done < length) Thread.sleep(FULL_WAIT_MS)
        }
    }

    /** 持锁调用 */
    private fun start() {
        playing = true
        track.play()
    }

    override fun drain(round: Int) {
        val target = synchronized(lock) {
            if (round != this.round) return
            // 不足起播缓冲的短句：直接开播
            if (!playing && written > 0) start()
            written
        }
        // 留出余量：按剩余时长再加 1 秒等待播放头追上
        val deadline = SystemClock.elapsedRealtime() + (target - head()).coerceAtLeast(0) * 1000 / sampleRate + 1000
        while (round == this.round && head() < target && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(20)
        }
        // 这一轮读完：停下并清零，下一轮重新攒起播缓冲。等待期间被打断则播放器可能已在播下一轮，不能再清
        synchronized(lock) {
            if (round == this.round) rewind()
        }
    }

    override fun flush() = synchronized(lock) {
        round++
        rewind()
    }

    /** 停下并清零（持锁调用）。不换轮次：一轮之内读完一段、稍后又接着读，仍是同一轮 */
    private fun rewind() {
        runCatching {
            track.pause()
            track.flush()
            track.stop()
        }
        playing = false
        written = 0
        envelope.reset()
    }

    override fun release() = synchronized(lock) {
        // 换轮次：还在写入或等播完的线程就此退出，不再访问已释放的播放器
        round++
        runCatching { track.stop() }
        track.release()
    }

    private companion object {
        /** 缓冲满时多久再试一次。缓冲至少 1 秒，这段时间只播掉其中约 2% */
        const val FULL_WAIT_MS = 20L
    }
}

/** 仅 debug 构建：把写入播放器的 PCM 同时追加到 [file]，用于检查实际播放的音频是否完好 */
class TeePcmPlayer(private val inner: PcmPlayer, file: java.io.File) : PcmPlayer {
    private val out = java.io.FileOutputStream(file, true)

    override val round: Int get() = inner.round

    override fun write(data: ByteArray, offset: Int, length: Int, round: Int) {
        // 被打断后才到的音频不会播出，也就不记
        if (round == inner.round) runCatching { out.write(data, offset, length) }
        inner.write(data, offset, length, round)
    }

    override fun drain(round: Int) = inner.drain(round)

    override fun flush() = inner.flush()

    override fun level() = inner.level()

    override fun release() {
        runCatching { out.close() }
        inner.release()
    }
}
