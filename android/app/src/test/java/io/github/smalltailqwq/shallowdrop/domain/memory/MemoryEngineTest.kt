package io.github.smalltailqwq.shallowdrop.domain.memory

import io.github.smalltailqwq.shallowdrop.core.contract.MemoryStore
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.CreateMessageRequest
import io.github.smalltailqwq.shallowdrop.core.model.MemoryChangeType
import io.github.smalltailqwq.shallowdrop.core.model.MemoryEntry
import io.github.smalltailqwq.shallowdrop.core.model.MemoryFullException
import io.github.smalltailqwq.shallowdrop.core.model.MemoryOrigin
import io.github.smalltailqwq.shallowdrop.core.model.MemoryState
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import io.github.smalltailqwq.shallowdrop.domain.chat.ChatEngine
import io.github.smalltailqwq.shallowdrop.domain.chat.FakeAttachmentStore
import io.github.smalltailqwq.shallowdrop.domain.chat.FakeChatApi
import io.github.smalltailqwq.shallowdrop.domain.chat.FakeSessionStore
import io.github.smalltailqwq.shallowdrop.domain.chat.FakeSettings
import io.github.smalltailqwq.shallowdrop.domain.chat.textReply
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeMemoryStore(var saved: MemoryState = MemoryState()) : MemoryStore {
        var saves = 0

        override suspend fun load() = saved

        override suspend fun save(state: MemoryState) {
            saves++
            saved = state
        }
    }

    private val api = FakeChatApi()
    private val sessions = FakeSessionStore()
    private val settings = FakeSettings(AppSettings(hasApiKey = true, locale = AppLocale.ZhCN, memoryEnabled = true))
    private val store = FakeMemoryStore()
    private var ids = 0

    private fun engineTest(block: suspend TestScope.(MemoryEngine, CoroutineScope) -> Unit) = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        val engine = MemoryEngine(api, sessions, store, settings, scope, clock = { testScheduler.currentTime },
            idGenerator = { "id${ids++}" }, zone = { ZoneOffset.UTC }, idleMs = 1000)
        try {
            block(engine, scope)
        } finally {
            scope.cancel()
        }
    }

    /** 一条已完成的线性对话：提问与回答交替。 */
    private suspend fun conversation(id: String, vararg texts: String, title: String = "标题") {
        val messages = texts.mapIndexed { index, text ->
            val parent = if (index == 0) null else "$id-${index - 1}"
            val child = if (index == texts.lastIndex) null else "$id-${index + 1}"
            if (index % 2 == 0) {
                Message.User("$id-$index", index.toLong(), parent, child, content = text)
            } else {
                Message.Assistant("$id-$index", index.toLong(), parent, child, status = ReplyStatus.Done,
                    stopReason = StopReason.EndTurn, blocks = listOf(ReplyBlock.Text(text)))
            }
        }
        sessions.save(Session(id, title, 0, 0, messages, "$id-0"))
    }

    private fun CreateMessageRequest.userText() =
        (messages.last() as MessageParam.User).content.filterIsInstance<UserContentParam.Text>().joinToString("") { it.text }

    private fun reply(json: String) = { _: CreateMessageRequest -> textReply(json) }

    @Test
    fun extractsAfterIdleAndKeepsTheQuote() = engineTest { engine, _ ->
        conversation("s", "我叫林澈，住在苏州", "你好林澈")
        api.scripts += reply("""{"ops":[{"op":"add","text":"用户叫林澈。","from":[1]},{"op":"add","text":"用户住在苏州。","from":[1]}]}""")
        engine.onReplyFinished("s")
        advanceTimeBy(999)
        runCurrent()
        assertTrue(api.requests.isEmpty())
        assertEquals(setOf("s"), store.saved.pending)
        advanceUntilIdle()

        val request = api.requests.single()
        assertEquals(ChatModel.Flash, request.model)
        assertEquals(MEMORY_EXTRACT_PROMPT, request.system)
        assertFalse(request.thinking)
        assertTrue(request.tools.isEmpty())
        assertEquals(MEMORY_MAX_OUTPUT_TOKENS, request.maxTokens)
        assertTrue("[用户 1] 我叫林澈，住在苏州\n[助手] 你好林澈" in request.userText())

        val state = engine.memory.value
        assertEquals(listOf("用户叫林澈。", "用户住在苏州。"), state.entries.map { it.text })
        val quote = state.entries.first().quotes.single()
        assertEquals(listOf("我叫林澈，住在苏州", "s", "s-0", "标题"), listOf(quote.text, quote.sessionId, quote.messageId, quote.sessionTitle))
        val record = state.records.single()
        assertEquals(MemoryOrigin.Conversation, record.origin)
        assertEquals("s", record.sessionId)
        assertEquals(1, record.quotes.size)
        assertEquals(2, record.changes.size)
        assertTrue(state.pending.isEmpty())
        assertEquals("s-1", state.cursors["s"])
        assertEquals(state, store.saved)
        assertFalse(engine.updating.value)
        assertTrue(engine.systemBlock()!!.contains("- (1970-01-01) 用户叫林澈。"))
    }

    @Test
    fun onlyNewTurnsAreSentNextTime() = engineTest { engine, _ ->
        conversation("s", "第一句", "回答一")
        engine.onReplyFinished("s")
        advanceUntilIdle()
        conversation("s", "第一句", "回答一", "第二句", "回答二")
        engine.onReplyFinished("s")
        advanceUntilIdle()
        assertEquals(2, api.requests.size)
        val text = api.requests.last().userText()
        assertTrue("[用户 1] 第二句" in text)
        assertFalse("第一句" in text)
        assertEquals("s-3", engine.memory.value.cursors["s"])
    }

    @Test
    fun disabledMemoryDoesNothing() = engineTest { engine, _ ->
        settings.setMemoryEnabled(false)
        store.saved = MemoryState(pending = setOf("s"))
        conversation("s", "我叫林澈", "好")
        engine.start()
        engine.onReplyFinished("s")
        engine.settleOthers("other")
        advanceUntilIdle()
        assertTrue(api.requests.isEmpty())
        assertNull(engine.systemBlock())
    }

    @Test
    fun failedRequestKeepsPendingAndUnparseableOutputDropsIt() = engineTest { engine, _ ->
        conversation("s", "我叫林澈", "好")
        api.scripts += { flow { throw ApiException(ReplyErrorKind.Network, "offline") } }
        engine.onReplyFinished("s")
        advanceUntilIdle()
        assertEquals(setOf("s"), engine.memory.value.pending)
        assertNull(engine.memory.value.cursors["s"])

        api.scripts += reply("我不确定该怎么做")
        engine.settleOthers("other")
        assertEquals(2, api.requests.size)
        assertTrue(engine.memory.value.pending.isEmpty())
        assertEquals("s-1", engine.memory.value.cursors["s"])
        assertTrue(engine.memory.value.entries.isEmpty())
    }

    @Test
    fun settleOthersSkipsTheCurrentSessionAndStartRecoversPending() = engineTest { engine, _ ->
        conversation("a", "我住在杭州", "好")
        conversation("b", "我养了一只柯基", "好")
        store.saved = MemoryState(pending = setOf("a", "b"))
        api.scripts += reply("""{"ops":[{"op":"add","text":"用户住在杭州。"}]}""")
        engine.settleOthers("b")
        assertEquals(1, api.requests.size)
        assertEquals(setOf("b"), engine.memory.value.pending)
        // 只有一句用户消息时，没有 from 也记下这句原话
        assertEquals("a-0", engine.memory.value.entries.single().quotes.single().messageId)

        api.scripts += reply("""{"ops":[{"op":"add","text":"用户养了一只柯基。","from":[1]}]}""")
        engine.start()
        advanceUntilIdle()
        assertEquals(2, engine.memory.value.entries.size)
        assertTrue(engine.memory.value.pending.isEmpty())
    }

    @Test
    fun unfinishedReplyAndDeletedSession() = engineTest { engine, _ ->
        sessions.save(Session("s", "t", 0, 0, listOf(
            Message.User("u", 0, null, "r", content = "我叫林澈"),
            Message.Assistant("r", 1, "u", null, status = ReplyStatus.Streaming),
        ), "u"))
        store.saved = MemoryState(pending = setOf("s", "gone"), cursors = mapOf("gone" to "x"))
        engine.settleOthers("other")
        assertTrue(api.requests.isEmpty())
        assertEquals(MemoryState(), engine.memory.value)
    }

    @Test
    fun manualEditsAreRecorded() = engineTest { engine, _ ->
        engine.add("  用户喜欢喝茶。 ")
        engine.add("用户喜欢喝茶。")
        val id = engine.memory.value.entries.single().id
        engine.edit(id, "用户喜欢喝凤凰单丛。")
        engine.edit(id, "用户喜欢喝凤凰单丛。")
        engine.remove(id)
        val state = engine.memory.value
        assertTrue(state.entries.isEmpty())
        assertEquals(listOf(MemoryChangeType.Add, MemoryChangeType.Update, MemoryChangeType.Delete), state.records.map { it.changes.single().type })
        assertTrue(state.records.all { it.origin == MemoryOrigin.Manual })

        store.saved = store.saved.copy(cursors = mapOf("s" to "m"))
        engine.forgetSessions("s")
        engine.add("一条")
        engine.clear()
        assertEquals(MemoryState(), engine.memory.value)
    }

    private fun entry(id: String, text: String, until: String? = null) = MemoryEntry(id, text, 0, 0, until = until)

    @Test
    fun expiredArrangementsAreRemovedOnStart() = engineTest { engine, _ ->
        // 测试时钟从 1970-01-01 开始
        store.saved = MemoryState(entries = listOf(entry("a", "长期"), entry("b", "早已结束的行程", "1969-12-01"), entry("c", "刚结束的行程", "1969-12-25")))
        engine.start()
        advanceUntilIdle()
        assertEquals(listOf("a", "c"), engine.memory.value.entries.map { it.id })
        val record = engine.memory.value.records.single()
        assertEquals(MemoryOrigin.Tidy, record.origin)
        assertEquals(listOf(MemoryChangeType.Delete to "早已结束的行程"), record.changes.map { it.type to it.before })
        assertTrue(api.requests.isEmpty())

    }

    @Test
    fun mergesOnceWhenNearTheBudgetAndRetriesOnlyAfterGrowth() = engineTest { engine, _ ->
        val big = "甲乙丙丁戊己庚辛壬癸".map { entry("$it", "$it".repeat(280)) }
        store.saved = MemoryState(entries = big)
        assertTrue(memorySize(big) > MEMORY_TIDY_AT)
        conversation("s", "你好", "你好")
        api.scripts += reply("""{"ops":[]}""")
        api.scripts += reply("""{"ops":[{"op":"merge","ids":["m1","m2"],"text":"${"甲乙".repeat(140)}"}]}""")
        engine.onReplyFinished("s")
        advanceUntilIdle()

        assertEquals(2, api.requests.size)
        assertEquals(MEMORY_TIDY_PROMPT, api.requests.last().system)
        assertTrue("[m9] (1970-01-01) 壬" in api.requests.last().userText())
        val state = engine.memory.value
        assertEquals(9, state.entries.size)
        assertEquals(memorySize(big) - 280, memorySize(state.entries))
        assertEquals(memorySize(state.entries), state.tidiedAt)
        val record = state.records.single()
        assertEquals(MemoryOrigin.Tidy, record.origin)
        assertEquals(listOf(MemoryChangeType.Update, MemoryChangeType.Delete), record.changes.map { it.type })

        // 仍高于阈值但没有明显增长：不再请求合并
        conversation("t", "在吗", "在")
        api.scripts += reply("""{"ops":[]}""")
        engine.onReplyFinished("t")
        advanceUntilIdle()
        assertEquals(3, api.requests.size)

        // 降到阈值以下后重新计
        engine.remove("丙")
        conversation("u", "在吗", "在")
        api.scripts += reply("""{"ops":[]}""")
        engine.onReplyFinished("u")
        advanceUntilIdle()
        assertEquals(4, api.requests.size)
        assertEquals(0, engine.memory.value.tidiedAt)
    }

    @Test
    fun manualWritesStopAtTheBudget() = engineTest { engine, _ ->
        store.saved = MemoryState(entries = (1..20).map { entry("e$it", "$it".padEnd(MEMORY_BUDGET / 20, '满')) })
        assertTrue(runCatching { engine.add("再加一条") }.exceptionOrNull() is MemoryFullException)
        assertTrue(runCatching { engine.edit("e1", "长".repeat(MEMORY_BUDGET / 20 + 1)) }.exceptionOrNull() is MemoryFullException)
        engine.edit("e1", "短")
        engine.add("再加一条")
        assertEquals(21, engine.memory.value.entries.size)
    }

    @Test
    fun askAnswersFromMemoryAndAppliesRequestedChanges() = engineTest { engine, _ ->
        engine.add("用户住在苏州。")
        engine.add("用户对花生过敏。")
        api.scripts += reply("""{"answer":"你住在苏州。","ops":[]}""")
        val first = engine.ask(" 我住哪？ ", emptyList())
        assertEquals("我住哪？", first.question)
        assertEquals("你住在苏州。", first.answer)
        assertTrue(first.changes.isEmpty())
        assertTrue(api.requests.single().system.startsWith(MEMORY_ASK_PROMPT))
        assertTrue("[m1] (更新于 1970-01-01) 用户住在苏州。" in api.requests.single().system)

        api.scripts += reply("""{"answer":"已删除。","ops":[{"op":"delete","id":"m2"},{"op":"update","id":"m1","text":"用户住在杭州。"}]}""")
        val second = engine.ask("删掉过敏那条，我搬到杭州了", listOf(first))
        assertEquals(3, api.requests.last().messages.size)
        assertEquals(listOf(MemoryChangeType.Delete, MemoryChangeType.Update), second.changes.map { it.type })
        assertEquals(listOf("用户住在杭州。"), engine.memory.value.entries.map { it.text })
        val record = engine.memory.value.records.last()
        assertEquals(MemoryOrigin.Ask, record.origin)
        assertEquals("删掉过敏那条，我搬到杭州了", record.quotes.single().text)
        assertEquals("删掉过敏那条，我搬到杭州了", engine.memory.value.entries.single().quotes.single().text)
    }

    @Test
    fun chatRequestsCarryMemoryAndScheduleExtraction() = engineTest { memory, scope ->
        memory.add("用户住在杭州。")
        val finished = mutableListOf<String>()
        val removed = mutableListOf<String?>()
        var chatIds = 0
        val chat = ChatEngine(
            api, sessions, FakeAttachmentStore(tmp.newFolder()), settings, scope,
            resolveLocation = { _, _ -> "" },
            clock = { testScheduler.currentTime },
            idGenerator = { "c${chatIds++}" },
            zone = { ZoneOffset.UTC },
            memoryBlock = { memory.systemBlock() },
            onReplyFinished = { finished += it },
            onSessionsRemoved = { removed += it },
        )
        val id = chat.send(null, "周末去哪", emptyList(), SendOptions(thinking = false, search = false, model = ChatModel.Flash))
        advanceUntilIdle()
        assertEquals(ChatEngine.SYSTEM_PROMPT + "\n\n" + memory.systemBlock(), api.requests.single().system)
        assertEquals(listOf(id), finished)

        settings.setMemoryEnabled(false)
        chat.send(id, "再问一句", emptyList(), SendOptions(thinking = false, search = false, model = ChatModel.Flash))
        advanceUntilIdle()
        assertEquals(ChatEngine.SYSTEM_PROMPT, api.requests.last().system)

        chat.delete(id)
        chat.deleteAll()
        assertEquals(listOf(id, null), removed)
    }
}
