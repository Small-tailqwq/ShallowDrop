package io.github.smalltailqwq.shallowdrop.data.history

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** ZIP 条目名，对照 paths.ts。条目顺序固定：manifest → user（可能缺失）→ 全部会话 JSON → index → 全部图片。 */
object ArchivePaths {
    const val MANIFEST = "manifest.json"
    const val USER = "user.json"
    const val INDEX = "chat_session_index.json"

    fun session(sessionId: String) = "chat_sessions/$sessionId.json"

    /** 会话自己的图片目录，带结尾斜杠 */
    fun sessionFilesDir(sessionId: String) = "chat_sessions/${sessionId}_files/"

    private val SESSION_PATH = Regex("^chat_sessions/([^/]+)\\.json$")
    private val SESSION_FILES = Regex("^chat_sessions/([^/]+)_files/")

    /** 会话 JSON 条目对应的会话 ID；其他条目返回 null */
    fun parseSession(path: String): String? = SESSION_PATH.find(path)?.groupValues?.get(1)

    /** 图片条目所在目录的会话 ID；其他条目返回 null */
    fun parseFilesDir(path: String): String? = SESSION_FILES.find(path)?.groupValues?.get(1)

    /** 安全路径片段：路径分隔符映射为 _，空值、. 与 .. 映射为 unnamed */
    private fun safeName(name: String): String {
        val result = name.replace('/', '_').replace('\\', '_')
        return if (result == "" || result == "." || result == "..") "unnamed" else result
    }

    /** chat_sessions/{session_id}_files/{file_id}_{file_name}；与 ZIP 读写端一致使用 NFC。 */
    fun image(sessionId: String, fileId: String, fileName: String) =
        nfc("${sessionFilesDir(sessionId)}${safeName(fileId)}_${safeName(fileName)}")
}

internal fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

/** 归档时间：以 Z 结尾、带可选小数秒的 UTC 时间，对照 schema.ts 的 time 与 toArchive.ts 的 iso。 */
internal object ArchiveTime {
    private val PATTERN = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?Z$")

    /** 与 JS Date#toISOString 相同：毫秒三位、Z 结尾 */
    private val ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** 校验并返回毫秒；不合法返回 null。小数秒补齐 / 截断到毫秒，与 Web 的 Date 精度一致。 */
    fun parseMillis(value: String): Long? {
        val match = PATTERN.matchEntire(value) ?: return null
        val base = try {
            LocalDateTime.parse(match.groupValues[1], DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (_: DateTimeParseException) {
            return null
        }
        val millis = match.groupValues[2].padEnd(3, '0').take(3).toLong()
        return base.toEpochSecond(ZoneOffset.UTC) * 1000 + millis
    }

    fun format(millis: Long): String = ISO.format(Instant.ofEpochMilli(millis))

    /**
     * 来源日期可能是任意格式的原始字符串，无法解析时返回 null（对照 toArchive.ts 的 isoOrNull）。
     * 接受 RFC 3339 / ISO 8601（含时区偏移）、无时区的日期时间（按 UTC）与纯日期。
     */
    fun normalizeOrNull(value: String?): String? {
        if (value == null) return null
        val text = value.trim()
        val millis = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching {
                java.time.LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
            ?: runCatching {
                ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
        return millis?.let(::format)
    }
}
