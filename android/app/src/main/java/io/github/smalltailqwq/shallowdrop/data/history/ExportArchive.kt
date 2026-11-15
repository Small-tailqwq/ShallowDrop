package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.data.db.AppDatabase
import io.github.smalltailqwq.shallowdrop.data.db.AttachmentPaths
import io.github.smalltailqwq.shallowdrop.data.db.readSession
import java.io.File
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.builtins.ListSerializer

/** 导出文件名 deepseek_data-yyyy-MM-dd.zip（本地日期），对照 backup.ts 的 backupFileName。 */
fun suggestedExportFileName(now: ZonedDateTime = ZonedDateTime.now()): String =
    "deepseek_data-${now.format(DateTimeFormatter.ofPattern("uuuu-MM-dd"))}.zip"

/**
 * 按导出格式生成 ZIP，对照 exportArchive.ts：manifest → 全部会话 JSON → index → 全部图片。
 * 没有账号信息，不写 user.json。只写出本地有原件的图片，每个会话目录各写一份；其他附件只有元数据。
 * 列表取导出开始时的快照，会话逐条从数据库读取；读取期间会话被删除时整次导出失败，标题采用快照。
 */
internal class ArchiveExporter(
    private val db: AppDatabase,
    private val paths: AttachmentPaths,
    notice: ReplyNotice,
) {
    private val toArchive = ToArchive(notice)

    private fun <T> json(serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        ArchiveJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

    suspend fun export(output: OutputStream): Int {
        val summaries = db.chatDao().summaries()
        val writer = ArchiveZipWriter(output)
        writer.add(ArchivePaths.MANIFEST, json(ArchiveManifest.serializer(), ArchiveManifest()))
        val imageEntries = ArrayList<Pair<String, File>>()
        val index = ArrayList<SessionIndexEntry>()
        val originals = HashMap<String, File?>()
        for (summary in summaries) {
            currentCoroutineContext().ensureActive()
            val session = db.readSession(summary.id)
                // 详情读取期间可能重命名，归档标题与开始时的列表保持一致。
                ?.copy(title = summary.title, createdAt = summary.createdAt, updatedAt = summary.updatedAt)
                ?: fail(HistoryErrorCodes.EXPORT_SESSION_MISSING)
            val images = LinkedHashMap<String, File>()
            val data = toArchive.toSessionFile(session) { file ->
                if (!file.isImage) return@toSessionFile null
                val original = originals.getOrPut(file.id) { paths.file(file.id).takeIf { it.isFile } }
                    ?: return@toSessionFile null
                ArchivePaths.image(session.id, file.id, file.name).also { images[it] = original }
            }
            writer.add(ArchivePaths.session(session.id), json(SessionFile.serializer(), data))
            images.forEach { (name, file) -> imageEntries += name to file }
            index += ToArchive.toIndexEntry(data)
        }
        writer.add(ArchivePaths.INDEX, json(ListSerializer(SessionIndexEntry.serializer()), index))
        for ((name, file) in imageEntries) {
            currentCoroutineContext().ensureActive()
            writer.add(name, file)
        }
        writer.finish()
        return summaries.size
    }
}
