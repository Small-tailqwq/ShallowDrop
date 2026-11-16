package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.contract.HistoryException

/**
 * HistoryException.code 的取值，对照 src/services/chat/history/errors.ts 使用的 i18n key，
 * 前七项与 Web 版 key 完全相同，UI 据此映射文案。注释为 zh.ts 中的提示。
 */
object HistoryErrorCodes {
    /** 文件不是有效的 JSON */
    const val INVALID_JSON = "backupInvalidJson"

    /** 无法识别该文件，请选择 DeepSeek 导出的历史对话 ZIP 文件 */
    const val UNRECOGNIZED = "backupUnrecognized"

    /** 文件版本过新，请升级后再导入 */
    const val NEWER_VERSION = "backupNewerVersion"

    /** 文件版本无效，无法导入 */
    const val INVALID_VERSION = "backupInvalidVersion"

    /** 文件内容不完整，无法导入（ZIP 损坏、字段不合法、索引与会话不对应等） */
    const val INCOMPLETE = "backupIncomplete"

    /** 文件中的消息分支已损坏，无法导入 */
    const val INVALID_BRANCHES = "backupInvalidBranches"

    /** 文件中的附件数据已损坏，无法导入 */
    const val INVALID_FILES = "backupInvalidFiles"

    // ---------- Android 新增，Web 版没有对应 key ----------

    /** 无法读取所选文件（Uri 无法打开或读取中断），请重新选择 */
    const val READ_FAILED = "backupReadFailed"

    /** 无法写入所选位置，请重新选择保存位置 */
    const val WRITE_FAILED = "backupWriteFailed"

    /** 本地存储失败（数据库或磁盘空间），原有数据未改变 */
    const val STORAGE_FAILED = "backupStorageFailed"

    /** 历史记录超出 ZIP32 容量（条目数或大小），无法导出 */
    const val EXPORT_TOO_LARGE = "backupExportTooLarge"

    /** 导出期间会话被删除，请重试 */
    const val EXPORT_SESSION_MISSING = "backupExportSessionMissing"

    /** 不是有效的下载链接 */
    const val LINK_INVALID = "backupLinkInvalid"

    /** 下载链接已失效（服务端 4xx 或返回的不是 ZIP），请在 DeepSeek App 中重新导出 */
    const val LINK_EXPIRED = "backupLinkExpired"

    /** 下载失败（网络或服务端 5xx），请检查网络后重试 */
    const val DOWNLOAD_FAILED = "backupDownloadFailed"

    internal val MESSAGES = mapOf(
        INVALID_JSON to "文件不是有效的 JSON",
        UNRECOGNIZED to "无法识别该文件，请选择 DeepSeek 导出的历史对话 ZIP 文件",
        NEWER_VERSION to "文件版本过新，请升级后再导入",
        INVALID_VERSION to "文件版本无效，无法导入",
        INCOMPLETE to "文件内容不完整，无法导入",
        INVALID_BRANCHES to "文件中的消息分支已损坏，无法导入",
        INVALID_FILES to "文件中的附件数据已损坏，无法导入",
        READ_FAILED to "无法读取所选文件",
        WRITE_FAILED to "无法写入所选位置",
        STORAGE_FAILED to "本地存储失败，原有数据未改变",
        EXPORT_TOO_LARGE to "历史记录过大，无法导出",
        EXPORT_SESSION_MISSING to "导出期间会话被删除，请重试",
        LINK_INVALID to "不是有效的下载链接",
        LINK_EXPIRED to "下载链接已失效",
        DOWNLOAD_FAILED to "下载失败",
    )
}

internal fun historyError(code: String, cause: Throwable? = null) =
    HistoryException(code, HistoryErrorCodes.MESSAGES[code] ?: code, cause)

internal fun fail(code: String): Nothing = throw historyError(code)
