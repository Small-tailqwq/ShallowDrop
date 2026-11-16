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
import io.github.smalltailqwq.shallowdrop.ui.sider.filterSessionGroups
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 侧栏按标题搜索，以及回复出错时卡片内的重试入口（Robolectric 合成界面，非设备）。 */
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

    @Test
    fun filterKeepsGroupsAndIgnoresCase() {
        assertEquals(groups, filterSessionGroups(groups, "  ", "新对话"))
        val kotlin = filterSessionGroups(groups, " kotlin ", "新对话")
        assertEquals(listOf(SessionGroupKey.Today), kotlin.map { it.key })
        assertEquals(listOf("b"), kotlin.single().sessions.map { it.id })
        assertEquals(listOf("c"), filterSessionGroups(groups, "行程", "新对话").flatMap { it.sessions }.map { it.id })
        assertEquals(listOf("d"), filterSessionGroups(groups, "新对话", "新对话").flatMap { it.sessions }.map { it.id })
        assertEquals(listOf("d"), filterSessionGroups(groups, "new chat", "New chat").flatMap { it.sessions }.map { it.id })
        assertEquals(emptyList<SessionGroup>(), filterSessionGroups(groups, "不存在", "新对话"))
    }

    private fun setSider(list: List<SessionGroup>, onSelect: (String) -> Unit = {}) = compose.setContent {
        ShallowDropTheme(darkTheme = false) {
            Surface { SessionSider(list, "a", onSelect, { _, _ -> }, {}, {}) }
        }
    }

    @Test
    fun searchFiltersSessionsAndShowsNoMatch() {
        var opened: String? = null
        setSider(groups) { opened = it }
        compose.onNodeWithText("东京五日游行程").assertIsDisplayed()

        compose.onNodeWithContentDescription("搜索对话").performTextInput("kotlin")
        compose.onNodeWithText("Kotlin 协程取消").assertIsDisplayed()
        compose.onAllNodesWithText("东京五日游行程").assertCountEquals(0)
        compose.onAllNodesWithText("昨天").assertCountEquals(0)
        compose.onNodeWithText("Kotlin 协程取消").performClick()
        assertEquals("b", opened)

        compose.onNodeWithContentDescription("搜索对话").performTextInput("zzz")
        compose.onNodeWithText("没有匹配的对话").assertIsDisplayed()

        compose.onNodeWithContentDescription("清除").performClick()
        compose.onNodeWithText("东京五日游行程").assertIsDisplayed()
        compose.onAllNodesWithText("没有匹配的对话").assertCountEquals(0)
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
