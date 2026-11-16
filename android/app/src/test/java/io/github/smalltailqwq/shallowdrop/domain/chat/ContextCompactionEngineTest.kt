package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContextCompactionEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val api = FakeChatApi()
    private val store = FakeSessionStore()
    private val settings = FakeSettings()
    private val attachments by lazy { FakeAttachmentStore(tmp.newFolder()) }
    private val options = SendOptions(thinking = false, search = false, model = ChatModel.Flash)
    private var ids = 0

    private fun engine(scope: CoroutineScope) = ChatEngine(api, store, attachments, settings, scope,
        resolveLocation = { _, _ -> "" }, clock = { 100L }, idGenerator = { "new${ids++}" })

    private fun engineTest(block: suspend TestScope.(ChatEngine, CoroutineScope) -> Unit) = runTest {
        store.save(Session("s", "长对话", 1, 1, compactionHistory(), "u0"))
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        try { block(engine(scope), scope) } finally { scope.cancel() }
    }

    private fun userTexts(index: Int): List<String> = api.requests.filter { it.maxTokens == null }[index].messages.filterIsInstance<MessageParam.User>()
        .flatMap { it.content.filterIsInstance<UserContentParam.Text>() }.map { it.text }

    @Test fun summaryIsAdoptedWithoutRemovingMessagesAndReloadsInNewEngine() = engineTest { engine, scope ->
        val gate = CompletableDeferred<Unit>()
        repeat(2) { api.scripts += { request ->
            if (request.maxTokens != null) flow { gate.await(); emitAll(textReply("背景事实摘要")) } else textReply("新答案")
        } }
        engine.send("s", "下一题", emptyList(), options)
        runCurrent()
        assertEquals(2, api.requests.size)
        val summaryRequest = api.requests.single { it.maxTokens != null }
        assertFalse(summaryRequest.thinking)
        assertFalse(summaryRequest.title)
        assertTrue(summaryRequest.tools.isEmpty())
        assertEquals(COMPACTION_MAX_OUTPUT_TOKENS, summaryRequest.maxTokens)
        assertFalse("s" in engine.generating.value)
        gate.complete(Unit)
        runCurrent()
        engine.send("s", "再问", emptyList(), options)
        runCurrent()
        assertTrue(userTexts(1).first().contains("背景事实摘要"))
        assertFalse(userTexts(1).contains("问题 0"))
        assertTrue(userTexts(1).containsAll(listOf("问题 1", "问题 2", "下一题", "再问")))
        val stored = store.load("s")!!
        assertEquals(10, stored.messages.size)
        val checkpoint = (stored.messages[1] as Message.Assistant).contextSummary!!
        assertEquals(checkpoint.sourceHash, (visibleMessages(stored).last() as Message.Assistant).contextSummaryId)
        val restored = engine(scope)
        restored.send("s", "恢复后继续", emptyList(), options)
        runCurrent()
        assertTrue(userTexts(2).first().contains("背景事实摘要"))
    }

    @Test fun truncatedSummaryDoesNotReplaceOriginalContext() = engineTest { engine, _ ->
        repeat(2) { api.scripts += { request ->
            if (request.maxTokens != null) flowOf(messageStart(), textStart(0, "未完成摘要"), messageDelta(StopReason.MaxTokens), MessageStreamEvent.MessageStop)
            else textReply("新答案")
        } }
        engine.send("s", "下一题", emptyList(), options)
        runCurrent()
        engine.send("s", "再问", emptyList(), options)
        runCurrent()
        assertTrue(userTexts(1).contains("问题 0"))
        assertTrue(store.load("s")!!.messages.filterIsInstance<Message.Assistant>().all { it.contextSummary == null })
    }

    @Test fun stoppingReplyKeepsBackgroundSummaryAlive() = engineTest { engine, _ ->
        val gate = CompletableDeferred<Unit>()
        repeat(2) { api.scripts += { request ->
            if (request.maxTokens != null) flow { gate.await(); emitAll(textReply("停止后的摘要")) }
            else flow { emit(messageStart()); awaitCancellation() }
        } }
        engine.send("s", "下一题", emptyList(), options)
        runCurrent()
        engine.stop("s")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        engine.send("s", "继续", emptyList(), options)
        runCurrent()
        assertTrue(userTexts(1).first().contains("停止后的摘要"))
    }

    @Test fun deletingSessionCancelsBackgroundRequest() = engineTest { engine, _ ->
        var cancelled = false
        repeat(2) { api.scripts += { request ->
            if (request.maxTokens != null) flow { try { awaitCancellation() } finally { cancelled = true } } else textReply("新答案")
        } }
        engine.send("s", "下一题", emptyList(), options)
        runCurrent()
        engine.delete("s")
        runCurrent()
        assertTrue(cancelled)
        assertNull(store.load("s"))
    }

    @Test fun fileRetryKeepsAdoptedSummary() = engineTest { engine, _ ->
        repeat(2) { api.scripts += { request -> textReply(if (request.maxTokens != null) "背景事实摘要" else "新答案") } }
        engine.send("s", "下一题", emptyList(), options)
        runCurrent()
        attachments.putLocal("f1")
        api.scripts += { flow { throw ApiException(ReplyErrorKind.Http, "file_ids do not exist or are not created under your account: file-api-old", 400) } }
        val file = FileAttachment("f1", "file-api-old", "a.png", 3, true)
        engine.send("s", "看图", listOf(file), options)
        advanceUntilIdle()
        assertTrue(userTexts(1).first().contains("背景事实摘要"))
        assertTrue(userTexts(2).first().contains("背景事实摘要"))
        assertFalse(userTexts(2).contains("问题 0"))
        assertEquals(1, api.uploads.size)
    }

    @Test fun savedThresholdsControlForegroundWaiting() = engineTest { engine, _ ->
        settings.setCompactionSettings(CompactionThresholds(20, 50))
        repeat(2) { api.scripts += { request -> textReply(if (request.maxTokens != null) "自定义阈值摘要" else "新答案") } }
        engine.send("s", "下一题", emptyList(), options)
        runCurrent()
        assertTrue(userTexts(0).first().contains("自定义阈值摘要"))
    }

    @Test fun disabledCompactionKeepsFullHistoryAndDoesNotStartBackgroundWork() = engineTest { engine, _ ->
        settings.setCompactionSettings(CompactionThresholds(20, 50), enabled = false)
        api.scripts += { textReply("未压缩回答") }
        engine.send("s", "下一题", emptyList(), options)
        runCurrent()
        assertEquals(1, api.requests.size)
        assertNull(api.requests.single().maxTokens)
        assertTrue(userTexts(0).contains("问题 0"))
        assertTrue(store.load("s")!!.messages.filterIsInstance<Message.Assistant>().all { it.contextSummary == null })
    }

    @Test fun disabledCompactionIgnoresSavedSummaryAndReenablingReusesIt() = engineTest { engine, _ ->
        val thresholds = CompactionThresholds(20, 50)
        settings.setCompactionSettings(thresholds)
        repeat(2) { api.scripts += { request -> textReply(if (request.maxTokens != null) "已保存摘要" else "第一轮回答") } }
        engine.send("s", "下一题", emptyList(), options)
        runCurrent()
        assertTrue(userTexts(0).first().contains("已保存摘要"))
        settings.setCompactionSettings(thresholds, enabled = false)
        api.scripts += { textReply("关闭后回答") }
        engine.send("s", "读取原文", emptyList(), options)
        runCurrent()
        assertTrue(userTexts(1).contains("问题 0"))
        assertFalse(userTexts(1).any { it.contains("<conversation_summary>") })
        assertNull((visibleMessages(store.load("s")!!).last() as Message.Assistant).contextSummaryId)
        settings.setCompactionSettings(thresholds, enabled = true)
        api.scripts += { textReply("重新开启后回答") }
        engine.send("s", "继续", emptyList(), options)
        runCurrent()
        assertTrue(userTexts(2).first().contains("已保存摘要"))
        assertEquals(1, api.requests.count { it.maxTokens != null })
    }

    @Test fun disablingDuringForegroundWaitCancelsSummaryAndContinuesWithOriginalHistory() = engineTest { engine, _ ->
        val thresholds = CompactionThresholds(20, 50)
        settings.setCompactionSettings(thresholds)
        var cancelled = false
        api.scripts += { flow { try { awaitCancellation() } finally { cancelled = true } } }
        api.scripts += { textReply("已改用原文") }
        engine.send("s", "下一题", emptyList(), options)
        runCurrent()
        assertEquals(1, api.requests.size)
        assertTrue("s" in engine.generating.value)
        settings.setCompactionSettings(thresholds, enabled = false)
        runCurrent()
        assertTrue(cancelled)
        assertFalse("s" in engine.generating.value)
        assertTrue(userTexts(0).contains("问题 0"))
        val reply = visibleMessages(store.load("s")!!).last() as Message.Assistant
        assertEquals(io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus.Done, reply.status)
        assertNull(reply.contextSummaryId)
        assertTrue(store.load("s")!!.messages.filterIsInstance<Message.Assistant>().all { it.contextSummary == null })
    }

    @Test fun disabledContextLimitLeavesHistoryAndAllowsNewConversation() = engineTest { engine, _ ->
        settings.setCompactionSettings(CompactionThresholds(45, 65), enabled = false)
        api.scripts += { flow { throw ApiException(ReplyErrorKind.Http, "maximum context length exceeded", 400, "contextFull") } }
        engine.send("s", "超限提问", emptyList(), options)
        runCurrent()
        assertEquals(1, api.requests.size)
        val old = store.load("s")!!
        assertEquals("contextFull", (visibleMessages(old).last() as Message.Assistant).error?.code)
        assertEquals("问题 0", (old.messages.first() as Message.User).content)
        api.scripts += { textReply("新对话回答") }
        val newId = engine.send(null, "新问题", emptyList(), options)
        runCurrent()
        assertNotEquals("s", newId)
        assertEquals(old, store.load("s"))
        assertFalse(userTexts(1).contains("问题 0"))
        assertTrue(api.requests.all { it.maxTokens == null })
    }
}
