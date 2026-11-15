package io.github.smalltailqwq.shallowdrop

import android.content.Context
import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.contract.AttachmentStore
import io.github.smalltailqwq.shallowdrop.core.contract.ChatApi
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryBackup
import io.github.smalltailqwq.shallowdrop.core.contract.ImportProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ImportResult
import io.github.smalltailqwq.shallowdrop.core.contract.SessionStore
import io.github.smalltailqwq.shallowdrop.data.api.DeepSeekApi
import io.github.smalltailqwq.shallowdrop.data.api.defaultOkHttpClient
import io.github.smalltailqwq.shallowdrop.data.db.AppDatabase
import io.github.smalltailqwq.shallowdrop.data.db.AttachmentPaths
import io.github.smalltailqwq.shallowdrop.data.db.FileAttachmentStore
import io.github.smalltailqwq.shallowdrop.data.db.RoomSessionStore
import io.github.smalltailqwq.shallowdrop.data.history.ArchiveHistoryBackup
import io.github.smalltailqwq.shallowdrop.data.settings.DataStoreSettingsRepository
import io.github.smalltailqwq.shallowdrop.domain.chat.ChatEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 手动依赖注入。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** 应用级作用域：生成在这里运行，离开页面不中断 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val chatApi: ChatApi by lazy { DeepSeekApi(defaultOkHttpClient()) }

    val sessionStore: SessionStore by lazy { RoomSessionStore(database, AttachmentPaths(appContext.filesDir)) }

    val attachmentStore: AttachmentStore by lazy { FileAttachmentStore(appContext, database) }

    val settingsRepository: DataStoreSettingsRepository by lazy { DataStoreSettingsRepository(appContext) }

    private val chatEngine: ChatEngine by lazy {
        ChatEngine(chatApi, sessionStore, attachmentStore, settingsRepository, appScope)
    }

    val chatRepository: ChatRepository get() = chatEngine

    private val archiveBackup: ArchiveHistoryBackup by lazy { ArchiveHistoryBackup(appContext, database) }

    /** 导入前先检查格式，通过后停止生成、整体替换并刷新对话缓存 */
    val historyBackup: HistoryBackup by lazy {
        object : HistoryBackup {
            override suspend fun importArchive(uri: Uri, onProgress: (ImportProgress) -> Unit): ImportResult {
                archiveBackup.checkArchive(uri)
                return chatEngine.replaceAll { archiveBackup.importArchive(uri, onProgress) }
            }

            override suspend fun exportArchive(uri: Uri) = archiveBackup.exportArchive(uri)
        }
    }

    /** 启动时收尾：补完中断的导入，把残留的进行中回复标为已停止 */
    fun onAppStart() {
        appScope.launch {
            runCatching { archiveBackup.recoverInterruptedImport() }
            runCatching { chatEngine.markInterruptedOnStartup() }
        }
    }
}
