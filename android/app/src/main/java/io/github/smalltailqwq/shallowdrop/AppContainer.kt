package io.github.smalltailqwq.shallowdrop

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.smalltailqwq.shallowdrop.core.contract.AttachmentStore
import io.github.smalltailqwq.shallowdrop.core.contract.ChatApi
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ExportLinkDownloader
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryBackup
import io.github.smalltailqwq.shallowdrop.core.contract.ImportProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ImportResult
import io.github.smalltailqwq.shallowdrop.core.contract.MemoryRepository
import io.github.smalltailqwq.shallowdrop.core.contract.SessionStore
import io.github.smalltailqwq.shallowdrop.data.memory.FileMemoryStore
import io.github.smalltailqwq.shallowdrop.domain.memory.MemoryEngine
import io.github.smalltailqwq.shallowdrop.data.api.DeepSeekApi
import io.github.smalltailqwq.shallowdrop.data.api.DeepSeekSpeechApi
import io.github.smalltailqwq.shallowdrop.data.api.defaultOkHttpClient
import io.github.smalltailqwq.shallowdrop.data.db.AppDatabase
import io.github.smalltailqwq.shallowdrop.data.db.AttachmentPaths
import io.github.smalltailqwq.shallowdrop.data.db.FileAttachmentStore
import io.github.smalltailqwq.shallowdrop.data.db.RoomSessionStore
import io.github.smalltailqwq.shallowdrop.data.history.ArchiveHistoryBackup
import io.github.smalltailqwq.shallowdrop.data.history.OkHttpExportLinkDownloader
import java.io.File
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

    private val httpClient by lazy { defaultOkHttpClient() }

    val chatApi: ChatApi by lazy { DeepSeekApi(httpClient) }

    /** 语音识别与合成（/v1/tools/asr、/v1/tools/tts） */
    val speechApi: DeepSeekSpeechApi by lazy { DeepSeekSpeechApi(httpClient) }

    /** 官方 App 导出链接的下载缓存放在 cacheDir/history-link，导入后即删 */
    val exportLinkDownloader: ExportLinkDownloader by lazy {
        OkHttpExportLinkDownloader(httpClient, File(appContext.cacheDir, "history-link"))
    }

    val sessionStore: SessionStore by lazy { RoomSessionStore(database, AttachmentPaths(appContext.filesDir)) }

    val attachmentStore: AttachmentStore by lazy { FileAttachmentStore(appContext, database) }

    val settingsRepository: DataStoreSettingsRepository by lazy { DataStoreSettingsRepository(appContext) }

    val locationProvider by lazy { io.github.smalltailqwq.shallowdrop.data.location.AndroidLocationProvider(appContext) }

    private val chatEngine: ChatEngine by lazy {
        ChatEngine(
            chatApi,
            sessionStore,
            attachmentStore,
            settingsRepository,
            appScope,
            resolveLocation = locationProvider::locate,
            // 发送前原件取不到时，先尝试补完导入图片的恢复
            restoreAttachments = { archiveBackup.retryImageRestore() },
            memoryBlock = { sessionId ->
                memoryEngine.settleOthers(sessionId)
                memoryEngine.systemBlock()
            },
            onReplyFinished = memoryEngine::onReplyFinished,
            onSessionsRemoved = memoryEngine::forgetSessions,
        )
    }

    private val memoryEngine: MemoryEngine by lazy {
        MemoryEngine(chatApi, sessionStore, FileMemoryStore(File(appContext.filesDir, "memory/memory.json")), settingsRepository, appScope,
            log = { message, error -> Log.w("MemoryEngine", message, error) })
    }

    val memoryRepository: MemoryRepository get() = memoryEngine

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

            override val imageRestore get() = archiveBackup.imageRestore

            override suspend fun retryImageRestore() = archiveBackup.retryImageRestore()
        }
    }

    /** 启动时补完中断的导入；消息在打开会话时收尾，避免扫描全部历史。 */
    fun onAppStart() {
        appScope.launch {
            // 失败时状态保持 Pending：设置页提示并可重试，发送带图消息前也会再试
            runCatching { archiveBackup.retryImageRestore() }
        }
        memoryEngine.start()
    }
}
