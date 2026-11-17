package io.github.smalltailqwq.shallowdrop.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 当前会话与会话列表的关系：新建会话不被旧的列表结果踢回新对话；
 * 真正被删除（侧栏删除、全部删除、导入替换）或进程重建后已不存在的会话回到新对话。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelSessionTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Repo : ChatRepository {
        var forkResult: CompletableDeferred<String?>? = null
        val list = MutableSharedFlow<List<SessionSummary>>(replay = 1)
        override val sessions: Flow<List<SessionSummary>> = list

        suspend fun emit(vararg ids: String) = list.emit(ids.map { SessionSummary(it, it, 0, 0) })

        override fun observeConversation(sessionId: String): Flow<ConversationState?> = flowOf(null)

        override suspend fun send(sessionId: String?, text: String, files: List<FileAttachment>, options: SendOptions) =
            sessionId ?: "new"

        override fun stop(sessionId: String) = Unit
        override suspend fun regenerate(sessionId: String, assistantMessageId: String, options: SendOptions) = Unit
        override suspend fun editAndResend(sessionId: String, userMessageId: String, text: String, options: SendOptions) = Unit
        override suspend fun continueReply(sessionId: String, assistantMessageId: String) = Unit
        override suspend fun switchBranch(sessionId: String, messageId: String, index: Int) = Unit
        override suspend fun fork(sessionId: String, assistantMessageId: String): String? = forkResult?.await() ?: "fork"
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

    private val repo = Repo()

    /** 创建 ViewModel 并保持 state 有订阅者（界面在前台） */
    private fun TestScope.viewModel(restoredSession: String? = null): ChatViewModel {
        val handle = if (restoredSession == null) SavedStateHandle() else SavedStateHandle(mapOf("sessionId" to restoredSession))
        val vm = ChatViewModel(repo, Settings(), handle)
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        return vm
    }

    private val ChatViewModel.current get() = state.value.sessionId

    @Test
    fun forkOpensNewSessionAndPreservesSourceDraft() = runTest(dispatcher) {
        repo.emit("source")
        val vm = viewModel("source")
        vm.onTextChange("原来的草稿")
        vm.fork("reply")
        assertEquals("fork", vm.current)
        assertEquals("", vm.state.value.draft.text)
        vm.openSession("source")
        assertEquals("原来的草稿", vm.state.value.draft.text)
    }

    @Test
    fun finishingForkDoesNotReplaceUserSelectedSession() = runTest(dispatcher) {
        repo.emit("source", "other")
        repo.forkResult = CompletableDeferred()
        val vm = viewModel("source")
        vm.fork("reply")
        vm.openSession("other")
        repo.forkResult!!.complete("fork")
        assertEquals("other", vm.current)
    }

    @Test
    fun newSessionSurvivesStaleListThenFollowsRealDeletion() = runTest(dispatcher) {
        repo.emit("a")
        val vm = viewModel()
        vm.onTextChange("你好")
        vm.send()
        assertEquals("new", vm.current)

        // 新建之前发出的查询结果晚到：列表里还没有新会话
        repo.emit("a")
        assertEquals("new", vm.current)
        repo.emit("a")
        assertEquals("new", vm.current)

        repo.emit("new", "a")
        assertEquals("new", vm.current)
        // 之后真的被删除
        repo.emit("a")
        assertNull(vm.current)
    }

    @Test
    fun deletingOpenSessionReturnsToNewChat() = runTest(dispatcher) {
        repo.emit("a", "b")
        val vm = viewModel()
        // 从侧栏打开，期间列表没有新的结果
        vm.openSession("a")
        assertEquals("a", vm.current)

        repo.emit("b")
        assertNull(vm.current)
    }

    @Test
    fun replacingAllSessionsReturnsToNewChat() = runTest(dispatcher) {
        repo.emit("a", "b")
        val vm = viewModel()
        vm.openSession("b")
        // 历史导入整体替换 / 全部删除
        repo.emit("x", "y")
        assertNull(vm.current)

        vm.openSession("x")
        repo.emit()
        assertNull(vm.current)
    }

    @Test
    fun restoredSessionThatNoLongerExistsReturnsToNewChat() = runTest(dispatcher) {
        val vm = viewModel(restoredSession = "gone")
        assertEquals("gone", vm.current)
        repo.emit("a")
        assertNull(vm.current)
    }

    @Test
    fun restoredExistingSessionStaysUntilDeleted() = runTest(dispatcher) {
        repo.emit("a", "b")
        val vm = viewModel(restoredSession = "a")
        assertEquals("a", vm.current)
        repo.emit("a", "b", "c")
        assertEquals("a", vm.current)
        repo.emit("b", "c")
        assertNull(vm.current)
    }
}
