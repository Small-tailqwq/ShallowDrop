package io.github.smalltailqwq.shallowdrop.data.db

import androidx.room.withTransaction
import io.github.smalltailqwq.shallowdrop.core.contract.SessionStore
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SessionStore 的 Room 实现，对照 src/services/chat/db.ts。
 * 消息逐条存为 JSON；附件引用另存 message_files，删除会话时只清理已无引用的原件（远端文件不删）。
 */
class RoomSessionStore(
    private val db: AppDatabase,
    private val paths: AttachmentPaths,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SessionStore {
    private val dao = db.chatDao()

    override fun observeSummaries(): Flow<List<SessionSummary>> = dao.observeSummaries()

    override suspend fun load(sessionId: String): Session? = db.readSession(sessionId)

    override suspend fun save(session: Session) {
        db.withTransaction {
            dao.upsertSession(session.toEntity())
            dao.deleteMessages(session.id)
            dao.deleteMessageFilesOf(session.id)
            dao.upsertMessages(session.messages.mapIndexed { index, message -> message.toEntity(session.id, index) })
            writeReferences(session.id, session.messages)
        }
    }

    /** 会话不存在（例如生成过程中被删除）时忽略，不再写入孤立消息。 */
    override suspend fun upsertMessage(sessionId: String, message: Message, updatedAt: Long, rootMessageId: String?) {
        db.withTransaction {
            if (dao.touchSession(sessionId, updatedAt, rootMessageId) == 0) {
                return@withTransaction
            }
            val seq = dao.message(sessionId, message.id)?.seq ?: ((dao.maxSeq(sessionId) ?: -1) + 1)
            dao.upsertMessages(listOf(message.toEntity(sessionId, seq)))
            dao.deleteMessageFilesOf(sessionId, message.id)
            writeReferences(sessionId, listOf(message))
        }
    }

    override suspend fun rename(sessionId: String, title: String) {
        dao.rename(sessionId, title)
    }

    override suspend fun delete(sessionId: String) {
        val orphaned = db.withTransaction {
            val fileIds = dao.fileIdsOf(sessionId)
            dao.deleteSession(sessionId)
            dao.deleteMessages(sessionId)
            dao.deleteMessageFilesOf(sessionId)
            fileIds.filter { dao.referenceCount(it) == 0 }.onEach { dao.deleteFile(it) }
        }
        withContext(io) { orphaned.forEach { paths.file(it).delete() } }
    }

    override suspend fun deleteAll() {
        db.historyLock.withLock {
            dao.clearAll()
            withContext(io) {
                paths.dir.deleteRecursively()
                paths.dir.mkdirs()
            }
        }
    }

    /** 记录消息引用的附件；附件元数据缺失时（例如未经 AttachmentStore 创建）补一行。 */
    private suspend fun writeReferences(sessionId: String, messages: List<Message>) {
        val refs = messages.flatMap { message -> message.files().map { MessageFileEntity(sessionId, message.id, it.id) } }
        if (refs.isEmpty()) return
        dao.insertMessageFiles(refs)
        dao.insertFilesIfAbsent(messages.flatMap { it.files() }.map { it.toEntity() })
    }
}
