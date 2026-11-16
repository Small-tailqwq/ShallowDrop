package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.contract.DownloadProgress
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 官方 App 导出链接：解析粘贴文本、下载、失效判定与临时文件清理。 */
@RunWith(RobolectricTestRunner::class)
class ExportLinkDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var downloader: OkHttpExportLinkDownloader

    private val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(600_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("history-link").toFile()
        downloader = OkHttpExportLinkDownloader(OkHttpClient(), dir, Dispatchers.IO) { it.toHttpUrlOrNull() }
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun link() = server.url("/export/deepseek_data.zip?sign=abc").toString()

    private fun respond(code: Int, body: ByteArray, type: String = "application/zip") {
        server.enqueue(MockResponse.Builder().code(code).setHeader("Content-Type", type).body(Buffer().write(body)).build())
    }

    private fun expectCode(code: String, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("应抛出 $code")
        } catch (error: HistoryException) {
            assertEquals(code, error.code)
        }
    }

    @Test
    fun parsesLinkFromPastedText() {
        val url = "https://cdn.deepseek.com/export/abc.zip?Expires=1&Signature=x%2By"
        assertEquals(url, parseExportLink(url))
        assertEquals(url, parseExportLink("  $url\n"))
        assertEquals(url, parseExportLink("下载链接：$url。"))
        assertEquals(url, parseExportLink("($url)"))
        assertNull(parseExportLink("http://example.com/a.zip"))
        assertNull(parseExportLink("deepseek_data.zip"))
        assertNull(parseExportLink(""))
    }

    @Test
    fun downloadsZipAndDeletesItAfterUse() = runBlocking {
        respond(200, zipBytes)
        val progress = mutableListOf<DownloadProgress>()
        var seen: File? = null
        val size = downloader.download(link(), { progress += it }) { uri ->
            val file = File(requireNotNull(uri.path))
            seen = file
            assertArrayEquals(zipBytes, file.readBytes())
            file.length()
        }
        assertEquals(zipBytes.size.toLong(), size)
        assertFalse("用完应删除临时文件", seen!!.exists())
        assertEquals(DownloadProgress(zipBytes.size.toLong(), zipBytes.size.toLong()), progress.last())
        assertTrue(progress.size >= 3)
        // 不带任何凭据
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun fileIsDeletedWhenImportFails() = runBlocking {
        respond(200, zipBytes)
        try {
            downloader.download(link(), {}) { error("导入失败") }
            fail("应抛出")
        } catch (_: IllegalStateException) {
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun clientErrorMeansExpiredLink() = expectCode(HistoryErrorCodes.LINK_EXPIRED) {
        respond(403, "<Error><Code>AccessDenied</Code></Error>".toByteArray(), "application/xml")
        downloader.download(link(), {}) { }
    }

    @Test
    fun nonZipBodyMeansExpiredLink() {
        expectCode(HistoryErrorCodes.LINK_EXPIRED) {
            respond(200, "<html>链接已过期</html>".toByteArray(), "text/html")
            downloader.download(link(), {}) { }
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun serverErrorIsDownloadFailure() = expectCode(HistoryErrorCodes.DOWNLOAD_FAILED) {
        respond(503, ByteArray(0))
        downloader.download(link(), {}) { }
    }

    @Test
    fun rejectsTextWithoutHttpsLink() = runBlocking {
        val strict = OkHttpExportLinkDownloader(OkHttpClient(), dir)
        try {
            strict.download(link(), {}) { }
            fail("http 链接应被拒绝")
        } catch (error: HistoryException) {
            assertEquals(HistoryErrorCodes.LINK_INVALID, error.code)
        }
        assertEquals(0, server.requestCount)
    }
}
