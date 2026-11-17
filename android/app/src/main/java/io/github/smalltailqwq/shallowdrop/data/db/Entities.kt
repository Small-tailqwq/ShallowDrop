package io.github.smalltailqwq.shallowdrop.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room 表结构，对照 src/services/chat/db.ts：
 * - sessions：会话元数据；
 * - messages：每条消息一行，整条 Message 以 JSON 存储（blocks、附件等复杂字段都在其中）；
 *   导入的消息 ID 是会话内序号（"1"、"2"…），不同会话会重复，主键为 (sessionId, id)；
 * - message_files：消息引用的附件，删除会话时据此判断原件是否仍被引用（对应 Web 的 fileIds 索引）；
 * - files：附件元数据，原件存于 filesDir/attachments/；
 * - staging_*：历史导入暂存，导入过程中只写这些表，全部校验通过后在一个事务中替换正式数据；
 * - import_state：导入提交后的原件目录交换标记，进程中断时据此恢复。
 */

@Entity(tableName = "sessions", indices = [Index("updatedAt")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val rootMessageId: String?,
)

@Entity(
    tableName = "messages",
    primaryKeys = ["sessionId", "id"],
    indices = [Index(value = ["sessionId", "seq", "id", "parentId", "selectedChildId"], name = "index_messages_branches"), Index("id"), Index("createdAt")],
)
data class MessageEntity(
    val sessionId: String,
    val id: String,
    /** 会话内的创建顺序；覆盖写入时保持不变 */
    val seq: Int,
    val createdAt: Long,
    /** user / assistant，便于排查，读取以 json 为准 */
    val role: String,
    /** 整条 Message 的 JSON，见 DbJson */
    val json: String,
    val parentId: String?,
    val selectedChildId: String?,
)

data class MessageSearchRow(val rowId: Long, val sessionId: String, val json: String)

@Entity(
    tableName = "message_files",
    primaryKeys = ["sessionId", "messageId", "fileId"],
    indices = [Index("fileId")],
)
data class MessageFileEntity(
    val sessionId: String,
    val messageId: String,
    val fileId: String,
)

@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    val name: String,
    val size: Long,
    val isImage: Boolean,
    val mimeType: String,
)

// ---------- 导入暂存，列与正式表一致，提交时 INSERT … SELECT 整表复制 ----------

@Entity(tableName = "staging_sessions")
data class StagingSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val rootMessageId: String?,
)

@Entity(tableName = "staging_messages", primaryKeys = ["sessionId", "id"])
data class StagingMessageEntity(
    val sessionId: String,
    val id: String,
    val seq: Int,
    val createdAt: Long,
    val role: String,
    val json: String,
    val parentId: String?,
    val selectedChildId: String?,
)

@Entity(
    tableName = "staging_message_files",
    primaryKeys = ["sessionId", "messageId", "fileId"],
    indices = [Index("fileId")],
)
data class StagingMessageFileEntity(
    val sessionId: String,
    val messageId: String,
    val fileId: String,
)

@Entity(tableName = "staging_files")
data class StagingFileEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    val name: String,
    val size: Long,
    val isImage: Boolean,
    val mimeType: String,
)

@Entity(tableName = "import_state")
data class ImportStateEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "value") val value: String,
)
