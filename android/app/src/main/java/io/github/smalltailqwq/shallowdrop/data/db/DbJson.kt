package io.github.smalltailqwq.shallowdrop.data.db

import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.Session
import kotlinx.serialization.json.Json

/** messages.json 列的编码：整条 Message，多态以 "type" 判别；读取时忽略未知字段，便于以后增字段。 */
object DbJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(message: Message): String = json.encodeToString(Message.serializer(), message)

    fun decode(text: String): Message = json.decodeFromString(Message.serializer(), text)
}

internal fun Message.role(): String = if (this is Message.User) "user" else "assistant"

internal fun Message.files(): List<FileAttachment> = (this as? Message.User)?.files.orEmpty()

internal fun Session.toEntity() = SessionEntity(id, title, createdAt, updatedAt, rootMessageId)

internal fun FileAttachment.toEntity() = FileEntity(id, serverId, name, size, isImage, mimeType)

internal fun FileEntity.toAttachment() = FileAttachment(id, serverId, name, size, isImage, mimeType)

internal fun Message.toEntity(sessionId: String, seq: Int) =
    MessageEntity(sessionId, id, seq, createdAt, role(), DbJson.encode(this), parentId, selectedChildId)

/** 读取一个完整会话；不存在时返回 null。导出与 RoomSessionStore 共用。 */
suspend fun AppDatabase.readSession(sessionId: String): Session? {
    val dao = chatDao()
    val row = dao.session(sessionId) ?: return null
    return Session(
        id = row.id,
        title = row.title,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        messages = dao.messages(sessionId).map { DbJson.decode(it.json) },
        rootMessageId = row.rootMessageId,
    )
}
