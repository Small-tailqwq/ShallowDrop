package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import io.github.smalltailqwq.shallowdrop.core.model.ContextSummary
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageUsage
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun compactionHistory(rounds: Int = 3, tokens: Long = BACKGROUND_COMPACT_TOKENS): List<Message> = buildList {
    repeat(rounds) { index ->
        add(Message.User("u$index", index.toLong(), parentId = if (index == 0) null else "a${index - 1}", selectedChildId = "a$index", content = "问题 $index"))
        add(Message.Assistant("a$index", index.toLong(), parentId = "u$index", selectedChildId = if (index + 1 < rounds) "u${index + 1}" else null,
            model = ChatModel.Flash, status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text("答案 $index")), usage = MessageUsage(inputTokens = tokens)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AsyncContextCompactionTest {
    private val model = ChatModel.Flash
    private val question = Message.User("next", 10, content = "继续")

    @Test fun belowThresholdDoesNotRequestSummary() = runTest {
        var calls = 0
        val history = compactionHistory(tokens = BACKGROUND_COMPACT_TOKENS - 10)
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ -> calls++; "摘要" }, { _, _, _ -> true })
        assertEquals(history, manager.prepare("s", history, question, model).history)
        runCurrent()
        assertEquals(0, calls)
    }

    @Test fun changedThresholdsApplyWithoutRestartingCompactor() = runTest {
        var thresholds = CompactionThresholds(75, 80)
        var calls = 0
        val gate = CompletableDeferred<String>()
        val history = compactionHistory()
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ -> calls++; gate.await() }, { _, _, _ -> true }, { thresholds })
        manager.prepare("s", history, question, model)
        runCurrent()
        assertEquals(0, calls)
        thresholds = CompactionThresholds(20, 80)
        assertNull(manager.prepare("s", history, question, model).summary)
        runCurrent()
        assertEquals(1, calls)
        thresholds = CompactionThresholds(20, 60)
        val request = async { manager.prepare("s", history, question, model) }
        runCurrent()
        assertFalse(request.isCompleted)
        gate.complete("自定义阈值摘要")
        assertNotNull(request.await().summary)
    }

    @Test fun backgroundDoesNotBlockAndSameSnapshotStartsOnce() = runTest {
        val gate = CompletableDeferred<String>()
        var calls = 0
        val history = compactionHistory()
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ -> calls++; gate.await() }, { _, _, _ -> true })
        repeat(3) { assertNull(manager.prepare("s", history, question, model).summary) }
        runCurrent()
        assertEquals(1, calls)
        assertFalse(gate.isCompleted)
    }

    @Test fun readyCandidateKeepsRetainedRoundsAndNewSuffix() = runTest {
        val installed = mutableListOf<String>()
        var calls = 0
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ -> calls++; "历史摘要" }, { _, anchor, _ -> installed += anchor; true })
        manager.maybeStart("s", compactionHistory(), model)
        runCurrent()
        val extended = compactionHistory(4)
        manager.maybeStart("s", extended, model)
        val result = manager.prepare("s", extended, question, model)
        assertEquals("历史摘要", result.summary?.text)
        assertEquals(extended.drop(2), result.history)
        assertEquals(listOf("a0"), installed)
        assertEquals(1, calls)
        assertEquals(toRequestMessages(extended.drop(2), question), result.messages(question).drop(1))
    }

    @Test fun editedPrefixRejectsCandidateAndStartsNewSnapshot() = runTest {
        var calls = 0
        var installs = 0
        val original = compactionHistory()
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ -> calls++; "摘要" }, { _, _, _ -> installs++; true })
        manager.maybeStart("s", original, model)
        runCurrent()
        val edited = original.toMutableList().apply { this[0] = (this[0] as Message.User).copy(content = "已修改的问题") }
        assertNull(manager.prepare("s", edited, question, model).summary)
        assertEquals(0, installs)
        runCurrent()
        assertNotNull(manager.prepare("s", edited, question, model).summary)
        assertEquals(2, calls)
    }

    @Test fun modelSwitchCancelsPendingCandidate() = runTest {
        var cancelled = false
        val manager = AsyncContextCompaction(backgroundScope, { _, _, selected ->
            if (selected == model) try { awaitCancellation() } finally { cancelled = true } else "新模型摘要"
        }, { _, _, _ -> true })
        manager.maybeStart("s", compactionHistory(), model)
        runCurrent()
        assertNull(manager.prepare("s", compactionHistory(), question, ChatModel.V4Pro).summary)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(ChatModel.V4Pro, manager.prepare("s", compactionHistory(), question, ChatModel.V4Pro).summary?.model)
    }

    @Test fun changedBranchCancelsPendingSummaryBeforeWaiting() = runTest {
        var cancelled = false
        var calls = 0
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ ->
            calls++
            if (calls == 1) try { awaitCancellation() } finally { cancelled = true } else "新分支摘要"
        }, { _, _, _ -> true })
        manager.maybeStart("s", compactionHistory(), model)
        runCurrent()
        val edited = compactionHistory(tokens = FOREGROUND_COMPACT_TOKENS).toMutableList()
        edited[0] = (edited[0] as Message.User).copy(content = "新的分支内容")
        assertEquals("新分支摘要", manager.prepare("s", edited, question, model).summary?.text)
        assertTrue(cancelled)
    }

    @Test fun laterCompactionUsesExistingSummaryAndNewHistory() = runTest {
        var history = compactionHistory()
        var calls = 0
        val summarizedMemories = mutableListOf<String?>()
        val manager = AsyncContextCompaction(backgroundScope, { _, snapshot, selected ->
            summarizedMemories += compactionContext(snapshot, selected).summary?.text
            "摘要 ${++calls}"
        }, { _, anchor, summary ->
            history = history.map { if (it.id == anchor && it is Message.Assistant) it.copy(contextSummary = summary) else it }
            true
        })
        manager.maybeStart("s", history, model)
        runCurrent()
        val first = manager.prepare("s", history, question, model).summary!!
        history = history.map { if (it.id == "a2" && it is Message.Assistant) it.copy(contextSummaryId = first.sourceHash) else it }
        manager.maybeStart("s", history, model)
        runCurrent()
        assertEquals("摘要 2", manager.prepare("s", history, question, model).summary?.text)
        assertEquals(listOf(null, "摘要 1"), summarizedMemories)
    }

    @Test fun nearLimitWaitsForPendingSummary() = runTest {
        val gate = CompletableDeferred<String>()
        val history = compactionHistory(tokens = FOREGROUND_COMPACT_TOKENS)
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ -> gate.await() }, { _, _, _ -> true })
        val request = async { manager.prepare("s", history, question, model) }
        runCurrent()
        assertFalse(request.isCompleted)
        gate.complete("摘要")
        assertNotNull(request.await().summary)
    }

    @Test fun backgroundFailureKeepsOriginalContextAndSuppressesDuplicateAttempt() = runTest {
        var calls = 0
        val history = compactionHistory()
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ -> calls++; error("offline") }, { _, _, _ -> fail("must not install"); false })
        manager.prepare("s", history, question, model)
        runCurrent()
        assertEquals(history, manager.prepare("s", history, question, model).history)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun failureAtLimitCanBeRetriedOnNextSend() = runTest {
        var calls = 0
        val history = compactionHistory(tokens = FOREGROUND_COMPACT_TOKENS)
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ -> calls++; error("offline") }, { _, _, _ -> false })
        repeat(2) {
            try { manager.prepare("s", history, question, model); fail("must report failure") }
            catch (expected: IllegalStateException) { assertEquals("offline", expected.message) }
        }
        assertEquals(2, calls)
    }

    @Test fun cancellationDiscardsPendingWork() = runTest {
        var cancellations = 0
        val manager = AsyncContextCompaction(backgroundScope, { _, _, _ -> try { awaitCancellation() } finally { cancellations++ } }, { _, _, _ -> false })
        manager.maybeStart("s1", compactionHistory(), model)
        manager.maybeStart("s2", compactionHistory(), model)
        runCurrent()
        manager.cancel("s1")
        manager.clear()
        runCurrent()
        assertEquals(2, cancellations)
    }

    @Test fun persistedCheckpointValidatesPrefixAndResetsOldUsage() {
        val history = compactionHistory().toMutableList()
        val summary = ContextSummary(contextFingerprint(history.take(2)), model, "历史摘要")
        history[1] = (history[1] as Message.Assistant).copy(contextSummary = summary)
        assertEquals(summary, compactionContext(history, model).summary)
        assertTrue(activeContextTokens(history, model) < BACKGROUND_COMPACT_TOKENS)
        assertNull(compactionContext(history, ChatModel.V4Pro).summary)
        history[0] = (history[0] as Message.User).copy(content = "不同分支")
        assertNull(compactionContext(history, model).summary)
    }
}
