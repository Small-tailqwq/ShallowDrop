package io.github.smalltailqwq.shallowdrop.data.history

import androidx.room.withTransaction
import io.github.smalltailqwq.shallowdrop.core.contract.ImportProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ImportResult
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.data.db.AppDatabase
import io.github.smalltailqwq.shallowdrop.data.db.AttachmentPaths
import io.github.smalltailqwq.shallowdrop.data.db.DbJson
import io.github.smalltailqwq.shallowdrop.data.db.ImportStateEntity
import io.github.smalltailqwq.shallowdrop.data.db.StagingDao
import io.github.smalltailqwq.shallowdrop.data.db.StagingFileEntity
import io.github.smalltailqwq.shallowdrop.data.db.StagingMessageEntity
import io.github.smalltailqwq.shallowdrop.data.db.StagingMessageFileEntity
import io.github.smalltailqwq.shallowdrop.data.db.StagingSessionEntity
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** 从原件字节补充图片 MIME；无法识别时为空字符串，图片身份仍由归档的 is_image 决定。 */
internal fun imageType(bytes: ByteArray): String {
    fun at(index: Int) = if (index < bytes.size) bytes[index].toInt() and 0xff else -1
    fun startsWith(vararg prefix: Int) = prefix.withIndex().all { (index, byte) -> at(index) == byte }
    val header = String(bytes, 0, minOf(12, bytes.size), Charsets.ISO_8859_1)
    return when {
        startsWith(0xff, 0xd8, 0xff) -> "image/jpeg"
        startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        header.startsWith("GIF87a") || header.startsWith("GIF89a") -> "image/gif"
        header.startsWith("RIFF") && header.length >= 12 && header.substring(8, 12) == "WEBP" -> "image/webp"
        startsWith(0x42, 0x4d) -> "image/bmp"
        startsWith(0x49, 0x49, 0x2a, 0) || startsWith(0x4d, 0x4d, 0, 0x2a) -> "image/tiff"
        header.length >= 12 && header.substring(4, 8) == "ftyp" && header.substring(8, 12) in setOf("avif", "avis") ->
            "image/avif"
        else -> ""
    }
}

/**
 * 流式导入，对照 importArchive.ts：manifest → user（不导入）→ 全部会话 JSON → index → 全部图片。
 * 也支持会话与图片交错排列、index 位于末尾的条目顺序。
 * 会话读完立即写入暂存表，只保留图片路径映射；图片全部读完后逐会话回填 MIME，不改变图片身份。
 * 全部校验通过后在一个事务中用暂存替换正式数据；失败时清理暂存，正式数据不变。
 */
