package io.github.smalltailqwq.shallowdrop.data.api

import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind

/*
 * 接口失败的统一分类，移植自 src/services/api/errors.ts。
 * 各调用方按自己的场景给同一种情况配不同文案（例如余额不足在回答、上传里措辞不同），
 * 所以这里只归类，ApiException.code 存放下面的稳定字符串，由 UI 映射为本地化文案。
 */

/** ApiException.code 的取值。字符串与 Web 版 errors.ts 中的标识一致，便于对照。 */
object ApiErrorCodes {
    /** 401：回答中「API Key 无效或已被删除，请更换后重试」；校验 Key 时「API Key 无效，请检查后重试」 */
    const val UNAUTHORIZED = "unauthorized"

    /** 402：「账户余额不足，请充值后重试」 */
    const val INSUFFICIENT_BALANCE = "insufficientBalance"

    /** 413：请求体过大，没有专门文案，显示服务端原文 */
    const val TOO_LARGE = "tooLarge"

    /** 429：「请求过于频繁，请稍后再试」 */
    const val RATE_LIMITED = "rateLimited"

    /** 5xx：回答中「服务器繁忙，请稍后再试」；上传中「服务器繁忙」 */
    const val SERVER_BUSY = "serverBusy"

    /** 请求没有到达服务端：「网络异常，请检查你的网络状况」 */
    const val NETWORK = "network"

    /** 其余情况（含流错误帧、断流、协议错误）：直接显示 message；message 为空时不显示提示 */
    const val OTHER = "other"

    /** 400 附件 file_id 不存在：本地有原件时重新上传后重发；无法自愈时显示原文 */
    const val FILE_ID_MISSING = "fileIdMissing"

    /** 400 附件数据丢失：同上，重新上传后重发 */
    const val FILE_DATA_MISSING = "fileDataMissing"

    /** 400 file_id 格式非法：同上，重新上传后重发 */
    const val INVALID_FILE_ID = "invalidFileId"

    /** 上传 400 文件数达到上限：附件上「上传失败」，另提示「已上传文件数达到上限（{limit} 个），可删除文件」 */
    const val UPLOAD_FILE_COUNT_LIMIT = "fileCountLimit"

    /** 上传 400 存储空间达到上限：附件上「上传失败」，另提示「已上传文件占用空间达到上限（{limit}GB），可删除文件」 */
    const val UPLOAD_STORAGE_LIMIT = "storageLimit"

    /** 上传 400 服务端无法解析文件（损坏、过大、不支持、加密）：「解析失败」 */
    const val UPLOAD_PARSE_FAILED = "parseFailed"
}

/** 附件 file_id 失效的三种情况 */
private val FILE_ERROR_PATTERNS = listOf(
    ApiErrorCodes.FILE_ID_MISSING to Regex("file_ids do not exist or are not created under your account", RegexOption.IGNORE_CASE),
    ApiErrorCodes.FILE_DATA_MISSING to Regex("file data not found for file_id", RegexOption.IGNORE_CASE),
    ApiErrorCodes.INVALID_FILE_ID to Regex("\\.source\\.file_id: invalid file_id", RegexOption.IGNORE_CASE),
)

/** 服务端无法解析文件内容：图片损坏或尺寸过大、格式不受支持、加密、文档解析失败 */
private val PARSE_FAILED_PATTERNS = listOf(
    Regex("You have uploaded an (unsupported|invalid) (file|image)", RegexOption.IGNORE_CASE),
    Regex("The image is too large to process", RegexOption.IGNORE_CASE),
    Regex("password-protected", RegexOption.IGNORE_CASE),
    Regex("Failed to recognize the file", RegexOption.IGNORE_CASE),
)

private val FILE_COUNT_LIMIT = Regex("User file count limit exceeded.*?limit (\\d+)", RegexOption.IGNORE_CASE)
private val STORAGE_LIMIT = Regex("User storage quota exceeded.*?limit ([\\d.]+) GB", RegexOption.IGNORE_CASE)
private val FILE_ID = Regex("file-api-[\\w-]+")

