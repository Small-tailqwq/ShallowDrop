package io.github.smalltailqwq.shallowdrop.domain.voice

import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class VoicePhase { Preparing, Listening, Thinking, Speaking, Muted, Stopped }

enum class VoiceProblem {
    /** 设备没有语音识别服务 */
    NoRecognizer,

    /** 设备没有语音合成引擎 */
    NoSynthesizer,

    /** 没有录音权限 */
    Permission,

    /** 识别服务网络不可用 */
    Network,

    /** 这一轮回复失败（如 API 报错），继续聆听 */
    ReplyFailed,
}

data class VoiceState(
    val phase: VoicePhase = VoicePhase.Preparing,
    /** 用户这一句：识别中为中间结果，结束后为最终文本 */
    val heard: String = "",
    /** 这一轮已交给 TTS 的回复（朗读文本） */
    val reply: String = "",
    /** 输入音量 0..1 */
    val level: Float = 0f,
    val problem: VoiceProblem? = null,
    /** 语音对话写入的会话；首轮发送后才有 */
    val sessionId: String? = null,
)

/**
 * 一次语音对话：聆听（ASR）→ 发送（流式 LLM）→ 边生成边朗读（TTS）→ 再聆听，循环直到 [close]。
 *
 * - 每轮都是普通的会话消息，写入 [sessionId] 对应会话（为 null 时首轮新建），挂断后可在对话页继续查看。
 * - 发送时标记 voice，ChatEngine 据此在 system 追加语音模式说明（见 VoicePrompt.kt）。
 * - 延迟优化：回复按句切分（[SpeechSegmenter]），第一句生成出来就开始朗读，不等整段回答结束。
 * - [interrupt]：思考或朗读中点按即停止生成与朗读，立刻重新聆听。
 * - 识别服务不可用、缺权限、网络不可用时停在 [VoicePhase.Stopped] 并给出 [VoiceProblem]，[interrupt] 可重试。
 *
 * @param options 每轮请求的开关；voice 由本类设置
 */
