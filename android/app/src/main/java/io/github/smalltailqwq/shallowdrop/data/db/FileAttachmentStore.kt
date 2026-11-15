package io.github.smalltailqwq.shallowdrop.data.db

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import io.github.smalltailqwq.shallowdrop.core.contract.AttachmentStore
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 附件原件存于 filesDir/attachments/{id}，元数据存 files 表。
 * 引用同一 id 的消息共用一份原件；原件先写临时文件再改名，避免中断后留下半个文件。
 */
class FileAttachmentStore(
    private val db: AppDatabase,
    private val paths: AttachmentPaths,
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentStore {
    constructor(context: Context, db: AppDatabase) :
        this(db, AttachmentPaths(context.filesDir), context.contentResolver)

    private val dao = db.chatDao()

    /** 文件名与大小取自 OpenableColumns；MIME 优先用 ContentResolver 给出的类型，缺失时按扩展名推断，图片身份由 MIME 决定。 */
    override suspend fun importFromUri(uri: Uri): FileAttachment = withContext(io) {
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            }
        }
        val fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: guessMimeType(fileName)
        val id = UUID.randomUUID().toString()
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        // 实际大小以复制的字节数为准，OpenableColumns.SIZE 可能缺失或不准确。
        val size = input.use { writeOriginal(id, it) }
        val attachment = FileAttachment(
            id = id,
            name = fileName,
            size = size,
            isImage = mimeType.startsWith("image/"),
            mimeType = mimeType,
        )
        dao.upsertFile(attachment.toEntity())
        attachment
    }

    override suspend fun put(attachment: FileAttachment, content: InputStream) {
        withContext(io) { writeOriginal(attachment.id, content) }
        dao.upsertFile(attachment.toEntity())
    }

    override fun file(attachmentId: String): File? = paths.file(attachmentId).takeIf { it.isFile }

    /** 同时更新 files 表与所有引用该附件的消息 JSON，使下次请求直接复用 file_id。 */
    override suspend fun setServerId(attachmentId: String, serverId: String) {
        db.withTransaction {
            dao.setServerId(attachmentId, serverId)
            for (ref in dao.referencesOf(attachmentId)) {
                val row = dao.message(ref.sessionId, ref.messageId) ?: continue
                val message = DbJson.decode(row.json) as? Message.User ?: continue
                val updated = message.copy(
                    files = message.files.map { if (it.id == attachmentId) it.copy(serverId = serverId) else it },
                )
                if (updated != message) dao.upsertMessages(listOf(row.copy(json = DbJson.encode(updated))))
            }
        }
    }

    /** 附件元数据（含 serverId），不存在时返回 null。 */
    suspend fun metadata(attachmentId: String): FileAttachment? = dao.file(attachmentId)?.toAttachment()

    private fun writeOriginal(id: String, content: InputStream): Long {
        paths.dir.mkdirs()
        val target = paths.file(id)
        val temp = File(paths.dir, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            val size = temp.outputStream().use { content.copyTo(it) }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw java.io.IOException("无法写入附件原件：$id")
            }
            return size
        } finally {
            temp.delete()
        }
    }

    companion object {
        fun guessMimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return ""
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
        }
    }
}