/** 按 HTTP 状态码归类；没有状态码时为 OTHER（网络问题由 kind 判断） */
fun classifyStatus(status: Int?): String = when {
    status == 401 -> ApiErrorCodes.UNAUTHORIZED
    status == 402 -> ApiErrorCodes.INSUFFICIENT_BALANCE
    status == 413 -> ApiErrorCodes.TOO_LARGE
    status == 429 -> ApiErrorCodes.RATE_LIMITED
    (status ?: 0) >= 500 -> ApiErrorCodes.SERVER_BUSY
    else -> ApiErrorCodes.OTHER
}

/** 对应 classifyApiError：只看 kind 与 status，不看 code 中的附件 / 上传细分 */
fun classifyApiError(error: Throwable): String {
    if (error !is ApiException) return ApiErrorCodes.OTHER
    if (error.status == null) {
        return if (error.kind == ReplyErrorKind.Network) ApiErrorCodes.NETWORK else ApiErrorCodes.OTHER
    }
    return classifyStatus(error.status)
}

/** 重试可能成功的情况：网络、限流、服务端繁忙，以及非 ApiException 的异常；参数或鉴权类错误重试无用 */
fun isRetryableApiError(error: Throwable): Boolean {
    if (error !is ApiException) return true
    return when (classifyApiError(error)) {
        ApiErrorCodes.NETWORK, ApiErrorCodes.RATE_LIMITED, ApiErrorCodes.SERVER_BUSY -> true
        else -> false
    }
}

/**
 * 只识别「附件 file_id 失效」这一类 400：服务端查不到文件、文件数据丢失、file_id 格式非法。
 * 其余错误（限流、余额、purpose 不合规等）返回 null，保持原有失败提示。
 */
fun fileErrorCode(error: Throwable): String? {
    if (error !is ApiException || error.status != 400) return null
    return FILE_ERROR_PATTERNS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(error.message) }?.first
}

/** 从报错文案里取出服务端 file_id（去重），用于只重置真正失效的附件 */
fun extractFileIds(message: String): List<String> = FILE_ID.findAll(message).map { it.value }.distinct().toList()

/** 上传失败中能识别出的具体原因；limit 为配额上限数值（文件数或 GB） */
data class UploadErrorInfo(val code: String, val limit: String? = null)

/** 识别 Files API 上传的 400。单个 Key 与账号合计（across all sub keys）的配额共用同一提示。 */
fun uploadErrorInfo(error: Throwable): UploadErrorInfo? {
    if (error !is ApiException || error.status != 400) return null
    val message = error.message
    FILE_COUNT_LIMIT.find(message)?.let { return UploadErrorInfo(ApiErrorCodes.UPLOAD_FILE_COUNT_LIMIT, it.groupValues[1]) }
    STORAGE_LIMIT.find(message)?.let { return UploadErrorInfo(ApiErrorCodes.UPLOAD_STORAGE_LIMIT, it.groupValues[1]) }
    if (PARSE_FAILED_PATTERNS.any { it.containsMatchIn(message) }) {
        return UploadErrorInfo(ApiErrorCodes.UPLOAD_PARSE_FAILED)
    }
    return null
}

/** 生成 ApiException 时给出 code 的场景：上传接口优先识别上传原因，消息接口优先识别附件失效 */
internal enum class ErrorScope { Default, Messages, Upload }

internal fun apiException(
    kind: ReplyErrorKind,
    message: String,
    status: Int? = null,
    scope: ErrorScope = ErrorScope.Default,
): ApiException {
    val base = ApiException(kind, message, status)
    val refined = when (scope) {
        ErrorScope.Messages -> fileErrorCode(base)
        ErrorScope.Upload -> uploadErrorInfo(base)?.code
        ErrorScope.Default -> null
    }
    return ApiException(kind, message, status, refined ?: classifyApiError(base))
}
