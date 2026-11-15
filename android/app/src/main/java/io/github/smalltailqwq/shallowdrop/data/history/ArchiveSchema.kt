package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.contract.HistoryException
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 按格式定义校验，对照 schema.ts：返回只含已定义字段的新对象，容器与元数据严格校验，无法解析的内容片段丢弃。
 * 可空字段必须显式为 null（缺省视为不合法）；可选字段允许缺省，存在时严格校验。
 * 不合法一律抛 HistoryException(INCOMPLETE)。
 */
internal object ArchiveSchema {
    private fun invalid(): Nothing = fail(HistoryErrorCodes.INCOMPLETE)

    private fun record(value: JsonElement?): JsonObject = value as? JsonObject ?: invalid()

    private fun primitive(value: JsonElement?): JsonPrimitive =
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: invalid()

    private fun string(value: JsonElement?): String = primitive(value).takeIf { it.isString }?.content ?: invalid()

    private fun boolean(value: JsonElement?): Boolean {
        val item = primitive(value)
        if (item.isString) invalid()
        return when (item.content) {
            "true" -> true
            "false" -> false
            else -> invalid()
        }
    }

    private fun decimal(value: JsonElement?): BigDecimal {
        val item = primitive(value)
        if (item.isString || item.content == "true" || item.content == "false") invalid()
        return try {
            BigDecimal(item.content)
        } catch (_: NumberFormatException) {
            invalid()
        }
    }

    /** 有限数值 */
    private fun number(value: JsonElement?): Double {
        val result = decimal(value).toDouble()
        return if (result.isFinite()) result else invalid()
    }

    /** 非负安全整数（u32 等），对应 Number.isSafeInteger(value) && value >= 0 */
    private fun count(value: JsonElement?): Long {
        val result = decimal(value)
        if (result.signum() < 0 || result.stripTrailingZeros().scale() > 0 || result > MAX_SAFE_INTEGER) invalid()
        return result.toLong()
    }

    private val MAX_SAFE_INTEGER = BigDecimal(9007199254740991L)

    private fun time(value: JsonElement?): String {
        val text = string(value)
        ArchiveTime.parseMillis(text) ?: invalid()
        // 返回原文，避免丢失微秒或纳秒。
        return text
    }

    private inline fun <T> nullable(value: JsonElement?, read: (JsonElement?) -> T): T? =
        if (value is JsonNull) null else read(value)

    private inline fun <T> list(value: JsonElement?, read: (JsonElement?) -> T): List<T> =
        (value as? JsonArray ?: invalid()).map { read(it) }

    private inline fun <reified T : Enum<T>> oneOf(value: JsonElement?): T {
        val text = string(value)
        return enumValues<T>().firstOrNull { it.name == text } ?: invalid()
    }

    /** 可选字段：缺省返回 null，存在时严格校验 */
    private inline fun <T> optional(item: JsonObject, key: String, read: (JsonElement?) -> T): T? =
        if (key in item) read(item[key]) else null

    private fun searchResult(value: JsonElement?): SearchResult {
        val item = record(value)
        return SearchResult(
            url = string(item["url"]),
            title = string(item["title"]),
            snippet = string(item["snippet"]),
            publishedAt = nullable(item["published_at"], ::time),
            siteName = nullable(item["site_name"], ::string),
            siteIcon = optional(item, "site_icon") { nullable(it, ::string) },
        )
    }

    private fun fileEntry(value: JsonElement?): FileEntry {
        val item = record(value)
        return FileEntry(
            id = string(item["id"]),
            fileName = string(item["file_name"]),
            fileSize = count(item["file_size"]),
            tokenUsage = nullable(item["token_usage"], ::count),
            insertedAt = time(item["inserted_at"]),
            exportPath = nullable(item["export_path"], ::string),
            isImage = boolean(item["is_image"]),
            width = nullable(item["width"], ::count),
            height = nullable(item["height"], ::count),
        )
    }

