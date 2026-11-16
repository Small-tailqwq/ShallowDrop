package io.github.smalltailqwq.shallowdrop.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import io.github.smalltailqwq.shallowdrop.ui.sider.SessionListViewModel
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 顶栏的当前模型：可见、两步可换、切换后用于之后的发送；窄屏大字号下不被裁切、不挤掉两侧按钮。
 * Robolectric 合成界面（非设备），截图写入 build/ui-review-topbar。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class ChatTopTitleTest {
    @get:Rule
    val compose = createComposeRule()

    private class Repo(val conversation: ConversationState?) : ChatRepository {
        val list = MutableSharedFlow<List<SessionSummary>>(replay = 1)
        val sent = mutableListOf<SendOptions>()
        val regenerated = mutableListOf<SendOptions>()
        override val sessions: Flow<List<SessionSummary>> = list
        override fun observeConversation(sessionId: String): Flow<ConversationState?> = flowOf(conversation)
        override suspend fun send(sessionId: String?, text: String, files: List<FileAttachment>, options: SendOptions): String {
            sent += options
            return sessionId ?: "new"
        }
        override fun stop(sessionId: String) = Unit
        override suspend fun regenerate(sessionId: String, assistantMessageId: String, options: SendOptions) {
            regenerated += options
        }
        override suspend fun editAndResend(sessionId: String, userMessageId: String, text: String, options: SendOptions) = Unit
        override suspend fun continueReply(sessionId: String, assistantMessageId: String) = Unit
        override suspend fun switchBranch(sessionId: String, messageId: String, index: Int) = Unit
        override suspend fun addAttachment(uri: Uri): FileAttachment = error("unused")
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun delete(sessionId: String) = Unit
        override suspend fun deleteAll() = Unit
    }

    private class Settings : SettingsRepository {
        val state = MutableStateFlow(AppSettings(hasApiKey = true))
        override val settings: StateFlow<AppSettings> = state
        override suspend fun apiKey(): String? = "k"
        override suspend fun setApiKey(apiKey: String?) = Unit
        override suspend fun setLocale(locale: AppLocale) = Unit
        override suspend fun setTheme(theme: ThemePreference) = Unit
        override suspend fun setThinking(enabled: Boolean) = Unit
        override suspend fun setSearch(enabled: Boolean) = Unit
        override suspend fun setModel(model: ChatModel) = state.update { it.copy(model = model) }
    }

    private val longTitle = "帮我看看这段 Kotlin 协程代码为什么会在取消之后还继续写数据库"

    private fun conversation(title: String) = ConversationState(
        SessionSummary("s", title, 0, 0),
        listOf(
            TurnView(Message.User("u", 0, content = "你好"), 0, 1),
            TurnView(Message.Assistant("a", 0, status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text("你好，有什么可以帮你？"))), 0, 1, canRegenerate = true),
        ),
        generating = false,
    )

    private val settings = Settings()

    private fun show(title: String?, dark: Boolean = false): Pair<Repo, ChatViewModel> {
        val repo = Repo(title?.let(::conversation))
        repo.list.tryEmit(listOfNotNull(repo.conversation?.session))
        val handle = if (title == null) SavedStateHandle() else SavedStateHandle(mapOf("sessionId" to "s"))
        val vm = ChatViewModel(repo, settings, handle)
        val sessions = SessionListViewModel(repo)
        compose.setContent { ShallowDropTheme(darkTheme = dark) { Surface { ChatScreen({}, vm, sessions) } } }
        compose.waitForIdle()
        return repo to vm
    }

    private fun shot(name: String) {
        val out = File("build/ui-review-topbar").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 抽屉关闭时侧栏内容仍在语义树里（位于屏幕左侧之外），只取屏幕内的节点 */
    private fun onScreen(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val all = compose.onAllNodes(matcher)
        val index = all.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.left >= 0f && it.boundsInRoot.width > 0f }
        assertTrue("屏幕内没有匹配的节点", index >= 0)
        return all[index]
    }

    /** 顶栏三个控件都在屏幕内、互不重叠，标题区完整落在 64dp 顶栏里 */
    private fun assertTopBarFits(screenWidth: Int, barHeight: Int = 64) {
        val menu = compose.onNodeWithContentDescription("打开边栏").assertIsDisplayed().getUnclippedBoundsInRoot()
        val newChat = onScreen(hasContentDescription("开启新对话")).assertIsDisplayed().getUnclippedBoundsInRoot()
        val model = compose.onNodeWithContentDescription("模型: ${settings.state.value.model.id}").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("模型区与菜单重叠", model.left >= menu.right)
        assertTrue("模型区与新对话重叠", model.right <= newChat.left)
        assertTrue("新对话按钮超出屏幕", newChat.right <= screenWidth.dp)
        assertTrue("标题区超出顶栏高度: $model", model.top >= 0.dp && model.bottom <= barHeight.dp)
        assertTrue("点击区域过小: $model", model.bottom - model.top >= 48.dp)
    }

    @Test
    fun newChatShowsModelAndSwitchAppliesToNextSend() {
        val (repo, vm) = show(null)
        compose.onNodeWithText("deepseek-flash").assertIsDisplayed()
        assertTopBarFits(360)
        shot("new-light")

        compose.onNodeWithContentDescription("模型: deepseek-flash").performClick()
        compose.onNodeWithText("deepseek-v4-pro").assertIsDisplayed()
        compose.onNodeWithContentDescription("已选择").assertIsDisplayed()
        shot("menu-light")
        compose.onNodeWithText("deepseek-v4-pro").performClick()
        compose.waitForIdle()

        // 菜单收起，顶栏显示新模型，设置同步更新
        assertEquals(ChatModel.V4Pro, settings.state.value.model)
        compose.onNodeWithContentDescription("模型: deepseek-v4-pro").assertIsDisplayed()
        compose.onAllNodesWithText("deepseek-flash").assertCountEquals(0)

        vm.onTextChange("你好")
        compose.waitForIdle()
        vm.send()
        compose.waitForIdle()
        assertEquals(listOf(ChatModel.V4Pro), repo.sent.map { it.model })
    }

    @Test
    fun conversationShowsTitleAndModelAndRegenerateUsesSwitchedModel() {
        val (repo, _) = show("周末阅读计划", dark = true)
        onScreen(hasText("周末阅读计划")).assertIsDisplayed()
        compose.onNodeWithText("deepseek-flash").assertIsDisplayed()
        assertTopBarFits(360)
        shot("conversation-dark")

        compose.onNodeWithContentDescription("模型: deepseek-flash").performClick()
        compose.onNodeWithText("deepseek-v4-pro").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("重新生成").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf(ChatModel.V4Pro), repo.regenerated.map { it.model })
    }

    @Test
    fun choosingCurrentModelChangesNothing() {
        show("周末阅读计划")
        compose.onNodeWithContentDescription("模型: deepseek-flash").performClick()
        compose.onAllNodesWithText("deepseek-flash")[1].performClick()
        compose.waitForIdle()
        assertEquals(ChatModel.Flash, settings.state.value.model)
        compose.onAllNodesWithText("deepseek-v4-pro").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-xhdpi", fontScale = 1.5f)
    fun narrowLargeFontLongTitleStillFits() {
        settings.state.update { it.copy(model = ChatModel.V4Pro) }
        show(longTitle)
        assertTopBarFits(320, 96)
        compose.onNodeWithText("deepseek-v4-pro").assertIsDisplayed()
        shot("narrow-320-font1.5-light")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-xhdpi", fontScale = 1.5f)
    fun narrowLargeFontNewChatFits() {
        settings.state.update { it.copy(model = ChatModel.V4Pro) }
        show(null, dark = true)
        assertTopBarFits(320, 96)
        shot("narrow-320-font1.5-new-dark")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-xhdpi", fontScale = 2f)
    fun narrowDoubleFontLongTitleStillFits() {
        settings.state.update { it.copy(model = ChatModel.V4Pro) }
        show(longTitle)
        assertTopBarFits(320, 128)
        compose.onNodeWithContentDescription("模型: deepseek-v4-pro").performClick()
        compose.onNodeWithText("deepseek-flash").assertIsDisplayed()
        shot("narrow-320-font2-menu-light")
    }
}
