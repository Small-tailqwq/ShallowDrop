package io.github.smalltailqwq.shallowdrop.core.contract

import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.model.LocationPreference
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import io.github.smalltailqwq.shallowdrop.core.model.CreateMessageRequest
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.FileObject
import io.github.smalltailqwq.shallowdrop.core.model.FilePurpose
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * 跨层接口。实现方：
 * ChatApi → data/api（W1）；SessionStore、AttachmentStore → data/db（W2）；HistoryBackup → data/history（W2）；
 * SettingsRepository → data/settings（W4）；ChatRepository → domain/chat（W5）。
 * UI 只依赖 ChatRepository、SettingsRepository、HistoryBackup。
 */

/** DeepSeek API（Anthropic 兼容）。失败抛 ApiException；取消协程即中止请求。 */
interface ChatApi {
    /** GET /models 校验 Key，不产生费用 */
    suspend fun verifyApiKey(apiKey: String)

    /** POST /anthropic/v1/messages（stream），收到 message_stop 后结束；之前断流抛 Interrupted */
    fun streamMessage(apiKey: String, request: CreateMessageRequest): Flow<MessageStreamEvent>

    /** POST /anthropic/v1/files；purpose 为 null 表示图片 */
    suspend fun uploadFile(
        apiKey: String,
        name: String,
        mimeType: String,
        purpose: FilePurpose?,
        content: () -> InputStream,
    ): FileObject

    suspend fun deleteFile(apiKey: String, fileId: String)
}

/** 会话持久化。messages 以整条消息为单位读写。 */
interface SessionStore {
    fun observeSummaries(): Flow<List<SessionSummary>>

    suspend fun load(sessionId: String): Session?

    /** 写入会话元数据与全部消息（新建或整体覆盖） */
    suspend fun save(session: Session)

    /** 新增或覆盖单条消息，同时更新会话 updatedAt 与 rootMessageId */
    suspend fun upsertMessage(sessionId: String, message: Message, updatedAt: Long, rootMessageId: String?)

    suspend fun rename(sessionId: String, title: String)

    suspend fun delete(sessionId: String)

    /** 删除全部会话与附件原件 */
    suspend fun deleteAll()
}

/** 附件原件，存于 filesDir/attachments/{id}。 */
interface AttachmentStore {
    /** 从 SAF Uri 复制原件并生成附件元数据 */
    suspend fun importFromUri(uri: Uri): FileAttachment

    suspend fun put(attachment: FileAttachment, content: InputStream)

    /** 本地有原件时返回文件 */
    fun file(attachmentId: String): File?

    /** 记录 Files API 返回的 file_id */
    suspend fun setServerId(attachmentId: String, serverId: String)
}

data class ImportProgress(val done: Int, val total: Int?)

data class ImportResult(
    val sessions: Int,
    val messages: Int,
    val files: Int,
    /** 会话已导入生效，但图片原件尚未全部恢复到位（见 [ImageRestoreState.Pending]） */
    val imagesPending: Boolean = false,
)

/** 导入图片原件的恢复状态。导入先提交会话数据，再把图片原件换入；两步之间中断或失败时为 Pending。 */
enum class ImageRestoreState {
    /** 启动后尚未确认 */
    Checking,
    Ready,

    /** 会话已是导入后的数据，图片原件还没恢复完；可重试，应用启动时也会自动重试 */
    Pending,
}

/** 历史导入导出，格式见 src/services/chat/history/README.md。失败抛 HistoryException。 */
interface HistoryBackup {
    /** 校验后整体替换本地会话与附件；失败时保留原数据 */
    suspend fun importArchive(uri: Uri, onProgress: (ImportProgress) -> Unit = {}): ImportResult

    suspend fun exportArchive(uri: Uri)

    val imageRestore: StateFlow<ImageRestoreState>

    /** 尝试完成图片原件的恢复；返回恢复后图片是否全部就绪。不抛出恢复失败 */
    suspend fun retryImageRestore(): Boolean
}

class HistoryException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

data class DownloadProgress(val bytes: Long, val total: Long?)

/**
 * 官方 DeepSeek App（2.8.0+）「设置 → 数据管理 → 导出所有历史对话」生成的下载链接。
 * 链接由服务端签发、7 天有效、无需登录，下载得到的 ZIP 与导入格式相同。失败抛 HistoryException。
 */
interface ExportLinkDownloader {
    /** 从粘贴的文本中取出第一个 https 链接；没有时返回 null */
    fun parse(text: String): String?

    /** 文本中的官方导出链接（用于剪贴板自动填入）；其他 https 链接不算 */
    fun recognize(text: String): String?

    /** 下载到应用缓存后交给 [use]，结束后删除临时文件 */
    suspend fun <T> download(link: String, onProgress: (DownloadProgress) -> Unit, use: suspend (Uri) -> T): T
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    suspend fun apiKey(): String?

    /** null 表示清除 */
    suspend fun setApiKey(apiKey: String?)

    suspend fun acceptRechargeNotice()

    suspend fun setLocation(location: LocationPreference)

    suspend fun setLocale(locale: AppLocale)

    suspend fun setTheme(theme: ThemePreference)

    suspend fun setChatFontSize(size: io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize)

    suspend fun setAutoReadAloud(enabled: Boolean)

    suspend fun setReplySwipe(settings: io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings)

    suspend fun setThinking(enabled: Boolean)

    suspend fun setSearch(enabled: Boolean)

    suspend fun setModel(model: ChatModel)

    suspend fun setCompactionSettings(thresholds: CompactionThresholds, enabled: Boolean = settings.value.compactionEnabled)

    /** 语音对话与朗读的音色；默认空实现便于测试替身 */
    suspend fun setTtsVoice(voice: TtsVoice) = Unit
}

/** 当前路径上的一条消息及其兄弟分支信息（用于 `< 2/3 >` 切换）。 */
data class TurnView(
    val message: Message,
    /** 在兄弟节点中的序号，从 0 开始 */
    val siblingIndex: Int,
    val siblingCount: Int,
    /** 能否重新生成 / 续写，由 domain 规则决定 */
    val canRegenerate: Boolean = false,
    val canContinue: Boolean = false,
)

data class ConversationState(
    val session: SessionSummary,
    val turns: List<TurnView>,
    val generating: Boolean,
)

data class SendOptions(
    val thinking: Boolean,
    val search: Boolean,
    val model: ChatModel,
    /** 语音对话的轮次：system 追加语音模式说明（见 domain/voice/VoicePrompt.kt），不改变消息存储 */
    val voice: Boolean = false,
)

/** UI 使用的对话接口。 */
interface ChatRepository {
    val sessions: Flow<List<SessionSummary>>

    fun observeConversation(sessionId: String): Flow<ConversationState?>

    /** 发送新提问；sessionId 为 null 时新建会话。返回会话 id */
    suspend fun send(sessionId: String?, text: String, files: List<FileAttachment>, options: SendOptions): String

    fun stop(sessionId: String)

    suspend fun regenerate(sessionId: String, assistantMessageId: String, options: SendOptions)

    /** 编辑提问，作为原提问的兄弟节点重新发送 */
    suspend fun editAndResend(sessionId: String, userMessageId: String, text: String, options: SendOptions)

    suspend fun continueReply(sessionId: String, assistantMessageId: String)

    /** 切换到 messageId 的第 index 个兄弟节点 */
    suspend fun switchBranch(sessionId: String, messageId: String, index: Int)

    suspend fun addAttachment(uri: Uri): FileAttachment

    suspend fun rename(sessionId: String, title: String)

    suspend fun delete(sessionId: String)

    suspend fun deleteAll()
}
