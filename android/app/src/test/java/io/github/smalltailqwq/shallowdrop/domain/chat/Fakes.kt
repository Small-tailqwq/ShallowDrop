package io.github.smalltailqwq.shallowdrop.domain.chat

import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.contract.AttachmentStore
import io.github.smalltailqwq.shallowdrop.core.contract.ChatApi
import io.github.smalltailqwq.shallowdrop.core.contract.SessionStore
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ContentBlockDelta
import io.github.smalltailqwq.shallowdrop.core.model.CreateMessageRequest
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.FileObject
import io.github.smalltailqwq.shallowdrop.core.model.FilePurpose
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import io.github.smalltailqwq.shallowdrop.core.model.toSummary
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** 按调用顺序返回脚本化的事件流；没有脚本时回复一段固定文本。 */
class FakeChatApi : ChatApi {
    val requests = mutableListOf<CreateMessageRequest>()
    val scripts = ArrayDeque<(CreateMessageRequest) -> Flow<MessageStreamEvent>>()
    val uploads = mutableListOf<Triple<String, String, FilePurpose?>>()
    var uploadHandler: (name: String) -> FileObject = { FileObject(id = "file-api-${it.hashCode()}") }

    override suspend fun verifyApiKey(apiKey: String) = Unit

    override fun streamMessage(apiKey: String, request: CreateMessageRequest): Flow<MessageStreamEvent> {
        requests += request
        val script = scripts.removeFirstOrNull() ?: { textReply("ok") }
        return script(request)
    }

    override suspend fun uploadFile(
        apiKey: String,
        name: String,
        mimeType: String,
        purpose: FilePurpose?,
        content: () -> InputStream,
    ): FileObject {
        uploads += Triple(name, mimeType, purpose)
        content().use { it.readBytes() }
        return uploadHandler(name)
    }

    override suspend fun deleteFile(apiKey: String, fileId: String) = Unit
}

class FakeSessionStore : SessionStore {
    val data = MutableStateFlow<Map<String, Session>>(emptyMap())
    var upserts = 0

    override fun observeSummaries(): Flow<List<SessionSummary>> = data.map { map -> map.values.map { it.toSummary() } }

    override suspend fun load(sessionId: String): Session? = data.value[sessionId]

    override suspend fun save(session: Session) {
        data.update { it + (session.id to session) }
    }

    override suspend fun upsertMessage(sessionId: String, message: Message, updatedAt: Long, rootMessageId: String?) {
        upserts++
        data.update { map ->
            val session = map[sessionId] ?: return@update map
            val exists = session.messages.any { it.id == message.id }
            val messages = if (exists) session.messages.map { if (it.id == message.id) message else it } else session.messages + message
            map + (sessionId to session.copy(messages = messages, updatedAt = updatedAt, rootMessageId = rootMessageId))
        }
    }

    override suspend fun rename(sessionId: String, title: String) {
        data.update { map -> map[sessionId]?.let { map + (sessionId to it.copy(title = title)) } ?: map }
    }

    override suspend fun delete(sessionId: String) {
        data.update { it - sessionId }
    }

    override suspend fun deleteAll() {
        data.value = emptyMap()
    }
}

class FakeAttachmentStore(private val dir: File) : AttachmentStore {
    val serverIds = mutableMapOf<String, String>()
    private val files = mutableMapOf<String, File>()

    fun putLocal(id: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        val file = File(dir, id).apply { writeBytes(bytes) }
        files[id] = file
    }

    override suspend fun importFromUri(uri: Uri): FileAttachment = error("not used in tests")

    override suspend fun put(attachment: FileAttachment, content: InputStream) {
        putLocal(attachment.id, content.readBytes())
    }

    override fun file(attachmentId: String): File? = files[attachmentId]

    override suspend fun setServerId(attachmentId: String, serverId: String) {
        serverIds[attachmentId] = serverId
    }
}

class FakeSettings(initial: AppSettings = AppSettings(hasApiKey = true, locale = AppLocale.ZhCN)) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = state

    override suspend fun apiKey(): String = "sk-test"

    override suspend fun setApiKey(apiKey: String?) = Unit

    override suspend fun acceptRechargeNotice() = Unit
    override suspend fun setLocation(location: io.github.smalltailqwq.shallowdrop.core.model.LocationPreference) = state.update { it.copy(location = location) }
    override suspend fun setLocale(locale: AppLocale) = state.update { it.copy(locale = locale) }

    override suspend fun setTheme(theme: ThemePreference) = state.update { it.copy(theme = theme) }
    override suspend fun setChatFontSize(size: io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize) = state.update { it.copy(chatFontSize = size) }
    override suspend fun setAutoReadAloud(enabled: Boolean) = state.update { it.copy(autoReadAloud = enabled) }
    override suspend fun setReplySwipe(settings: io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings) = state.update { it.copy(replySwipe = settings) }

    override suspend fun setThinking(enabled: Boolean) = state.update { it.copy(thinking = enabled) }

    override suspend fun setSearch(enabled: Boolean) = state.update { it.copy(search = enabled) }

    override suspend fun setModel(model: ChatModel) = state.update { it.copy(model = model) }
    override suspend fun setCompactionSettings(thresholds: io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds, enabled: Boolean) = state.update { it.copy(compactionThresholds = thresholds, compactionEnabled = enabled) }
}

// ---------- 事件构造 ----------

private fun raw(type: String) = buildJsonObject { put("type", JsonPrimitive(type)) }

fun messageStart() = MessageStreamEvent.MessageStart(id = "resp", model = "deepseek-flash", usage = null)

fun textStart(index: Int, text: String = "") = MessageStreamEvent.ContentBlockStart(index, ReplyBlock.Text(text), raw("text"))

fun thinkingStart(index: Int) = MessageStreamEvent.ContentBlockStart(index, ReplyBlock.Thinking(""), raw("thinking"))

fun toolUseStart(index: Int) = MessageStreamEvent.ContentBlockStart(index, null, raw("tool_use"))

fun blockStart(index: Int, block: ReplyBlock) = MessageStreamEvent.ContentBlockStart(index, block, raw("other"))

fun textDelta(index: Int, text: String) = MessageStreamEvent.ContentBlockDeltaEvent(index, ContentBlockDelta.TextDelta(text))

fun thinkingDelta(index: Int, text: String) = MessageStreamEvent.ContentBlockDeltaEvent(index, ContentBlockDelta.ThinkingDelta(text))

fun signatureDelta(index: Int, signature: String) =
    MessageStreamEvent.ContentBlockDeltaEvent(index, ContentBlockDelta.SignatureDelta(signature))

fun citationDelta(index: Int, url: String) =
    MessageStreamEvent.ContentBlockDeltaEvent(index, ContentBlockDelta.CitationsDelta(TextCitation(url = url, title = "t")))

fun blockStop(index: Int) = MessageStreamEvent.ContentBlockStop(index)

fun messageDelta(stopReason: StopReason = StopReason.EndTurn, title: String? = null) =
    MessageStreamEvent.MessageDelta(stopReason, null, title, null)

fun textReply(text: String, title: String? = null): Flow<MessageStreamEvent> = flowOf(
    messageStart(),
    textStart(0),
    textDelta(0, text),
    blockStop(0),
    messageDelta(title = title),
    MessageStreamEvent.MessageStop,
)
