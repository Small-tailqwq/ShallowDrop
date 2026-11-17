package io.github.smalltailqwq.shallowdrop.ui.voice

import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.domain.chat.FakeSettings
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrError
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrEvent
import io.github.smalltailqwq.shallowdrop.domain.voice.AsrException
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechInput
import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechOutput
import io.github.smalltailqwq.shallowdrop.domain.voice.VoicePhase
import io.github.smalltailqwq.shallowdrop.domain.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 语音页与对话状态机之间的一层：挂断后不再重启，音量不进入界面状态。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 发送后立即给出一条已有完整首句、仍在生成的回复 */
    private class Chat : ChatRepository {
        val conversation = MutableStateFlow<ConversationState?>(null)

        override suspend fun send(sessionId: String?, text: String, files: List<FileAttachment>, options: SendOptions): String {
            val reply = Message.Assistant(id = "a1", createdAt = 0, status = ReplyStatus.Streaming, blocks = listOf(ReplyBlock.Text("你好呀。今天")))
            conversation.value = ConversationState(
                session = SessionSummary("s1", "", 0, 0),
                turns = listOf(TurnView(Message.User("u1", 0, content = text), 0, 1), TurnView(reply, 0, 1)),
                generating = true,
            )
            return "s1"
        }

        override fun observeConversation(sessionId: String): Flow<ConversationState?> = conversation
        override fun stop(sessionId: String) = Unit
        override val sessions: Flow<List<SessionSummary>> = flowOf(emptyList())
        override suspend fun regenerate(sessionId: String, assistantMessageId: String, options: SendOptions) = Unit
        override suspend fun editAndResend(sessionId: String, userMessageId: String, text: String, options: SendOptions) = Unit
        override suspend fun continueReply(sessionId: String, assistantMessageId: String) = Unit
        override suspend fun switchBranch(sessionId: String, messageId: String, index: Int) = Unit
        override suspend fun fork(sessionId: String, assistantMessageId: String): String? = error("unused")
        override suspend fun selectMessage(sessionId: String, messageId: String) = true
        override suspend fun addAttachment(uri: Uri): FileAttachment = error("unused")
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun delete(sessionId: String) = Unit
        override suspend fun deleteAll() = Unit
    }

    /** 聆听时把 [events] 里的事件依次发出，发出最终结果后这次聆听结束；插话监听一直挂起 */
    private class Input : SpeechInput {
        var listens = 0
        val events = Channel<AsrEvent>(Channel.UNLIMITED)

        override fun isAvailable() = true

        override fun listen(languageTag: String, bargeIn: Boolean): Flow<AsrEvent> = flow {
            if (bargeIn) awaitCancellation()
            listens++
            emit(AsrEvent.Ready)
            for (event in events) {
                emit(event)
                if (event is AsrEvent.Final) break
            }
        }
    }

    private class Output : SpeechOutput {
        override val failure: Throwable? = null
        override var level = 0f
        val spoken = mutableListOf<String>()

        override suspend fun prepare(languageTag: String) = true
        override fun enqueue(text: String) {
            spoken += text
        }

        override suspend fun awaitIdle() = awaitCancellation()
        override fun stop() = Unit
        override fun release() = Unit
    }

    private val input = Input()
    private val output = Output()

    private fun viewModel() = VoiceViewModel(Chat(), FakeSettings(), input, output, sessionId = "s1")

    @Test
    fun hangUpIsFinal() {
        val viewModel = viewModel()
        viewModel.onPermissionResult(true)
        assertEquals(VoicePhase.Listening, viewModel.state.value.phase)
        assertEquals(1, input.listens)

        assertEquals("s1", viewModel.hangUp())
        assertEquals(VoicePhase.Stopped, viewModel.state.value.phase)

        // 退出过渡期间页面还在：点光球（停止状态下本是「重试」）、迟到的权限回调都不能把对话重新拉起来
        viewModel.interrupt()
        viewModel.onPermissionResult(true)
        assertEquals(VoicePhase.Stopped, viewModel.state.value.phase)
        assertEquals(1, input.listens)
    }

    @Test
    fun retryStillWorksBeforeHangUp() {
        val viewModel = viewModel()
        viewModel.onPermissionResult(true)
        // 麦克风出错：停下并给出问题
        input.events.close(AsrException(AsrError.Network))
        assertEquals(VoicePhase.Stopped, viewModel.state.value.phase)

        viewModel.interrupt()
        assertEquals(2, input.listens)
    }

    @Test
    fun inputLevelDrivesOrbWithoutTouchingUiState() {
        val viewModel = viewModel()
        val seen = mutableListOf<VoiceState>()
        val scope = CoroutineScope(dispatcher)
        scope.launch { viewModel.state.collect { seen += it } }
        viewModel.onPermissionResult(true)
        val before = seen.size

        listOf(0.2f, 0.8f, 0.5f).forEach { input.events.trySend(AsrEvent.Level(it)) }

        assertEquals("光球读到的是麦克风音量", 0.5f, viewModel.orbLevel(), 0f)
        assertEquals("音量变化不应让界面状态重新发射", before, seen.size)
        assertEquals(0f, viewModel.state.value.level, 0f)
        scope.cancel()
    }

    @Test
    fun orbFollowsSpeechLevelWhileSpeaking() {
        val viewModel = viewModel()
        viewModel.onPermissionResult(true)
        input.events.trySend(AsrEvent.Level(0.9f))
        input.events.trySend(AsrEvent.Final("你好"))
        assertEquals(VoicePhase.Speaking, viewModel.state.value.phase)
        assertEquals(listOf("你好呀。"), output.spoken)

        assertEquals("说话时不再用麦克风音量", 0f, viewModel.orbLevel(), 0f)
        output.level = 0.6f
        assertEquals(0.6f, viewModel.orbLevel(), 0f)
    }
}
