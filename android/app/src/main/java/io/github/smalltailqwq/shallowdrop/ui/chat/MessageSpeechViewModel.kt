package io.github.smalltailqwq.shallowdrop.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechOutput
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechSegmenter
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.UiText
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 聊天页共用一个播放器，切换消息或离开页面时停止朗读。 */
class MessageSpeechViewModel(private val output: SpeechOutput) : ViewModel() {
    private val active = MutableStateFlow<String?>(null)
    val messageId = active.asStateFlow()
    private val errors = Channel<UiText>(Channel.BUFFERED)
    val events = errors.receiveAsFlow()
    private var job: Job? = null
    private var generation = 0
    private var watchedSession: String? = null
    private var wasGenerating = false
    private var automaticEnabled = false
    private var attemptedMessage: String? = null
    private var automatic: AutomaticPlayback? = null
    private var previousReply: Message.Assistant? = null

    private class AutomaticPlayback(val id: String) {
        val segmenter = SpeechSegmenter()
        val queue = Channel<String>(Channel.UNLIMITED)
    }

    /** 只跟随正在生成的最新回复，打开历史和切换版本不会自动播放。 */
    fun updateAutomatic(conversation: ConversationState?, enabled: Boolean, languageTag: String) {
        val session = conversation?.session?.id
        if (session != watchedSession) {
            stop()
            watchedSession = session
            attemptedMessage = null
            wasGenerating = false
            previousReply = null
        }
        if (enabled != automaticEnabled) {
            automaticEnabled = enabled
            attemptedMessage = null
            if (!enabled && automatic != null) stop()
        }
        val generating = conversation?.generating == true
        if (generating && !wasGenerating) {
            stop()
            attemptedMessage = null
        }
        wasGenerating = generating
        if (active.value != null && conversation?.turns?.none { it.message.id == active.value } != false) stop()
        val reply = conversation?.turns?.lastOrNull()?.message as? Message.Assistant
        val previous = previousReply
        previousReply = reply
        if (automatic != null && automatic?.id != reply?.id) stop()
        if (!enabled || reply == null) return
        if (reply.stopReason == StopReason.Refusal || reply.status == ReplyStatus.Stopped || reply.status == ReplyStatus.Error) {
            if (automatic != null) stop()
            return
        }
        val streaming = reply.status == ReplyStatus.Pending || reply.status == ReplyStatus.Streaming
        if (automatic == null) {
            if (!generating || !streaming || attemptedMessage == reply.id) return
            stop()
            attemptedMessage = reply.id
            val playback = AutomaticPlayback(reply.id)
            if (previous?.id == reply.id && previous.status != ReplyStatus.Pending && previous.status != ReplyStatus.Streaming) {
                // 续写只朗读追加内容，已有正文作为分句器的已消费前缀。
                playback.segmenter.push(replyText(previous))
                playback.segmenter.flush()
            }
            automatic = playback
            play(reply.id, languageTag, playback.queue)
        }
        val playback = automatic ?: return
        val text = replyText(reply)
        val segments = playback.segmenter.push(text) + if (!streaming) playback.segmenter.flush() else emptyList()
        segments.forEach { playback.queue.trySend(it) }
        if (!streaming) playback.queue.close()
    }

    private fun replyText(reply: Message.Assistant): String =
        reply.blocks.filterIsInstance<ReplyBlock.Text>().filterNot { it.isTemplate }.joinToString("") { it.text }

    fun toggle(id: String, text: String, languageTag: String) {
        val wasActive = active.value == id
        stop()
        if (wasActive) return
        val segmenter = SpeechSegmenter()
        val segments = segmenter.push(text) + segmenter.flush()
        if (segments.isEmpty()) {
            errors.trySend(UiText.Res(R.string.read_aloud_empty))
            return
        }
        val queue = Channel<String>(Channel.UNLIMITED)
        segments.forEach { queue.trySend(it) }
        queue.close()
        play(id, languageTag, queue)
    }

    private fun play(id: String, languageTag: String, queue: Channel<String>) {
        val ticket = generation
        active.value = id
        job = viewModelScope.launch {
            try {
                check(output.prepare(languageTag))
                currentCoroutineContext().ensureActive()
                for (segment in queue) output.enqueue(segment)
                output.awaitIdle()
                output.failure?.let { throw it }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                errors.trySend(UiText.Res(R.string.read_aloud_failed))
            } finally {
                if (generation == ticket) {
                    output.stop()
                    active.value = null
                    automatic = null
                }
            }
        }
    }

    fun stop() {
        generation++
        automatic?.queue?.close()
        automatic = null
        active.value = null
        job?.cancel()
        job = null
        output.stop()
    }

    override fun onCleared() {
        stop()
        output.release()
    }

    companion object {
        val Factory = uiViewModelFactory { deps, _ -> MessageSpeechViewModel(deps.createSpeechOutput()) }
    }
}
