package io.github.smalltailqwq.shallowdrop.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.smalltailqwq.shallowdrop.core.contract.MessageMatch
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyError
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.ui.chat.message.AssistantMessage
import io.github.smalltailqwq.shallowdrop.ui.sider.SessionGroup
import io.github.smalltailqwq.shallowdrop.ui.sider.SessionGroupKey
import io.github.smalltailqwq.shallowdrop.ui.sider.SessionSider
import io.github.smalltailqwq.shallowdrop.ui.sider.SearchContent
import io.github.smalltailqwq.shallowdrop.ui.sider.SearchResult
import io.github.smalltailqwq.shallowdrop.ui.sider.SearchSnippet
import io.github.smalltailqwq.shallowdrop.ui.sider.buildSearchResults
import io.github.smalltailqwq.shallowdrop.ui.sider.snippetAround
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 搜索页（标题 / 消息 / 文件名命中）与侧栏入口，以及回复出错时卡片内的重试入口（Robolectric 合成界面，非设备）。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class SiderSearchAndErrorCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun summary(id: String, title: String) = SessionSummary(id, title, 0, 0)

    private val groups = listOf(
        SessionGroup(SessionGroupKey.Today, listOf(summary("a", "周末阅读计划"), summary("b", "Kotlin 协程取消"))),
        SessionGroup(SessionGroupKey.Yesterday, listOf(summary("c", "东京五日游行程"), summary("d", ""))),
    )

    private fun match(session: String, message: String, text: String, at: Long = 0, user: Boolean = true, file: Boolean = false) =
        MessageMatch(session, message, at, user, text, file)

    private val sessions = groups.flatMap { it.sessions }

    @Test
    fun resultsMergeTitleMessageAndFileNameHits() {
        val long = "前文".repeat(30) + "协程取消" + "后文".repeat(60)
        // 命中由存储层按查询词筛好，这里按查询词分别给出
        val kotlinHits = listOf(
            match("a", "m1", "我想看 Kotlin 书单", 1),
            match("a", "m1", "kotlin-notes.pdf", 1, file = true),
            match("zzz", "m3", "已删除会话的 kotlin 消息"),
        )
        val results = buildSearchResults(sessions, kotlinHits, " kotlin ", "新对话")
        // b 仅标题命中，a 仅消息命中；已不在列表中的会话被丢弃；顺序沿用会话列表
        assertEquals(listOf("a", "b"), results.map { it.session.id })
        assertEquals(1, results[0].snippets.size) // 同一条消息只取一个片段，正文优先
        assertEquals(false, results[0].snippets[0].isFileName)
        assertEquals(emptyList<SearchSnippet>(), results[1].snippets)

        val deep = buildSearchResults(sessions, listOf(match("c", "m2", long, 2, user = false)), "协程取消", "新对话")
        assertEquals(listOf("b", "c"), deep.map { it.session.id })
        val snippet = deep[1].snippets.single()
        assertTrue(snippet.text.startsWith("…") && snippet.text.endsWith("…") && "协程取消" in snippet.text)
        assertEquals(false, snippet.fromUser)
        assertEquals(emptyList<SearchResult>(), buildSearchResults(sessions, kotlinHits, "  ", "新对话"))
        assertEquals(listOf("d"), buildSearchResults(sessions, emptyList(), "新对话", "新对话").map { it.session.id })
    }

    @Test
    fun fileNameOnlyHitKeepsFileFlag() {
        val results = buildSearchResults(sessions, listOf(match("c", "m", "报告.PDF", file = true)), "pdf", "新对话")
        assertEquals(listOf(SearchSnippet("报告.PDF", true, true)), results.single().snippets)
    }

    @Test
    fun snippetFlattensLineBreaks() {
        assertEquals("第一行 第二行 关键词", snippetAround("第一行\n第二行\n关键词", "关键词"))
    }

    private fun setSearch(results: List<SearchResult>?, highlight: String = "kotlin", onSelect: (String) -> Unit = {}) = compose.setContent {
        ShallowDropTheme(darkTheme = false) {
            var query by remember { mutableStateOf("") }
            Surface { SearchContent(query, { query = it }, results, highlight, "新对话", {}, onSelect) }
        }
    }

    @Test
    fun searchPageShowsHitsAndOpensSession() {
        var opened: String? = null
        setSearch(listOf(SearchResult(summary("b", "Kotlin 协程取消"), listOf(SearchSnippet("…读 kotlin 文档", true, false)))), onSelect = { opened = it })
        compose.onNodeWithText("Kotlin 协程取消").assertIsDisplayed()
        compose.onNodeWithText("…读 kotlin 文档").assertIsDisplayed()
        compose.onNodeWithText("Kotlin 协程取消").performClick()
        assertEquals("b", opened)
    }

    @Test
    fun searchPageShowsNoMatchOnlyForEmptyResults() {
        setSearch(emptyList())
        compose.onNodeWithText("没有匹配的对话").assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索对话").performTextInput("x")
        compose.onNodeWithContentDescription("清除").performClick()
    }

    @Test
    fun siderSearchEntryOpensSearchPage() {
        var opened = 0
        compose.setContent { ShallowDropTheme(darkTheme = false) {
            Surface { SessionSider(groups, "a", {}, { _, _ -> }, {}, {}, { opened++ }) }
        } }
        compose.onNodeWithText("东京五日游行程").assertIsDisplayed()
        compose.onNodeWithText("搜索对话").performClick()
        assertEquals(1, opened)
    }

    private fun setSider(list: List<SessionGroup>) = compose.setContent {
        ShallowDropTheme(darkTheme = false) {
            Surface { SessionSider(list, "a", {}, { _, _ -> }, {}, {}, {}) }
        }
    }

    @Test
    fun emptySiderSaysSo() {
        setSider(emptyList())
        compose.onNodeWithText("还没有对话").assertIsDisplayed()
    }

    private fun setReply(turn: TurnView, onRegenerate: () -> Unit = {}) = compose.setContent {
        ShallowDropTheme(darkTheme = false) {
            Surface { AssistantMessage(turn, true, false, false, onRegenerate, {}, {}, {}, {}) }
        }
    }

    @Test
    fun errorReplyOffersRetryInCardAndNoCopy() {
        var retried = 0
        val error = ReplyError(ReplyErrorKind.Http, "Insufficient Balance", status = 402, code = "insufficientBalance")
        setReply(TurnView(Message.Assistant("a", 0, status = ReplyStatus.Error, error = error), 0, 1, canRegenerate = true)) { retried++ }

        compose.onNodeWithText("账户余额不足，请充值后重试").assertIsDisplayed()
        compose.onNodeWithText("前往充值").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        assertEquals(1, retried)
        // 没有正文可复制；重试已在卡片里，操作栏不再重复
        compose.onAllNodesWithContentDescription("复制").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("重新生成").assertCountEquals(0)
    }

    @Test
    fun normalReplyKeepsCopyAndRegenerate() {
        var retried = 0
        val reply = Message.Assistant("a", 0, status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text("你好")))
        setReply(TurnView(reply, 0, 1, canRegenerate = true)) { retried++ }
        compose.onNodeWithContentDescription("复制").assertIsDisplayed()
        compose.onNodeWithContentDescription("重新生成").performClick()
        assertEquals(1, retried)
        compose.onAllNodesWithText("重试").assertCountEquals(0)
    }

    @Test fun contextLimitOffersNewConversationInsteadOfRetry() {
        var opened = false
        val reply = Message.Assistant("full", 0, status = ReplyStatus.Error,
            error = ReplyError(ReplyErrorKind.Http, "maximum context length exceeded", 400, "contextFull"))
        compose.setContent { ShallowDropTheme(darkTheme = false) {
            Surface { AssistantMessage(TurnView(reply, 0, 1, canRegenerate = true), true, false, false,
                {}, {}, {}, {}, {}, onNewChat = { opened = true }) }
        } }
        compose.onNodeWithText("当前对话已达到上下文上限，请新建对话。").assertIsDisplayed()
        compose.onAllNodesWithText("重试").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("重新生成").assertCountEquals(0)
        compose.onNodeWithText("开启新对话").performClick()
        assertTrue(opened)
    }

    @Test
    fun thinkingStartsCollapsedAndCanBeExpandedAndCollapsed() {
        val reply = Message.Assistant("a", 0, status = ReplyStatus.Done, blocks = listOf(
            ReplyBlock.Thinking("思考内容", elapsedSeconds = 2.0),
            ReplyBlock.Text("正文内容"),
        ))
        setReply(TurnView(reply, 0, 1))
        compose.onNodeWithText("正文内容").assertIsDisplayed()
        compose.onAllNodesWithText("思考内容").assertCountEquals(0)
        compose.onNodeWithText("已思考（用时 2 秒）").performClick()
        compose.onNodeWithText("思考内容").assertIsDisplayed()
        compose.onNodeWithText("已思考（用时 2 秒）").performClick()
        compose.onAllNodesWithText("思考内容").assertCountEquals(0)
    }

    @Test
    fun errorWithPartialAnswerKeepsCopyAndRegenerateIcon() {
        // 已有正文再出错：正文仍可复制，重新生成留在操作栏
        val error = ReplyError(ReplyErrorKind.Network, "", code = "network")
        val reply = Message.Assistant("a", 0, status = ReplyStatus.Error, blocks = listOf(ReplyBlock.Text("写到一半")), error = error)
        setReply(TurnView(reply, 0, 1, canRegenerate = true))
        compose.onNodeWithContentDescription("复制").assertIsDisplayed()
        compose.onNodeWithContentDescription("重新生成").assertIsDisplayed()
        compose.onAllNodesWithText("重试").assertCountEquals(0)
    }
}
