package io.github.smalltailqwq.shallowdrop.ui.fake

import android.content.Context
import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryBackup
import io.github.smalltailqwq.shallowdrop.core.contract.HistoryException
import io.github.smalltailqwq.shallowdrop.core.contract.ImageRestoreState
import io.github.smalltailqwq.shallowdrop.core.contract.ImportProgress
import io.github.smalltailqwq.shallowdrop.core.contract.ImportResult
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** 开发期导入导出：导入只模拟进度（非 .zip 名称时报 backupUnrecognized），导出写一个占位 ZIP。 */
class FakeHistoryBackup(context: Context) : HistoryBackup {
    private val appContext = context.applicationContext

    override suspend fun importArchive(uri: Uri, onProgress: (ImportProgress) -> Unit): ImportResult {
        val name = uri.lastPathSegment.orEmpty()
        if (!name.endsWith(".zip", ignoreCase = true)) {
            delay(300)
            throw HistoryException("backupUnrecognized", "not a zip: $name")
        }
        val total = 5
        for (done in 0..total) {
            onProgress(ImportProgress(done, total))
            delay(200)
        }
        return ImportResult(sessions = total, messages = 20, files = 0)
    }

    override val imageRestore: StateFlow<ImageRestoreState> = MutableStateFlow(ImageRestoreState.Ready)

    override suspend fun retryImageRestore(): Boolean = true

    override suspend fun exportArchive(uri: Uri) = withContext(Dispatchers.IO) {
        val output = appContext.contentResolver.openOutputStream(uri) ?: error("cannot open $uri")
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"export_format_version":3,"fake":true}""".toByteArray())
            zip.closeEntry()
        }
    }
}
