package io.github.smalltailqwq.shallowdrop.ui.chat

import io.github.smalltailqwq.shallowdrop.domain.voice.SpeechOutput
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSpeechViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class Output : SpeechOutput {
        override var failure: Throwable? = null
        var idle = CompletableDeferred<Unit>()
        val texts = mutableListOf<String>()
        var available = true
        var preparations = 0
        var prepareGate: CompletableDeferred<Unit>? = null
        override suspend fun prepare(languageTag: String): Boolean {
            preparations++
            prepareGate?.await()
            return available
        }
        override fun enqueue(text: String) { texts += text }
        override suspend fun awaitIdle() { idle.await() }
        override fun stop() { idle.complete(Unit); idle = CompletableDeferred() }
        override fun release() = stop()
    }

    @Test fun switchAndRapidRestartDoNotLetCancelledPlaybackStopNewPlayback() = runTest(dispatcher) {
        val output = Output()
        val vm = MessageSpeechViewModel(output)
        vm.toggle("a", "第一句。", "zh-CN")
        runCurrent()
        vm.toggle("a", "第一句。", "zh-CN")
        vm.toggle("a", "第一句。", "zh-CN")
        runCurrent()
        assertEquals("a", vm.messageId.value)
        vm.toggle("b", "第二句。", "zh-CN")
        runCurrent()
        assertEquals("b", vm.messageId.value)
        output.idle.complete(Unit)
        runCurrent()
        assertNull(vm.messageId.value)
        assertEquals(listOf("第一句。", "第一句。", "第二句。"), output.texts)
    }

    @Test fun readsProseWithoutCodeAndReportsServiceFailure() = runTest(dispatcher) {
        val output = Output()
        val vm = MessageSpeechViewModel(output)
        vm.toggle("a", "# 你好\n```kotlin\nprintln(123)\n```\n正文。", "zh-CN")
        runCurrent()
        assertTrue(output.texts.joinToString().contains("正文"))
        assertFalse(output.texts.joinToString().contains("println"))
        output.failure = IllegalStateException("service unavailable")
        output.idle.complete(Unit)
        runCurrent()
        assertNull(vm.messageId.value)
        assertNotNull(vm.events.first())
    }

    private fun conversation(text: String, status: ReplyStatus = ReplyStatus.Streaming, id: String = "a", session: String = "s") =
        ConversationState(SessionSummary(session, "", 0, 0), listOf(TurnView(
            Message.Assistant(id, 0, status = status, blocks = listOf(ReplyBlock.Thinking("思考不会被朗读"), ReplyBlock.Text(text))), 0, 1,
        )), generating = status == ReplyStatus.Pending || status == ReplyStatus.Streaming)

    @Test fun automaticReadingStreamsSentencesAndFlushesOnlyOnce() = runTest(dispatcher) {
        val output = Output()
        val vm = MessageSpeechViewModel(output)
        vm.updateAutomatic(conversation("第一句。"), true, "zh-CN")
        runCurrent()
        assertEquals(listOf("第一句。"), output.texts)
        vm.updateAutomatic(conversation("第一句。第二句"), true, "zh-CN")
        runCurrent()
        assertEquals(1, output.texts.size)
        val done = conversation("第一句。第二句", ReplyStatus.Done)
        vm.updateAutomatic(done, true, "zh-CN")
        runCurrent()
        assertEquals(listOf("第一句。", "第二句"), output.texts)
        output.idle.complete(Unit)
        runCurrent()
        assertNull(vm.messageId.value)
        vm.updateAutomatic(done, true, "zh-CN")
        runCurrent()
        assertEquals(2, output.texts.size)
    }

    @Test fun historicalRepliesStaySilentAndSwitchingSessionsStopsPlayback() = runTest(dispatcher) {
        val output = Output()
        val vm = MessageSpeechViewModel(output)
        vm.updateAutomatic(conversation("历史回复", ReplyStatus.Done), true, "zh-CN")
        runCurrent()
        assertTrue(output.texts.isEmpty())
        vm.updateAutomatic(conversation("新回复。", id = "b"), true, "zh-CN")
        runCurrent()
        assertEquals("b", vm.messageId.value)
        vm.updateAutomatic(conversation("另一段历史", ReplyStatus.Done, session = "other"), true, "zh-CN")
        runCurrent()
        assertNull(vm.messageId.value)
        assertEquals(listOf("新回复。"), output.texts)
    }

    @Test fun disablingAutomaticReadingStopsItAndDoesNotRestartOnMoreTokens() = runTest(dispatcher) {
        val output = Output()
        val vm = MessageSpeechViewModel(output)
        vm.updateAutomatic(conversation("第一句。"), true, "zh-CN")
        runCurrent()
        vm.updateAutomatic(conversation("第一句。"), false, "zh-CN")
        vm.updateAutomatic(conversation("第一句。第二句。"), false, "zh-CN")
        runCurrent()
        assertNull(vm.messageId.value)
        assertEquals(listOf("第一句。"), output.texts)
    }

    @Test fun manualStopSuppressesAutomaticReplayUntilNextGeneration() = runTest(dispatcher) {
        val output = Output()
        val vm = MessageSpeechViewModel(output)
        vm.updateAutomatic(conversation("第一句。"), true, "zh-CN")
        runCurrent()
        vm.toggle("a", "第一句。", "zh-CN")
        vm.updateAutomatic(conversation("第一句。第二句。"), true, "zh-CN")
        runCurrent()
        assertNull(vm.messageId.value)
        assertEquals(listOf("第一句。"), output.texts)
        vm.updateAutomatic(conversation("第一句。第二句。", ReplyStatus.Done), true, "zh-CN")
        vm.updateAutomatic(conversation("下一轮。", id = "b"), true, "zh-CN")
        runCurrent()
        assertEquals(listOf("第一句。", "下一轮。"), output.texts)
        vm.stop()
    }

    @Test fun continuingReplyReadsOnlyNewText() = runTest(dispatcher) {
        val output = Output()
        val vm = MessageSpeechViewModel(output)
        vm.updateAutomatic(conversation("已有正文。", ReplyStatus.Done), true, "zh-CN")
        vm.updateAutomatic(conversation("已有正文。", ReplyStatus.Pending), true, "zh-CN")
        vm.updateAutomatic(conversation("已有正文。续写内容。"), true, "zh-CN")
        vm.updateAutomatic(conversation("已有正文。续写内容。", ReplyStatus.Done), true, "zh-CN")
        runCurrent()
        assertEquals(listOf("续写内容。"), output.texts)
        output.idle.complete(Unit)
        runCurrent()
    }

    @Test fun failedPreparationIsReportedOnceAndCancellationDiscardsQueuedSpeech() = runTest(dispatcher) {
        val output = Output().apply { available = false }
        val vm = MessageSpeechViewModel(output)
        vm.updateAutomatic(conversation("第一句。"), true, "zh-CN")
        runCurrent()
        vm.updateAutomatic(conversation("第一句。第二句。"), true, "zh-CN")
        runCurrent()
        assertEquals(1, output.preparations)
        assertNull(vm.messageId.value)
        assertNotNull(vm.events.first())

        output.available = true
        output.prepareGate = CompletableDeferred()
        vm.updateAutomatic(conversation("新句子。", id = "b"), true, "zh-CN")
        runCurrent()
        vm.stop()
        output.prepareGate!!.complete(Unit)
        runCurrent()
        assertTrue(output.texts.isEmpty())
    }
}