class VoiceConversation(
    private val chat: ChatRepository,
    private val input: SpeechInput,
    private val output: SpeechOutput,
    private val scope: CoroutineScope,
    private val options: () -> SendOptions,
    private val languageTag: () -> String,
    sessionId: String?,
) {
    private val stateFlow = MutableStateFlow(VoiceState(sessionId = sessionId))
    val state: StateFlow<VoiceState> = stateFlow.asStateFlow()

    private val muted = MutableStateFlow(false)
    private var loop: Job? = null

    @Volatile
    private var turn: Job? = null

    /** 本轮发现的、需要用户处理才能继续的问题；置位后结束循环 */
    @Volatile
    private var fatal: VoiceProblem? = null

    fun start() {
        if (loop?.isActive == true) return
        fatal = null
        stateFlow.update { it.copy(phase = VoicePhase.Preparing, problem = null) }
        loop = scope.launch {
            if (!input.isAvailable()) return@launch stopWith(VoiceProblem.NoRecognizer)
            if (!output.prepare(languageTag())) return@launch stopWith(VoiceProblem.NoSynthesizer)
            while (isActive) {
                if (muted.value) {
                    stateFlow.update { it.copy(phase = VoicePhase.Muted, level = 0f) }
                    muted.first { !it }
                }
                val job = launch { runTurn() }
                turn = job
                job.join()
                fatal?.let { return@launch stopWith(it) }
            }
        }
    }

    /** 思考或朗读中：打断并重新聆听；已停止时：重试 */
    fun interrupt() {
        when (stateFlow.value.phase) {
            VoicePhase.Thinking, VoicePhase.Speaking -> {
                stateFlow.value.sessionId?.let(chat::stop)
                output.stop()
                turn?.cancel()
            }
            VoicePhase.Stopped -> start()
            else -> Unit
        }
    }

    /** 静音：聆听中立即停止识别；朗读中不打断，读完后不再聆听，直到取消静音 */
    fun setMuted(value: Boolean) {
        muted.value = value
        if (value && stateFlow.value.phase == VoicePhase.Listening) turn?.cancel()
    }

    /** 挂断：停止识别与朗读；已发出的回复继续生成，可在对话页查看 */
    fun close() {
        loop?.cancel()
        output.stop()
        stateFlow.update { it.copy(phase = VoicePhase.Stopped, level = 0f) }
    }

    fun release() {
        close()
        output.release()
    }

    private fun stopWith(problem: VoiceProblem) {
        stateFlow.update { it.copy(phase = VoicePhase.Stopped, problem = problem, level = 0f) }
    }

    private suspend fun runTurn() {
        val question = listenOnce() ?: return
        stateFlow.update { it.copy(phase = VoicePhase.Thinking, heard = question, reply = "", level = 0f, problem = null) }
        val sessionId = try {
            stateFlow.value.sessionId?.let { awaitIdleSession(it) }
            chat.send(stateFlow.value.sessionId, question, emptyList(), options().copy(voice = true))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            stateFlow.update { it.copy(problem = VoiceProblem.ReplyFailed) }
            delay(RETRY_DELAY_MS)
            return
        }
        stateFlow.update { it.copy(sessionId = sessionId) }
        speakReply(sessionId)
        output.awaitIdle()
    }

    /** 听一句话；没听到内容或可恢复的错误返回 null，由循环重新聆听 */
    private suspend fun listenOnce(): String? {
        stateFlow.update { it.copy(phase = VoicePhase.Listening, heard = "", level = 0f) }
        var result: String? = null
        try {
            input.listen(languageTag()).collect { event ->
                when (event) {
                    AsrEvent.Ready -> Unit
                    is AsrEvent.Level -> stateFlow.update { it.copy(level = event.value) }
                    is AsrEvent.Partial -> stateFlow.update { it.copy(heard = event.text, problem = null) }
                    is AsrEvent.Final -> result = event.text
                }
            }
        } catch (e: AsrException) {
            when (e.error) {
                AsrError.NoSpeech -> Unit
                AsrError.Busy, AsrError.Other -> delay(RETRY_DELAY_MS)
                AsrError.Permission -> fatal = VoiceProblem.Permission
                AsrError.Network -> fatal = VoiceProblem.Network
                AsrError.Unavailable -> fatal = VoiceProblem.NoRecognizer
            }
            return null
        }
        return result?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 打断后上一轮生成可能还在收尾，等它结束再发送（同一会话同时只能有一个生成） */
    private suspend fun awaitIdleSession(sessionId: String) {
        withTimeoutOrNull(IDLE_WAIT_MS) {
            chat.observeConversation(sessionId).first { it?.generating != true }
        }
    }

    /** 跟随流式回复，凑齐一句就交给 TTS；回复收尾后读完余下内容 */
    private suspend fun speakReply(sessionId: String) {
        val segmenter = SpeechSegmenter()
        val spoken = StringBuilder()
        chat.observeConversation(sessionId).first { conversation ->
            val reply = conversation?.turns?.lastOrNull()?.message as? Message.Assistant ?: return@first false
            val text = reply.blocks.filterIsInstance<ReplyBlock.Text>().filterNot { it.isTemplate }.joinToString("") { it.text }
            val settled = reply.status != ReplyStatus.Pending && reply.status != ReplyStatus.Streaming
            val segments = segmenter.push(text) + if (settled) segmenter.flush() else emptyList()
            for (segment in segments) {
                output.enqueue(segment)
                if (spoken.isNotEmpty()) spoken.append(' ')
                spoken.append(segment)
            }
            if (segments.isNotEmpty()) {
                stateFlow.update { it.copy(phase = VoicePhase.Speaking, reply = spoken.toString()) }
            }
            if (settled && reply.status == ReplyStatus.Error && spoken.isEmpty()) {
                stateFlow.update { it.copy(problem = VoiceProblem.ReplyFailed) }
            }
            settled
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 400L
        const val IDLE_WAIT_MS = 3_000L
    }
}
