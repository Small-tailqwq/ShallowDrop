package io.github.smalltailqwq.shallowdrop.domain.voice

import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音对话状态机：聆听 → 发送 → 边生成边朗读 → 再聆听，以及打断、静音和不可用。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceConversationTest {

    private class FakeChat : ChatRepository {
        val sent = mutableListOf<Pair<String, SendOptions>>()
        val stopped = mutableListOf<String>()
        val conversation = MutableStateFlow<ConversationState?>(null)

        fun emit(text: String, status: ReplyStatus) {
            val reply = Message.Assistant(
                id = "a${sent.size}",
                createdAt = 0,
                status = status,
                blocks = if (text.isEmpty()) emptyList() else listOf(ReplyBlock.Text(text)),
            )
            conversation.value = ConversationState(
                session = SessionSummary("s1", "", 0, 0),
                turns = listOf(TurnView(Message.User("u${sent.size}", 0, content = sent.last().first), 0, 1), TurnView(reply, 0, 1)),
                generating = status == ReplyStatus.Pending || status == ReplyStatus.Streaming,
            )
        }

        override suspend fun send(sessionId: String?, text: String, files: List<FileAttachment>, options: SendOptions): String {
            sent += text to options
            emit("", ReplyStatus.Pending)
            return "s1"
        }

        override fun observeConversation(sessionId: String): Flow<ConversationState?> = conversation

        override fun stop(sessionId: String) {
            stopped += sessionId
            conversation.value?.let { state ->
                val reply = state.turns.last().message as Message.Assistant
                emit((reply.blocks.firstOrNull() as? ReplyBlock.Text)?.text.orEmpty(), ReplyStatus.Stopped)
            }
        }

        override val sessions: Flow<List<SessionSummary>> = flowOf(emptyList())
        override suspend fun regenerate(sessionId: String, assistantMessageId: String, options: SendOptions) = Unit
        override suspend fun editAndResend(sessionId: String, userMessageId: String, text: String, options: SendOptions) = Unit
        override suspend fun continueReply(sessionId: String, assistantMessageId: String) = Unit
        override suspend fun switchBranch(sessionId: String, messageId: String, index: Int) = Unit
        override suspend fun addAttachment(uri: Uri): FileAttachment = error("unused")
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun delete(sessionId: String) = Unit
        override suspend fun deleteAll() = Unit
    }

    /** 每次 listen 取下一段脚本；脚本用完后一直聆听（挂起） */
    private class FakeInput : SpeechInput {
        var available = true
        val scripts = ArrayDeque<suspend kotlinx.coroutines.flow.FlowCollector<AsrEvent>.() -> Unit>()
        var listens = 0

        override fun isAvailable() = available

        override fun listen(languageTag: String): Flow<AsrEvent> = flow {
            listens++
            val script = scripts.removeFirstOrNull() ?: awaitCancellation()
            script()
        }
    }

    private class FakeOutput : SpeechOutput {
        var available = true
        val spoken = mutableListOf<String>()
        val idle = MutableStateFlow(true)
        var stops = 0

        override suspend fun prepare(languageTag: String) = available
        override fun enqueue(text: String) {
            spoken += text
        }

        override suspend fun awaitIdle() {
            idle.first { it }
        }

        override fun stop() {
            stops++
            idle.value = true
        }

        override fun release() = Unit
    }

    private val chat = FakeChat()
    private val input = FakeInput()
    private val output = FakeOutput()
    private val options = SendOptions(thinking = false, search = true, model = ChatModel.Flash)

    private fun voiceTest(block: suspend TestScope.(VoiceConversation) -> Unit) = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        val voice = VoiceConversation(chat, input, output, scope, { options }, { "zh-CN" }, sessionId = null)
        try {
            block(voice)
        } finally {
            scope.cancel()
        }
    }

    private fun say(vararg partials: String, final: String) {
        input.scripts += {
            emit(AsrEvent.Ready)
            partials.forEach { emit(AsrEvent.Partial(it)) }
            emit(AsrEvent.Final(final))
        }
    }

    @Test
    fun turnSendsVoiceRequestAndSpeaksWhileStreaming() = voiceTest { voice ->
        say("你好", final = "你好呀")
        voice.start()
        advanceUntilIdle()

        assertEquals(listOf("你好呀"), chat.sent.map { it.first })
        assertTrue("语音轮次应标记 voice", chat.sent.single().second.voice)
        assertEquals(VoicePhase.Thinking, voice.state.value.phase)
        assertEquals("你好呀", voice.state.value.heard)

        // 第一句生成出来就开始朗读，不等整段回答
        chat.emit("你好！我是", ReplyStatus.Streaming)
        advanceUntilIdle()
        assertEquals(listOf("你好！"), output.spoken)
        assertEquals(VoicePhase.Speaking, voice.state.value.phase)

        chat.emit("你好！我是 **DeepSeek**。", ReplyStatus.Done)
        advanceUntilIdle()
        assertEquals(listOf("你好！", "我是 DeepSeek。"), output.spoken)
        assertEquals("你好！ 我是 DeepSeek。", voice.state.value.reply)
        // 读完后回到聆听，后续轮次写入同一会话
        assertEquals(VoicePhase.Listening, voice.state.value.phase)
        assertEquals("s1", voice.state.value.sessionId)
        assertEquals(2, input.listens)
    }

    @Test
    fun tapWhileSpeakingStopsReplyAndListensAgain() = voiceTest { voice ->
        say(final = "讲个长故事")
        output.idle.value = false
        voice.start()
        advanceUntilIdle()
        chat.emit("从前有座山。山里有座庙。", ReplyStatus.Streaming)
        advanceUntilIdle()
        assertEquals(VoicePhase.Speaking, voice.state.value.phase)

        voice.interrupt()
        advanceUntilIdle()
        assertEquals(listOf("s1"), chat.stopped)
        assertEquals(1, output.stops)
        assertEquals(VoicePhase.Listening, voice.state.value.phase)
    }

    @Test
    fun silenceKeepsListeningWithoutSending() = voiceTest { voice ->
        input.scripts += { throw AsrException(AsrError.NoSpeech) }
        input.scripts += { emit(AsrEvent.Final("   ")) }
        voice.start()
        advanceUntilIdle()
        assertTrue(chat.sent.isEmpty())
        assertEquals(3, input.listens)
        assertEquals(VoicePhase.Listening, voice.state.value.phase)
    }

    @Test
    fun missingRecognizerStopsWithProblem() = voiceTest { voice ->
        input.available = false
        voice.start()
        advanceUntilIdle()
        assertEquals(VoicePhase.Stopped, voice.state.value.phase)
        assertEquals(VoiceProblem.NoRecognizer, voice.state.value.problem)
        assertEquals(0, input.listens)
    }

    @Test
    fun missingSynthesizerStopsWithProblem() = voiceTest { voice ->
        output.available = false
        voice.start()
        advanceUntilIdle()
        assertEquals(VoiceProblem.NoSynthesizer, voice.state.value.problem)
    }

    @Test
    fun networkErrorStopsAndTapRetries() = voiceTest { voice ->
        input.scripts += { throw AsrException(AsrError.Network) }
        voice.start()
        advanceUntilIdle()
        assertEquals(VoicePhase.Stopped, voice.state.value.phase)
        assertEquals(VoiceProblem.Network, voice.state.value.problem)

        voice.interrupt()
        advanceUntilIdle()
        assertEquals(VoicePhase.Listening, voice.state.value.phase)
        assertEquals(null, voice.state.value.problem)
    }

    @Test
    fun muteStopsListeningUntilUnmuted() = voiceTest { voice ->
        voice.start()
        advanceUntilIdle()
        assertEquals(VoicePhase.Listening, voice.state.value.phase)

        voice.setMuted(true)
        advanceUntilIdle()
        assertEquals(VoicePhase.Muted, voice.state.value.phase)
        val listens = input.listens

        voice.setMuted(false)
        advanceUntilIdle()
        assertEquals(VoicePhase.Listening, voice.state.value.phase)
        assertEquals(listens + 1, input.listens)
    }

    @Test
    fun failedReplyShowsProblemAndKeepsListening() = voiceTest { voice ->
        say(final = "你好")
        voice.start()
        advanceUntilIdle()
        chat.emit("", ReplyStatus.Error)
        advanceUntilIdle()
        assertEquals(VoiceProblem.ReplyFailed, voice.state.value.problem)
        assertEquals(VoicePhase.Listening, voice.state.value.phase)
        assertTrue(output.spoken.isEmpty())
    }
}
