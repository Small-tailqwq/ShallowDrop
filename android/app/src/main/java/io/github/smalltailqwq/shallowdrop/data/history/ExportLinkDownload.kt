package io.github.smalltailqwq.shallowdrop.data.history

import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.contract.DownloadProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ExportLinkDownloader
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 下载官方 App 导出链接得到的 ZIP，存于 [dir]（应用缓存下的独立目录），用完即删。
 *
 * - 只接受 https，且不跟随降级到 http 的重定向；请求不带任何凭据（链接本身是签名地址）。
 * - 服务端 4xx 或响应不是 ZIP（过期链接常返回 200 的 HTML / XML 错误页）视为链接失效。
 * - 内容校验交给后续导入：这里只确认 ZIP 头，格式与版本由 ArchiveImporter 检查。
 */
class OkHttpExportLinkDownloader(
    client: OkHttpClient,
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 仅测试替换：MockWebServer 只提供 http */
    private val resolve: (String) -> HttpUrl? = { parseExportLink(it)?.toHttpUrlOrNull() },
) : ExportLinkDownloader {
    private val client = client.newBuilder().followSslRedirects(false).build()

    override fun parse(text: String): String? = parseExportLink(text)

    override suspend fun <T> download(
        link: String,
        onProgress: (DownloadProgress) -> Unit,
        use: suspend (Uri) -> T,
    ): T {
        val url = resolve(link) ?: fail(HistoryErrorCodes.LINK_INVALID)
        val file = fetch(url, onProgress)
        try {
            return use(Uri.fromFile(file))
        } finally {
            withContext(NonCancellable + io) { file.delete() }
        }
    }

    private suspend fun fetch(url: HttpUrl, onProgress: (DownloadProgress) -> Unit): File = coroutineScope {
        val call = client.newCall(Request.Builder().url(url).build())
        // 取消时关闭连接，让阻塞中的读取立即抛出
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(io) {
                prepareDir()
                val file = File(dir, "export-${System.currentTimeMillis()}.zip")
                try {
                    val response = try {
                        call.execute()
                    } catch (error: IOException) {
                        ensureActive()
                        throw historyError(HistoryErrorCodes.DOWNLOAD_FAILED, error)
                    }
                    response.use {
                        if (!it.isSuccessful) {
                            fail(if (it.code in 400..499) HistoryErrorCodes.LINK_EXPIRED else HistoryErrorCodes.DOWNLOAD_FAILED)
                        }
                        val body = it.body
                        val total = body.contentLength().takeIf { length -> length >= 0 }
                        try {
                            body.byteStream().use { input ->
                                file.outputStream().use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var bytes = 0L
                                    var reported = 0L
                                    onProgress(DownloadProgress(0, total))
                                    while (true) {
                                        ensureActive()
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        // 写入失败（通常是空间不足）单独报存储错误，不当作下载失败
                                        try {
                                            output.write(buffer, 0, read)
                                        } catch (error: IOException) {
                                            throw historyError(HistoryErrorCodes.STORAGE_FAILED, error)
                                        }
                                        bytes += read
                                        if (bytes - reported >= PROGRESS_STEP) {
                                            reported = bytes
                                            onProgress(DownloadProgress(bytes, total))
                                        }
                                    }
                                    onProgress(DownloadProgress(bytes, total))
                                }
                            }
                        } catch (error: IOException) {
                            ensureActive()
                            throw historyError(HistoryErrorCodes.DOWNLOAD_FAILED, error)
                        }
                    }
                    if (!isZip(file)) fail(HistoryErrorCodes.LINK_EXPIRED)
                    file
                } catch (error: Throwable) {
                    file.delete()
                    throw error
                }
            }
        } finally {
            guard.cancel()
        }
    }

    /** 清理上次中断遗留的临时文件 */
    private fun prepareDir() {
        if (!dir.isDirectory && !dir.mkdirs()) fail(HistoryErrorCodes.STORAGE_FAILED)
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP = 256 * 1024L
    }
}

/** 本地文件头（PK\3\4）或空归档的中央目录结束记录（PK\5\6） */
internal fun isZip(file: File): Boolean {
    if (file.length() < 4) return false
    val head = ByteArray(4)
    file.inputStream().use { if (it.read(head) != 4) return false }
    return head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
        ((head[2] == 3.toByte() && head[3] == 4.toByte()) || (head[2] == 5.toByte() && head[3] == 6.toByte()))
}

private val LINK_PATTERN = Regex("""https://[^\s<>"'，。、；：！？（）「」『』【】《》]+""", RegexOption.IGNORE_CASE)
private const val TRAILING_PUNCTUATION = ".,;:!?)]}>'\""

/**
 * 取出文本中第一个 https 链接：官方 App 复制的是纯链接，这里也容忍前后带说明文字或标点。
 * 无法解析出主机名时返回 null。
 */
internal fun parseExportLink(text: String): String? {
    val match = LINK_PATTERN.find(text) ?: return null
    val link = match.value.trimEnd { it in TRAILING_PUNCTUATION }
    val url = link.toHttpUrlOrNull() ?: return null
    return link.takeIf { url.isHttps && url.host.isNotEmpty() }
}
