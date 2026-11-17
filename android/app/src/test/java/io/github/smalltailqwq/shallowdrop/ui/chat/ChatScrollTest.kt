package io.github.smalltailqwq.shallowdrop.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.ui.sider.SessionListViewModel
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import io.github.smalltailqwq.shallowdrop.ui.sider.SearchTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class ChatScrollTest {
    @get:Rule val compose = createComposeRule()
    private class Repo(initial: ConversationState?) : ChatRepository {
        val conversation = MutableStateFlow(initial)
        val list = MutableSharedFlow<List<SessionSummary>>(replay = 1)
        override val sessions: Flow<List<SessionSummary>> = list


        override fun observeConversation(sessionId: String): Flow<ConversationState?> = conversation

        override suspend fun send(sessionId: String?, text: String, files: List<FileAttachment>, options: SendOptions)  = 
            sessionId ?: "new"

        override fun stop(sessionId: String) = Unit
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

    private class Settings : SettingsRepository {
        override val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings(hasApiKey = true))
        override suspend fun apiKey(): String? = "k"
        override suspend fun setApiKey(apiKey: String?) = Unit
        override suspend fun acceptRechargeNotice() = Unit
        override suspend fun setLocation(location: io.github.smalltailqwq.shallowdrop.core.model.LocationPreference) = Unit
    override suspend fun setLocale(locale: AppLocale) = Unit
        override suspend fun setTheme(theme: ThemePreference) = Unit
        override suspend fun setChatFontSize(size: io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize) = Unit
        override suspend fun setAutoReadAloud(enabled: Boolean) = Unit
        override suspend fun setReplySwipe(settings: io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings) = Unit
        override suspend fun setThinking(enabled: Boolean) = Unit
        override suspend fun setSearch(enabled: Boolean) = Unit
        override suspend fun setModel(model: ChatModel) = Unit
        override suspend fun setCompactionSettings(thresholds: io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds, enabled: Boolean) = Unit
    }


    private fun conversation(id: String = "preview", text: String = "结束", generating: Boolean = false)  = 
        ConversationState(SessionSummary(id, id, 0, 0), (0..19).flatMap { i -> listOf(
            TurnView(Message.User("${id}u$i", 0, content = "第 $i 个问题"), 0, 1),
            TurnView(Message.Assistant("${id}a$i", 0,
                status = if (generating && i == 19) ReplyStatus.Streaming else ReplyStatus.Done,
                blocks = listOf(ReplyBlock.Text(if (i == 19) text else "合成内容。".repeat(50)))), 0, 1)
        ) }, generating)

    private fun show(repo: Repo, onOpenSearch: () -> Unit = {}): ChatViewModel {
        repo.list.tryEmit(listOf(SessionSummary("preview", "preview", 0, 0), SessionSummary("other", "other", 0, 0)))
        val vm = ChatViewModel(repo, Settings(), SavedStateHandle(mapOf("sessionId" to "preview")))
        val sessions = SessionListViewModel(repo)
        compose.setContent { ShallowDropTheme { Surface { ChatScreen({}, vm, sessions, onOpenSearch = onOpenSearch) } } }
        compose.waitForIdle()
        return vm
    }
    private fun list() = compose.onAllNodes(hasScrollAction())[0]
    private fun assertBottom(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("回到底部").assertDoesNotExist()
    }
    @Test fun searchEntryOpensSearchPageFromDrawer() {
        var opened = 0
        show(Repo(conversation()), onOpenSearch = { opened++ })
        compose.onNodeWithContentDescription("打开边栏").performClick()
        compose.onNodeWithText("搜索对话").performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
        compose.onNodeWithText("搜索对话").assertIsDisplayed()
    }
    @Test fun firstLayoutOpensAtBottom() {
        show(Repo(conversation()))
        assertBottom("结束")
    }
    @Test fun searchLocatesEarlierMessageAndKeywordHighlightExpires() {
        val text = "定位前文。".repeat(100) + "SEARCH_HIT" + "后文。".repeat(40)
        val original = conversation()
        val turns = original.turns.map { turn ->
            if (turn.message.id != "previewa2") turn else turn.copy(message =
                (turn.message as Message.Assistant).copy(blocks = listOf(ReplyBlock.Text(text))))
        }
        val vm = show(Repo(original.copy(turns = turns)))
        compose.runOnIdle { vm.openSearchResult(SearchTarget("preview", "previewa2", "SEARCH_HIT")) }
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed()
        fun hasHighlight(): Boolean {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.text.spanStyles.any { it.item.background != Color.Unspecified }
        }
        assertTrue(hasHighlight())
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(hasHighlight())
        compose.mainClock.advanceTimeBy(1_500)
        assertFalse(hasHighlight())
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithContentDescription("回到底部").assertIsDisplayed()
    }
    @Test fun asynchronouslyLoadedConversationOpensAtBottom() {
        val repo = Repo(null)
        show(repo)
        compose.runOnIdle { repo.conversation.value = conversation() }
        assertBottom("结束")
    }
    @Test fun switchingConversationResetsManualScroll() {
        val repo = Repo(conversation())
        val vm = show(repo)
        list().performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription("回到底部").assertIsDisplayed()
        compose.runOnIdle {
            repo.conversation.value = conversation("other", "另一个会话末尾")
            vm.openSession("other")
        }
        assertBottom("另一个会话末尾")
    }
    @Test fun streamingFollowsUntilUserScrollsAndReturnButtonRestoresFollowing() {
        val repo = Repo(conversation(generating = true))
        show(repo)
        compose.runOnIdle { repo.conversation.value = conversation(text = "新增内容。\n\n".repeat(50) + "末尾一", generating = true) }
        assertBottom("末尾一")
        list().performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription("回到底部").assertIsDisplayed()
        compose.runOnIdle { repo.conversation.value = conversation(text = "新增内容。\n\n".repeat(70) + "末尾二", generating = true) }
        compose.onNodeWithContentDescription("回到底部").assertIsDisplayed()
        compose.onNodeWithText("末尾二", substring = true).assertIsNotDisplayed()
        compose.onNodeWithContentDescription("回到底部").performClick()
        assertBottom("末尾二")
        compose.runOnIdle { repo.conversation.value = conversation(text = "新增内容。\n\n".repeat(80) + "末尾三", generating = true) }
        assertBottom("末尾三")
    }
}
