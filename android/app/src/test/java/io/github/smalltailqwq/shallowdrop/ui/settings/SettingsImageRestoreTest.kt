package io.github.smalltailqwq.shallowdrop.ui.settings

import android.net.Uri
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.DownloadProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ExportLinkDownloader
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryBackup
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryException
import io.github.smalltailqwq.shallowdrop.core.contract.ImageRestoreState
import io.github.smalltailqwq.shallowdrop.core.contract.ImportProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ImportResult
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

/** 设置页对「会话已导入、图片待恢复」的提示与重试。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsImageRestoreTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Backup : HistoryBackup {
        var swapWorks = false
        val state = MutableStateFlow(ImageRestoreState.Ready)
        override val imageRestore: StateFlow<ImageRestoreState> = state

        override suspend fun importArchive(uri: Uri, onProgress: (ImportProgress) -> Unit): ImportResult {
            state.value = if (swapWorks) ImageRestoreState.Ready else ImageRestoreState.Pending
            return ImportResult(sessions = 3, messages = 9, files = 2, imagesPending = !swapWorks)
        }

        override suspend fun exportArchive(uri: Uri) = Unit

        override suspend fun retryImageRestore(): Boolean {
            state.value = if (swapWorks) ImageRestoreState.Ready else ImageRestoreState.Pending
            return swapWorks
        }
    }

    private class Repo : ChatRepository {
        override val sessions: Flow<List<SessionSummary>> = flowOf(emptyList())
        override fun observeConversation(sessionId: String): Flow<ConversationState?> = flowOf(null)
        override suspend fun send(sessionId: String?, text: String, files: List<FileAttachment>, options: SendOptions) = "s"
        override fun stop(sessionId: String) = Unit
        override suspend fun regenerate(sessionId: String, assistantMessageId: String, options: SendOptions) = Unit
        override suspend fun editAndResend(sessionId: String, userMessageId: String, text: String, options: SendOptions) = Unit
        override suspend fun continueReply(sessionId: String, assistantMessageId: String) = Unit
        override suspend fun switchBranch(sessionId: String, messageId: String, index: Int) = Unit
        override suspend fun addAttachment(uri: Uri): FileAttachment = error("unused")
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun delete(sessionId: String) = Unit
        override suspend fun deleteAll() = Unit
    }

    private class Settings : SettingsRepository {
        override val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings(hasApiKey = true))
        override suspend fun apiKey(): String? = "sk-test-key"
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

    /** 不联网：fail 非空时按该错误码失败，否则把固定 Uri 交给导入 */
    private class Links : ExportLinkDownloader {
        var fail: String? = null
        val downloaded = mutableListOf<String>()
        override fun parse(text: String): String? = text.trim().takeIf { it.startsWith("https://") }
        override fun recognize(text: String): String? = parse(text)
        override suspend fun <T> download(link: String, onProgress: (DownloadProgress) -> Unit, use: suspend (Uri) -> T): T {
            fail?.let { throw HistoryException(it, it) }
            downloaded += link
            onProgress(DownloadProgress(10, 10))
            return use(Uri.parse("file:///cache/history-link/export.zip"))
        }
    }

    private val backup = Backup()
    private val links = Links()
    private fun viewModel() = SettingsViewModel(Settings(), Repo(), backup, links) {}

    @Test
    fun linkImportDownloadsThenImports() = runTest(dispatcher) {
        backup.swapWorks = true
        val vm = viewModel()
        vm.importHistoryFromLink("https://example.com/export.zip")
        assertEquals(UiText.Plural(R.plurals.history_imported, 3), vm.events.first())
        assertEquals(listOf("https://example.com/export.zip"), links.downloaded)
        assertNull(vm.task.value)
    }

    @Test
    fun expiredLinkShowsReexportHint() = runTest(dispatcher) {
        links.fail = "backupLinkExpired"
        val vm = viewModel()
        vm.importHistoryFromLink("https://example.com/export.zip")
        assertEquals(UiText.Res(R.string.backup_link_expired), vm.events.first())
        assertEquals(ImageRestoreState.Ready, vm.imageRestore.value)
        assertNull(vm.task.value)
    }

    @Test
    fun pendingImportIsNotReportedAsPlainSuccess() = runTest(dispatcher) {
        val vm = viewModel()
        vm.importHistory(Uri.parse("content://x/a.zip"))
        assertEquals(UiText.Res(R.string.history_imported_images_pending), vm.events.first())
        assertEquals(ImageRestoreState.Pending, vm.imageRestore.value)
        assertNull(vm.task.value)

        // 故障未排除：提示失败，仍为待恢复
        vm.retryImageRestore()
        assertEquals(UiText.Res(R.string.images_restore_failed), vm.events.first())
        assertEquals(ImageRestoreState.Pending, vm.imageRestore.value)

        backup.swapWorks = true
        vm.retryImageRestore()
        assertEquals(UiText.Res(R.string.images_restored), vm.events.first())
        assertEquals(ImageRestoreState.Ready, vm.imageRestore.value)
    }

    @Test
    fun completeImportReportsCount() = runTest(dispatcher) {
        backup.swapWorks = true
        val vm = viewModel()
        vm.importHistory(Uri.parse("content://x/a.zip"))
        assertEquals(UiText.Plural(R.plurals.history_imported, 3), vm.events.first())
        assertEquals(ImageRestoreState.Ready, vm.imageRestore.value)
    }

    @Test
    fun deleteAllClearsPendingState() = runTest(dispatcher) {
        val vm = viewModel()
        vm.importHistory(Uri.parse("content://x/a.zip"))
        vm.events.first()
        // 全部删除连同待恢复的图片一起丢弃（存储层行为见 ImportSwapRecoveryTest），状态随之刷新
        backup.swapWorks = true
        vm.deleteAll()
        assertEquals(UiText.Res(R.string.deleted), vm.events.first())
        assertEquals(ImageRestoreState.Ready, vm.imageRestore.value)
    }
}
