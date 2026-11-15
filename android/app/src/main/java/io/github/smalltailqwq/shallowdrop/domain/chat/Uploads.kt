package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.FilePurpose
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind

/*
 * 附件补传相关规则，对照 src/services/attachments.ts 与 src/services/api/errors.ts 中被生成流程用到的部分。
 */

/** 文件扩展名（小写，不含点）；没有扩展名时为空 */
fun fileExt(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(dot + 1).lowercase() else ""
}

/** 需要服务端做版面解析的文档；其余非图片文件（纯文本、代码、表格、电子书）无需解析 */
private val LAYOUT_RECOGNITION_EXTS = setOf("pdf", "doc", "docx", "ppt", "pptx")

/** 上传时的 purpose：图片不传，PDF 与 Word、PPT 做版面解析，其余按 user_data 上传 */
fun uploadPurpose(name: String, isImage: Boolean): FilePurpose? = when {
    isImage -> null
    fileExt(name) in LAYOUT_RECOGNITION_EXTS -> FilePurpose.RichLayoutRecognition
    else -> FilePurpose.UserData
}

private val IMAGE_MIME_BY_EXT = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
)

/** 上传用的 MIME：附件记录的优先，缺失时按扩展名推断图片类型，仍未知用 octet-stream */
internal fun uploadMimeType(name: String, mimeType: String): String =
    mimeType.ifEmpty { IMAGE_MIME_BY_EXT[fileExt(name)] ?: "application/octet-stream" }

/** 重试可能成功的情况：网络、限流、服务端繁忙；参数或鉴权类错误重试无用。非 ApiException 视为可重试。 */
fun isRetryableApiError(error: Throwable): Boolean {
    if (error !is ApiException) return true
    val status = error.status ?: return error.kind == ReplyErrorKind.Network
    return status == 429 || status >= 500
}

/** 上传失败的本地化错误码（与 src/i18n 的 key 一致）：服务端繁忙、文件无法解析，其余统一「上传失败」。 */
object UploadErrorCode {
    const val SERVER_BUSY = "uploadServerBusy"
    const val PARSE_FAILED = "uploadParseFailed"
    const val FAILED = "uploadFileStatusFailed"
}

private val PARSE_FAILED_PATTERNS = listOf(
    Regex("You have uploaded an (unsupported|invalid) (file|image)", RegexOption.IGNORE_CASE),
    Regex("The image is too large to process", RegexOption.IGNORE_CASE),
    Regex("password-protected", RegexOption.IGNORE_CASE),
    Regex("Failed to recognize the file", RegexOption.IGNORE_CASE),
)

fun describeUploadError(error: Throwable): String {
    if (error is ApiException) {
        val status = error.status
        if (status != null && status >= 500) return UploadErrorCode.SERVER_BUSY
        if (status == 400 && PARSE_FAILED_PATTERNS.any { it.containsMatchIn(error.message) }) {
            return UploadErrorCode.PARSE_FAILED
        }
    }
    return UploadErrorCode.FAILED
}

/** 400 只带 message 文本，按文案区分「附件 file_id 失效」：查不到文件、文件数据丢失、file_id 非法 */
private val FILE_ERROR_PATTERNS = listOf(
    Regex("file_ids do not exist or are not created under your account", RegexOption.IGNORE_CASE),
    Regex("file data not found for file_id", RegexOption.IGNORE_CASE),
    Regex("\\.source\\.file_id: invalid file_id", RegexOption.IGNORE_CASE),
)

/** 只识别附件失效这一类 400；本地有原件时重新上传即可自愈 */
fun isFileInvalidatedError(error: Throwable): Boolean =
    error is ApiException && error.status == 400 && FILE_ERROR_PATTERNS.any { it.containsMatchIn(error.message) }

private val FILE_ID_PATTERN = Regex("file-api-[\\w-]+")

/** 从报错文案里取出服务端 file_id，用于只重置真正失效的附件 */
fun extractFileIds(message: String): List<String> =
    FILE_ID_PATTERN.findAll(message).map { it.value }.distinct().toList()