    private fun readFragment(value: JsonElement?): Fragment {
        val item = record(value)
        val stageId = { optional(item, "stage_id", ::count) }
        val groupId = { optional(item, "group_id", ::count) }
        return when (string(item["type"])) {
            "REQUEST" -> Fragment.Request(string(item["content"]))
            "RESPONSE" -> Fragment.Response(string(item["content"]))
            "TEMPLATE_RESPONSE" -> Fragment.TemplateResponse(string(item["content"]))
            "THINK" -> Fragment.Think(string(item["content"]), nullable(item["elapsed_secs"], ::number))
            "TIP" -> Fragment.Tip(string(item["content"]), oneOf<TipStyle>(item["style"]))
            "CONTENT_RISK" -> Fragment.ContentRisk(string(item["content"]), oneOf<TipStyle>(item["style"]))
            "PARTIAL_FILE_READ" -> {
                val ratio = number(item["ratio"])
                Fragment.PartialFileRead(if (ratio in 0.0..100.0) ratio else invalid())
            }
            "TOOL_SEARCH" -> Fragment.ToolSearch(
                queries = list(item["queries"], ::string),
                results = list(item["results"], ::searchResult),
                status = optional(item, "status") { oneOf<SearchStatus>(it) },
                stageId = stageId(),
                groupId = groupId(),
            )
            "TOOL_OPEN" -> Fragment.ToolOpen(
                status = oneOf<SearchStatus>(item["status"]),
                url = nullable(item["url"], ::string),
                title = nullable(item["title"], ::string),
                snippet = optional(item, "snippet") { nullable(it, ::string) },
                siteName = optional(item, "site_name") { nullable(it, ::string) },
                publishedAt = nullable(item["published_at"], ::time),
                stageId = stageId(),
                groupId = groupId(),
            )
            "TOOL_FIND" -> Fragment.ToolFind(
                status = oneOf<ToolFindStatus>(item["status"]),
                pattern = optional(item, "pattern", ::string),
                stageId = stageId(),
                groupId = groupId(),
            )
            "READ_LINK" -> Fragment.ReadLink(
                list(item["results"]) { result ->
                    val entry = record(result)
                    ReadLinkResult(
                        url = optional(entry, "url", ::string),
                        title = optional(entry, "title") { nullable(it, ::string) },
                        signature = string(entry["signature"]),
                    )
                },
            )
            "FILE" -> Fragment.File(list(item["files"], ::fileEntry))
            "TOOL_IMAGE_SEARCH" -> Fragment.ToolImageSearch(
                status = oneOf<SearchStatus>(item["status"]),
                imageIndexes = optional(item, "image_indexes") { list(it, ::count) },
                stageId = stageId(),
                groupId = groupId(),
                results = list(item["results"]) { result ->
                    val entry = record(result)
                    ImageSearchResult(
                        title = string(entry["title"]),
                        sourcePageUrl = string(entry["source_page_url"]),
                        snippet = string(entry["snippet"]),
                        siteName = nullable(entry["site_name"], ::string),
                        publishedAt = nullable(entry["published_at"], ::time),
                    )
                },
            )
            else -> invalid()
        }
    }

    /** 单个内容片段不兼容不影响其余消息；容器、元数据和后续消息树校验仍严格执行。 */
    private fun fragment(value: JsonElement?): Fragment? =
        try {
            readFragment(value)
        } catch (error: HistoryException) {
            if (error.code == HistoryErrorCodes.INCOMPLETE) null else throw error
        }

    private fun message(value: JsonElement?): ChatMessage {
        val item = record(value)
        return ChatMessage(
            id = count(item["id"]),
            parentId = nullable(item["parent_id"], ::count),
            role = oneOf<ChatRole>(item["role"]),
            status = oneOf<ChatMessageStatus>(item["status"]),
            thinkingEnabled = boolean(item["thinking_enabled"]),
            searchEnabled = boolean(item["search_enabled"]),
            accumulatedTokenUsage = count(item["accumulated_token_usage"]),
            insertedAt = time(item["inserted_at"]),
            fragments = list(item["fragments"], ::fragment).filterNotNull(),
        )
    }

    /** 会话 ID 用于拼接条目名，不能含路径分隔符 */
    private fun sessionId(value: JsonElement?): String {
        val id = string(value)
        return if (id.isNotEmpty() && '/' !in id && '\\' !in id && id != "." && id != "..") id else invalid()
    }

    /** 版本号用于校验归档格式兼容性；字段结构由各字段的校验函数保证。 */
    fun checkManifest(value: JsonElement) {
        val version = record(value)["export_format_version"]
        val number = (version as? JsonPrimitive)
            ?.takeIf { it !is JsonNull && !it.isString && it.content != "true" && it.content != "false" }
            ?.content
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
        if (number != null && number > BigDecimal(EXPORT_FORMAT_VERSION)) fail(HistoryErrorCodes.NEWER_VERSION)
        if (number == null || number.compareTo(BigDecimal(EXPORT_FORMAT_VERSION)) != 0) {
            fail(HistoryErrorCodes.INVALID_VERSION)
        }
    }

    fun readSessionIndex(value: JsonElement): List<SessionIndexEntry> =
        list(value) {
            val item = record(it)
            SessionIndexEntry(
                id = sessionId(item["id"]),
                title = nullable(item["title"], ::string),
                pinned = false,
                messageCount = count(item["message_count"]),
                insertedAt = time(item["inserted_at"]),
                updatedAt = time(item["updated_at"]),
            )
        }

    fun readSessionFile(value: JsonElement): SessionFile {
        val item = record(value)
        return SessionFile(
            id = sessionId(item["id"]),
            title = nullable(item["title"], ::string),
            pinned = false,
            currentMessageId = count(item["current_message_id"]),
            insertedAt = time(item["inserted_at"]),
            updatedAt = time(item["updated_at"]),
            messages = list(item["messages"], ::message),
        )
    }
}
