package io.github.smalltailqwq.shallowdrop.data.history

import android.content.Context
import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryBackup
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryException
import io.github.smalltailqwq.shallowdrop.core.contract.ImageRestoreState
import io.github.smalltailqwq.shallowdrop.core.contract.ImportProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ImportResult
import io.github.smalltailqwq.shallowdrop.data.db.AppDatabase
import io.github.smalltailqwq.shallowdrop.data.db.AttachmentPaths
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 历史导入导出，格式见 src/services/chat/history/README.md（export_format_version 3）。
 * 导入、导出与 RoomSessionStore.deleteAll 经 AppDatabase.historyLock 串行执行。
 * 失败抛 HistoryException，code 见 HistoryErrorCodes。
 */
class ArchiveHistoryBackup(
    private val db: AppDatabase,
    private val paths: AttachmentPaths,
    private val openInput: (Uri) -> InputStream?,
    private val openOutput: (Uri) -> OutputStream?,
    notice: ReplyNotice = DefaultReplyNotice(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 仅测试：模拟图片原件换入失败 */
    swapFault: () -> Unit = {},
) : HistoryBackup {
    constructor(context: Context, db: AppDatabase, notice: ReplyNotice = DefaultReplyNotice()) : this(
        db = db,
        paths = AttachmentPaths(context.filesDir),
        openInput = { context.contentResolver.openInputStream(it) },
        // "wt" 截断已有内容；部分提供方不支持时退回 "w"。
        openOutput = {
            try {
                context.contentResolver.openOutputStream(it, "wt")
            } catch (_: IllegalArgumentException) {
                context.contentResolver.openOutputStream(it, "w")
            } catch (_: UnsupportedOperationException) {
                context.contentResolver.openOutputStream(it, "w")
            }
        },
        notice = notice,
    )

    private val importer = ArchiveImporter(db, paths, swapFault)

    private val imageRestoreState = MutableStateFlow(ImageRestoreState.Checking)

    /** 导入图片原件的恢复状态；启动恢复、导入、重试之后更新 */
    override val imageRestore: StateFlow<ImageRestoreState> = imageRestoreState.asStateFlow()

    private suspend fun refreshImageRestore(): Boolean {
        val pending = importer.swapPending()
        imageRestoreState.value = if (pending) ImageRestoreState.Pending else ImageRestoreState.Ready
        return !pending
    }

    /**
     * 尝试完成图片原件的换入并更新状态；失败不抛出，返回图片是否已全部就绪。
     * 发送消息前、设置页重试与应用启动都走这里。
     */
    override suspend fun retryImageRestore(): Boolean =
        db.historyLock.withLock {
            withContext(io) {
                try {
                    importer.finishPendingSwap()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // 保持 Pending，留待下次重试
                }
                refreshImageRestore()
            }
        }
    private val exporter = ArchiveExporter(db, paths, notice)

    /**
     * 只检查 ZIP 格式、首个 manifest 条目与版本，不修改任何数据。
     * 对应 Web 的 checkArchiveFormat：调用方可先检查，通过后再停止正在生成的回答并调用 importArchive。
     */
    suspend fun checkArchive(uri: Uri) = withContext(io) {
        mapErrors(HistoryErrorCodes.READ_FAILED) {
            val input = openInput(uri) ?: throw FileNotFoundException(uri.toString())
            importer.open(input).close()
        }
    }

    override suspend fun importArchive(uri: Uri, onProgress: (ImportProgress) -> Unit): ImportResult =
        importFrom({ openInput(uri) ?: throw FileNotFoundException(uri.toString()) }, onProgress)

    override suspend fun exportArchive(uri: Uri) {
        exportTo { openOutput(uri) ?: throw FileNotFoundException(uri.toString()) }
    }

    /**
     * 从任意输入流导入（importArchive 的实现，也便于测试）。
     * 先校验 manifest，通过后才清理遗留暂存并写入；任一步失败都清空暂存，正式数据不变。
     * onProgress 在 IO 线程回调。
     */
    suspend fun importFrom(open: () -> InputStream, onProgress: (ImportProgress) -> Unit = {}): ImportResult =
        db.historyLock.withLock {
            withContext(io) {
                mapErrors(HistoryErrorCodes.READ_FAILED) { importer.finishPendingSwap() }
                mapErrors(HistoryErrorCodes.READ_FAILED) {
                    importer.open(open()).use { reader ->
                        importer.clearStaging()
                        try {
                            importer.import(reader, onProgress).also { refreshImageRestore() }
                        } catch (error: Throwable) {
                            withContext(NonCancellable) { runCatching { importer.clearStaging() } }
                            throw error
                        }
                    }
                }
            }
        }

    /** 导出到任意输出流（exportArchive 的实现，也便于测试）；流由本方法关闭。返回导出的会话数。 */
    suspend fun exportTo(open: () -> OutputStream): Int =
        db.historyLock.withLock {
            withContext(io) {
                mapErrors(HistoryErrorCodes.READ_FAILED) { importer.finishPendingSwap() }
                mapErrors(HistoryErrorCodes.WRITE_FAILED) {
                    open().buffered().use { exporter.export(it) }
                }
            }
        }

    /**
     * 完成上次导入提交后中断的原件目录交换。导入导出前会自动调用；
     * 建议应用启动时调用一次，避免中断后首次展示时找不到导入图片的原件。
     */
    suspend fun recoverInterruptedImport() {
        db.historyLock.withLock {
            withContext(io) {
                try {
                    mapErrors(HistoryErrorCodes.STORAGE_FAILED) { importer.finishPendingSwap() }
                } finally {
                    withContext(NonCancellable) { refreshImageRestore() }
                }
            }
        }
    }

    private inline fun <T> mapErrors(ioCode: String, block: () -> T): T =
        try {
            block()
        } catch (error: HistoryException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: ArchiveFormatException) {
            throw historyError(HistoryErrorCodes.INCOMPLETE, error)
        } catch (error: ArchiveLimitException) {
            throw historyError(HistoryErrorCodes.EXPORT_TOO_LARGE, error)
        } catch (error: IOException) {
            throw historyError(ioCode, error)
        } catch (error: SecurityException) {
            throw historyError(ioCode, error)
        } catch (error: Exception) {
            throw historyError(HistoryErrorCodes.STORAGE_FAILED, error)
        }
}