internal class ArchiveImporter(
    private val db: AppDatabase,
    private val paths: AttachmentPaths,
    /** 仅测试：在换入原件前调用，抛出异常以模拟磁盘失败 */
    private val swapFault: () -> Unit = {},
) {
    private val staging: StagingDao = db.stagingDao()
    private val json = Json

    private fun readJson(entry: ArchiveZipReader.Entry): JsonElement {
        // 与 TextDecoder 一致：非法字节替换为 U+FFFD，去掉开头的 BOM。
        val text = entry.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            fail(HistoryErrorCodes.INVALID_JSON)
        } catch (_: IllegalArgumentException) {
            fail(HistoryErrorCodes.INVALID_JSON)
        }
    }

    /**
     * 检查 ZIP 格式、首个 manifest 条目及格式版本，返回已越过 manifest 的读取器。
     * 只读取，不触碰暂存与正式数据。输入流先复制为临时文件（关闭读取器时删除）。
     */
    fun open(input: InputStream): ArchiveZipReader {
        // 清理上次中断遗留的临时副本
        paths.importTempDir.listFiles()?.forEach { it.delete() }
        return check(ArchiveZipReader.spool(input, paths.importTempDir))
    }

    /** 与 [open] 相同，直接读取本地文件（如链接下载的缓存），不另做副本 */
    fun open(file: File): ArchiveZipReader = check(ArchiveZipReader(file))

    private fun check(reader: ArchiveZipReader): ArchiveZipReader {
        try {
            if (!reader.isZip) fail(HistoryErrorCodes.UNRECOGNIZED)
            val first = reader.next() ?: fail(HistoryErrorCodes.UNRECOGNIZED)
            if (first.path != ArchivePaths.MANIFEST) fail(HistoryErrorCodes.UNRECOGNIZED)
            ArchiveSchema.checkManifest(readJson(first))
            return reader
        } catch (error: Throwable) {
            runCatching { reader.close() }
            throw error
        }
    }

    /** 数据库已提交、图片原件尚未全部换入正式目录 */
    suspend fun swapPending(): Boolean = staging.state(StagingDao.STATE_FILES_SWAP_PENDING) != null

    /** 清空导入暂存：开始导入前清除上次中断遗留的数据，导入失败后清除本次写入。 */
    suspend fun clearStaging() {
        // 数据库已提交但原件尚未换入时，暂存目录属于已生效的导入，不能删除，留给 finishPendingSwap。
        if (staging.state(StagingDao.STATE_FILES_SWAP_PENDING) != null) return
        staging.clearStaging()
        paths.stagingDir.deleteRecursively()
    }

    /**
     * 提交后把暂存原件目录换入正式目录。数据库提交与目录交换不在同一事务中，
     * 以 import_state 标记衔接：进程在两者之间中断或换入失败时，下次调用会继续完成交换。
     * 标记分两个阶段，保证重试幂等：
     * - PENDING：旧原件尚未移走。移走后改为 MOVING；
     * - MOVING：正式目录里只有新导入已换入的原件（以及之后新增的附件），重试时只移动剩余暂存文件，不再清空正式目录。
     */
    suspend fun finishPendingSwap() {
        val state = staging.state(StagingDao.STATE_FILES_SWAP_PENDING) ?: return
        swapFault()
        if (state != StagingDao.SWAP_MOVING) {
            if (paths.stagingDir.exists()) {
                paths.oldDir.deleteRecursively()
                if (paths.dir.exists() && !paths.dir.renameTo(paths.oldDir)) {
                    paths.dir.deleteRecursively()
                }
            }
            staging.putState(ImportStateEntity(StagingDao.STATE_FILES_SWAP_PENDING, StagingDao.SWAP_MOVING))
        }
        if (paths.stagingDir.exists()) {
            if (paths.dir.exists() || !paths.stagingDir.renameTo(paths.dir)) {
                // 正式目录已存在（上次换入到一半）或改名失败时逐个移动。
                paths.dir.mkdirs()
                paths.stagingDir.listFiles()?.forEach { file ->
                    val target = File(paths.dir, file.name)
                    if (!file.renameTo(target)) {
                        file.copyTo(target, overwrite = true)
                        file.delete()
                    }
                }
                if (!paths.stagingDir.deleteRecursively()) throw IOException("无法清理导入暂存目录")
            }
        }
        paths.oldDir.deleteRecursively()
        paths.dir.mkdirs()
        staging.deleteState(StagingDao.STATE_FILES_SWAP_PENDING)
    }

    /** 从已越过 manifest 的读取器导入。调用方负责先清空暂存，失败时再次清空。 */
    suspend fun import(reader: ArchiveZipReader, onProgress: (ImportProgress) -> Unit): ImportResult {
        paths.stagingDir.mkdirs()
        val imported = LinkedHashSet<String>()
        val images = HashMap<String, String>()
        /** 已保存的原件：文件 ID → MIME。同一图片出现在多个会话目录时只保存一次。 */
        val stored = LinkedHashMap<String, String>()
        var index: List<SessionIndexEntry>? = null
        var currentSessionId: String? = null
        var messageCount = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = reader.next() ?: break
            val path = entry.path
            val sessionId = ArchivePaths.parseSession(path)
            when {
                // 账号信息（已脱敏）不导入；文件可能缺失。
                path == ArchivePaths.USER && index == null && imported.isEmpty() -> entry.skip()
                sessionId != null && index == null -> {
                    val local = ToLocal.toLocalSession(ArchiveSchema.readSessionFile(readJson(entry)))
                    if (local.session.id != sessionId || sessionId in imported) fail(HistoryErrorCodes.INCOMPLETE)
                    imported += sessionId
                    currentSessionId = sessionId
                    images.putAll(local.images)
                    stageSession(local)
                    messageCount += local.session.messages.size
                    onProgress(ImportProgress(imported.size, null))
                }
                path == ArchivePaths.INDEX && index == null -> {
                    index = ArchiveSchema.readSessionIndex(readJson(entry))
                    onProgress(ImportProgress(imported.size, index.size))
                }
                if (index != null) {
                    ArchivePaths.parseFilesDir(path)?.let { it in imported } == true
                } else {
                    currentSessionId != null && path.startsWith(ArchivePaths.sessionFilesDir(currentSessionId))
                } -> {
                    // 没有被引用的条目不保存，引用了但缺失的图片只展示文件名。
                    val id = images[path]
                    if (id == null || id in stored) entry.skip() else stored[id] = saveImage(entry, id)
                }
                else -> fail(HistoryErrorCodes.INCOMPLETE)
            }
        }

        // index 只列出成功写出的会话，与会话文件一一对应。
        val listed = index?.map { it.id }?.toSet()
        if (index == null || listed == null || listed.size != index.size || listed != imported) {
            fail(HistoryErrorCodes.INCOMPLETE)
        }
        updateFileTypes(stored)
        // 提交与原件换入不可被取消打断；提交后数据已生效，换入失败留待下次启动或导入导出前重试。
        withContext(NonCancellable) {
            staging.commit()
            runCatching { finishPendingSwap() }
        }
        return ImportResult(imported.size, messageCount, stored.size, imagesPending = swapPending())
    }

    private suspend fun stageSession(local: LocalSession) {
        val session = local.session
        db.withTransaction {
            staging.insertSession(
                StagingSessionEntity(session.id, session.title, session.createdAt, session.updatedAt, session.rootMessageId),
            )
            staging.insertMessages(
                session.messages.mapIndexed { seq, message ->
                    StagingMessageEntity(
                        sessionId = session.id,
                        id = message.id,
                        seq = seq,
                        createdAt = message.createdAt,
                        role = if (message is Message.User) "user" else "assistant",
                        json = DbJson.encode(message),
                    )
                },
            )
            val users = session.messages.filterIsInstance<Message.User>()
            staging.insertMessageFiles(
                users.flatMap { message -> message.files.map { StagingMessageFileEntity(session.id, message.id, it.id) } },
            )
            staging.insertFilesIfAbsent(
                users.flatMap { it.files }.map {
                    StagingFileEntity(it.id, it.serverId, it.name, it.size, it.isImage, it.mimeType)
                },
            )
        }
    }

    /** 整张写入暂存目录，同时取前 16 字节识别类型。 */
    private fun saveImage(entry: ArchiveZipReader.Entry, id: String): String {
        val header = ByteArray(16)
        var headerSize = 0
        val target = paths.stagingFile(id)
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = entry.read(buffer, 0, buffer.size)
                if (n < 0) break
                if (headerSize < header.size) {
                    val take = minOf(n, header.size - headerSize)
                    System.arraycopy(buffer, 0, header, headerSize, take)
                    headerSize += take
                }
                out.write(buffer, 0, n)
            }
        }
        return imageType(header.copyOf(headerSize))
    }

    /** 图片位于会话 JSON 之后；全部读完后回填具体 MIME，保留归档给出的 isImage 标识。 */
    private suspend fun updateFileTypes(types: Map<String, String>) {
        for ((id, type) in types) {
            if (type.isEmpty()) continue
            db.withTransaction {
                staging.setFileMimeType(id, type)
                for (ref in staging.referencesOf(id)) {
                    val row = staging.message(ref.sessionId, ref.messageId) ?: continue
                    val message = DbJson.decode(row.json) as? Message.User ?: continue
                    val updated = message.copy(files = message.files.map { if (it.id == id) it.copy(mimeType = type) else it })
                    if (updated != message) staging.updateMessageJson(row.sessionId, row.id, DbJson.encode(updated))
                }
            }
        }
    }
}
