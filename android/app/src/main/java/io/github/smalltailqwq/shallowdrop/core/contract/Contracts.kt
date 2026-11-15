package io.github.smalltailqwq.shallowdrop.core.contract

import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.CreateMessageRequest
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.FileObject
import io.github.smalltailqwq.shallowdrop.core.model.FilePurpose
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
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

data class ImportResult(val sessions: Int, val messages: Int, val files: Int)

/** 历史导入导出，格式见 src/services/chat/history/README.md。失败抛 HistoryException。 */
interface HistoryBackup {
    /** 校验后整体替换本地会话与附件；失败时保留原数据 */
    suspend fun importArchive(uri: Uri, onProgress: (ImportProgress) -> Unit = {}): ImportResult

    suspend fun exportArchive(uri: Uri)
}

class HistoryException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    suspend fun apiKey(): String?

    /** null 表示清除 */
    suspend fun setApiKey(apiKey: String?)

    suspend fun setLocale(locale: AppLocale)

    suspend fun setTheme(theme: ThemePreference)

    suspend fun setThinking(enabled: Boolean)

    suspend fun setSearch(enabled: Boolean)

    suspend fun setModel(model: ChatModel)
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

data class SendOptions(val thinking: Boolean, val search: Boolean, val model: ChatModel)

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
