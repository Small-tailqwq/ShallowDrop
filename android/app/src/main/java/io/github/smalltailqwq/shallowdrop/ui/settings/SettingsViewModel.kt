package io.github.smalltailqwq.shallowdrop.ui.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.DownloadProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ExportLinkDownloader
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryBackup
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryException
import io.github.smalltailqwq.shallowdrop.core.contract.ImportProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ImportResult
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import io.github.smalltailqwq.shallowdrop.ui.UiDependencies
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.UiText
import io.github.smalltailqwq.shallowdrop.ui.common.ApiKeyForm
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 数据操作进行中的状态；同一时间只允许一个 */
sealed interface DataTask {
    /** 下载官方 App 的导出链接，完成后转为 Importing */
    data class Downloading(val progress: DownloadProgress?) : DataTask

    data class Importing(val progress: ImportProgress?) : DataTask

    data object Exporting : DataTask

    data object Deleting : DataTask

    data object RestoringImages : DataTask
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val historyBackup: HistoryBackup,
    private val linkDownloader: ExportLinkDownloader,
    verifyApiKey: suspend (String) -> Unit,
) : ViewModel() {
    val settings = settingsRepository.settings
    val apiKeyForm = ApiKeyForm(viewModelScope, settingsRepository, verifyApiKey)

    private val taskState = MutableStateFlow<DataTask?>(null)
    val task: StateFlow<DataTask?> = taskState.asStateFlow()

    /** 导入的会话已生效但图片原件还没恢复完时为 Pending，设置页据此显示重试入口 */
    val imageRestore = historyBackup.imageRestore

    private val eventChannel = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = eventChannel.receiveAsFlow()

    /** 已保存 Key 的掩码（只保留前 3 位与后 4 位） */
    private val maskedKeyState = MutableStateFlow<String?>(null)
    val maskedKey: StateFlow<String?> = maskedKeyState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.collect { refreshMask() }
        }
    }

    private suspend fun refreshMask() {
        val key = settingsRepository.apiKey()
        maskedKeyState.value = key?.let {
            if (it.length <= 8) "•".repeat(it.length) else it.take(3) + "•".repeat(6) + it.takeLast(4)
        }
    }

    fun setLocale(locale: AppLocale) = viewModelScope.launch { settingsRepository.setLocale(locale) }

    fun setTheme(theme: ThemePreference) = viewModelScope.launch { settingsRepository.setTheme(theme) }

    fun setModel(model: ChatModel) = viewModelScope.launch { settingsRepository.setModel(model) }

    private fun runTask(initial: DataTask, block: suspend () -> UiText) {
        if (taskState.value != null) return
        taskState.value = initial
        viewModelScope.launch {
            try {
                eventChannel.trySend(block())
            } finally {
                taskState.value = null
            }
        }
    }

    fun importHistory(uri: Uri) = runTask(DataTask.Importing(null)) { importResult { runImport(uri) } }

    /** 从粘贴的链接中取出下载地址；不是有效链接时返回 null */
    fun parseLink(text: String): String? = linkDownloader.parse(text)

    /** 下载官方 App 导出的链接后导入；下载失败不改动本地数据 */
    fun importHistoryFromLink(link: String) = runTask(DataTask.Downloading(null)) {
        importResult {
            linkDownloader.download(link, onProgress = { taskState.value = DataTask.Downloading(it) }) { uri ->
                taskState.value = DataTask.Importing(null)
                runImport(uri)
            }
        }
    }

    private suspend fun runImport(uri: Uri) =
        historyBackup.importArchive(uri) { progress -> taskState.value = DataTask.Importing(progress) }

    private suspend fun importResult(block: suspend () -> ImportResult): UiText =
        try {
            val result = block()
            // 会话已生效但图片没恢复完时不报「导入成功」，明确告知并指向重试
            if (result.imagesPending) {
                UiText.Res(R.string.history_imported_images_pending)
            } else {
                UiText.Plural(R.plurals.history_imported, result.sessions)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HistoryException) {
            Log.w(TAG, "导入失败", e)
            UiText.Res(backupErrorMessage(e.code))
        } catch (e: Exception) {
            Log.w(TAG, "导入失败", e)
            UiText.Res(R.string.history_import_failed)
        }

    fun retryImageRestore() = runTask(DataTask.RestoringImages) {
        try {
            if (historyBackup.retryImageRestore()) {
                UiText.Res(R.string.images_restored)
            } else {
                UiText.Res(R.string.images_restore_failed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "图片恢复失败", e)
            UiText.Res(R.string.images_restore_failed)
        }
    }

    fun exportHistory(uri: Uri) = runTask(DataTask.Exporting) {
        try {
            historyBackup.exportArchive(uri)
            UiText.Res(R.string.history_exported)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "导出失败", e)
            UiText.Format(R.string.history_export_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    fun deleteAll() = runTask(DataTask.Deleting) {
        try {
            chatRepository.deleteAll()
            // 全部删除会一并丢弃待恢复的图片，刷新恢复状态
            historyBackup.retryImageRestore()
            UiText.Res(R.string.deleted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "删除失败", e)
            UiText.Res(R.string.delete_failed)
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"

        val Factory = uiViewModelFactory { deps: UiDependencies, _ ->
            SettingsViewModel(
                deps.settingsRepository,
                deps.chatRepository,
                deps.historyBackup,
                deps.exportLinkDownloader,
                deps.verifyApiKey,
            )
        }

        /** HistoryException.code 采用 Web 版 i18n 键名（backupXxx） */
        fun backupErrorMessage(code: String): Int = when (code) {
            "backupInvalidJson" -> R.string.backup_invalid_json
            "backupUnrecognized" -> R.string.backup_unrecognized
            "backupNewerVersion" -> R.string.backup_newer_version
            "backupInvalidVersion" -> R.string.backup_invalid_version
            "backupIncomplete" -> R.string.backup_incomplete
            "backupInvalidBranches" -> R.string.backup_invalid_branches
            "backupInvalidFiles" -> R.string.backup_invalid_files
            "backupLinkInvalid" -> R.string.backup_link_invalid
            "backupLinkExpired" -> R.string.backup_link_expired
            "backupDownloadFailed" -> R.string.backup_download_failed
            else -> R.string.history_import_failed
        }
    }
}
