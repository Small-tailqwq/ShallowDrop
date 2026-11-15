package io.github.smalltailqwq.shallowdrop.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import kotlinx.coroutines.flow.Flow

/** 正式数据的读写。 */
@Dao
interface ChatDao {
    @Query("SELECT id, title, createdAt, updatedAt FROM sessions ORDER BY updatedAt DESC, id")
    fun observeSummaries(): Flow<List<SessionSummary>>

    @Query("SELECT id, title, createdAt, updatedAt FROM sessions ORDER BY updatedAt DESC, id")
    suspend fun summaries(): List<SessionSummary>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq")
    suspend fun messages(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND id = :id")
    suspend fun message(sessionId: String, id: String): MessageEntity?

    @Query("SELECT MAX(seq) FROM messages WHERE sessionId = :sessionId")
    suspend fun maxSeq(sessionId: String): Int?

    @Upsert
    suspend fun upsertSession(session: SessionEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("UPDATE sessions SET updatedAt = :updatedAt, rootMessageId = :rootMessageId WHERE id = :id")
    suspend fun touchSession(id: String, updatedAt: Long, rootMessageId: String?): Int

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    // ---------- 消息与附件的引用 ----------

    @Query("SELECT DISTINCT fileId FROM message_files WHERE sessionId = :sessionId")
    suspend fun fileIdsOf(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM message_files WHERE fileId = :fileId")
    suspend fun referenceCount(fileId: String): Int

    @Query("SELECT * FROM message_files WHERE fileId = :fileId")
    suspend fun referencesOf(fileId: String): List<MessageFileEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageFiles(rows: List<MessageFileEntity>)

    @Query("DELETE FROM message_files WHERE sessionId = :sessionId")
    suspend fun deleteMessageFilesOf(sessionId: String)

    @Query("DELETE FROM message_files WHERE sessionId = :sessionId AND messageId = :messageId")
    suspend fun deleteMessageFilesOf(sessionId: String, messageId: String)

    // ---------- 附件元数据 ----------

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun file(id: String): FileEntity?

    @Upsert
    suspend fun upsertFile(file: FileEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFilesIfAbsent(files: List<FileEntity>)

    @Query("UPDATE files SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: String, serverId: String)

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun deleteFile(id: String)

    // ---------- 全部删除 ----------

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    @Query("DELETE FROM message_files")
    suspend fun clearMessageFiles()

    @Query("DELETE FROM files")
    suspend fun clearFiles()

    @Transaction
    suspend fun clearAll() {
        clearSessions()
        clearMessages()
        clearMessageFiles()
        clearFiles()
    }
}

/** 历史导入暂存与提交。 */
@Dao
interface StagingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: StagingSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessages(messages: List<StagingMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageFiles(rows: List<StagingMessageFileEntity>)

    /** 同一文件 ID 出现在多条消息中时保留首次出现的元数据 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFilesIfAbsent(files: List<StagingFileEntity>)

    @Query("UPDATE staging_files SET mimeType = :mimeType WHERE id = :id")
    suspend fun setFileMimeType(id: String, mimeType: String)

    @Query("SELECT * FROM staging_message_files WHERE fileId = :fileId")
    suspend fun referencesOf(fileId: String): List<StagingMessageFileEntity>

    @Query("SELECT * FROM staging_messages WHERE sessionId = :sessionId AND id = :id")
    suspend fun message(sessionId: String, id: String): StagingMessageEntity?

    @Query("UPDATE staging_messages SET json = :json WHERE sessionId = :sessionId AND id = :id")
    suspend fun updateMessageJson(sessionId: String, id: String, json: String)

    @Query("SELECT COUNT(*) FROM staging_messages")
    suspend fun messageCount(): Int

    @Query("DELETE FROM staging_sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM staging_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM staging_message_files")
    suspend fun clearMessageFiles()

    @Query("DELETE FROM staging_files")
    suspend fun clearFiles()

    @Transaction
    suspend fun clearStaging() {
        clearSessions()
        clearMessages()
        clearMessageFiles()
        clearFiles()
    }

    @Query("DELETE FROM sessions")
    suspend fun clearLiveSessions()

    @Query("DELETE FROM messages")
    suspend fun clearLiveMessages()

    @Query("DELETE FROM message_files")
    suspend fun clearLiveMessageFiles()

    @Query("DELETE FROM files")
    suspend fun clearLiveFiles()

    @Query(
        "INSERT INTO sessions (id, title, createdAt, updatedAt, rootMessageId) " +
            "SELECT id, title, createdAt, updatedAt, rootMessageId FROM staging_sessions",
    )
    suspend fun copySessions()

    @Query(
        "INSERT INTO messages (sessionId, id, seq, createdAt, role, json) " +
            "SELECT sessionId, id, seq, createdAt, role, json FROM staging_messages",
    )
    suspend fun copyMessages()

    @Query(
        "INSERT INTO message_files (sessionId, messageId, fileId) " +
            "SELECT sessionId, messageId, fileId FROM staging_message_files",
    )
    suspend fun copyMessageFiles()

    @Query(
        "INSERT INTO files (id, serverId, name, size, isImage, mimeType) " +
            "SELECT id, serverId, name, size, isImage, mimeType FROM staging_files",
    )
    suspend fun copyFiles()

    @Upsert
    suspend fun putState(state: ImportStateEntity)

    @Query("SELECT value FROM import_state WHERE `key` = :key")
    suspend fun state(key: String): String?

    @Query("DELETE FROM import_state WHERE `key` = :key")
    suspend fun deleteState(key: String)

    /**
     * 用暂存整体替换正式数据：同一事务中清空正式表、复制暂存、清空暂存并写入原件目录交换标记。
     * 事务失败时整体回滚，正式数据不变。
     */
    @Transaction
    suspend fun commit() {
        clearLiveSessions()
        clearLiveMessages()
        clearLiveMessageFiles()
        clearLiveFiles()
        copySessions()
        copyMessages()
        copyMessageFiles()
        copyFiles()
        clearStaging()
        putState(ImportStateEntity(STATE_FILES_SWAP_PENDING, "1"))
    }

    companion object {
        /** 数据库已提交、原件暂存目录尚未换入正式目录 */
        const val STATE_FILES_SWAP_PENDING = "files_swap_pending"
    }
}
