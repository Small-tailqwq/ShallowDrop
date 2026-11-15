package io.github.smalltailqwq.shallowdrop.data.history

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 用代码合成官方导出格式的归档，字段与 format.ts 一致。 */
object Fx {
    fun at(second: Int) = "2026-11-11T02:%02d:%02d.000Z".format(second / 60, second % 60)

    private fun str(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) put(key, any(value))
    }

    private fun any(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::any))
        else -> error("unsupported $value")
    }

    fun request(text: String) = obj("type" to "REQUEST", "content" to text)

    fun response(text: String) = obj("type" to "RESPONSE", "content" to text)

    fun template(text: String) = obj("type" to "TEMPLATE_RESPONSE", "content" to text)

    fun think(text: String, secs: Double?) = obj("type" to "THINK", "content" to text, "elapsed_secs" to secs)

    fun tip(text: String) = obj("type" to "TIP", "content" to text, "style" to "INFO")

    fun searchResult(url: String, title: String, publishedAt: String? = null, siteIcon: String? = null) = obj(
        "url" to url,
        "title" to title,
        "snippet" to "摘要",
        "published_at" to publishedAt,
        "site_name" to "站点",
        "site_icon" to siteIcon,
    )

    fun toolSearch(queries: List<String>, results: List<JsonObject>, status: String? = "FINISHED") = buildJsonObject {
        put("type", "TOOL_SEARCH")
        put("queries", JsonArray(queries.map(::JsonPrimitive)))
        put("results", JsonArray(results))
        if (status != null) put("status", status)
    }

    fun toolOpen(url: String?, title: String?, status: String = "FINISHED", publishedAt: String? = null) = obj(
        "type" to "TOOL_OPEN",
        "status" to status,
        "url" to url,
        "title" to title,
        "snippet" to "",
        "site_name" to null,
        "published_at" to publishedAt,
    )

    fun toolFind(pattern: String, status: String = "FINISHED") =
        obj("type" to "TOOL_FIND", "status" to status, "pattern" to pattern)

    fun readLink(vararg results: JsonObject) = obj("type" to "READ_LINK", "results" to results.toList())

    fun readLinkResult(url: String?, title: String?, signature: String) = buildJsonObject {
        if (url != null) put("url", url)
        put("title", str(title))
        put("signature", signature)
    }

    fun fileEntry(id: String, name: String, size: Int, isImage: Boolean, exportPath: String?) = obj(
        "id" to id,
        "file_name" to name,
        "file_size" to size,
        "token_usage" to null,
        "inserted_at" to at(0),
        "export_path" to exportPath,
        "is_image" to isImage,
        "width" to null,
        "height" to null,
    )

    fun files(vararg entries: JsonObject) = obj("type" to "FILE", "files" to entries.toList())

    fun message(
        id: Int,
        parent: Int?,
        role: String,
        fragments: List<JsonObject>,
        status: String = "FINISHED",
        thinking: Boolean = false,
        search: Boolean = false,
    ) = obj(
        "id" to id,
        "parent_id" to parent,
        "role" to role,
        "status" to status,
        "thinking_enabled" to thinking,
        "search_enabled" to search,
        "accumulated_token_usage" to 0,
        "inserted_at" to at(id),
        "fragments" to fragments,
    )

    fun user(id: Int, parent: Int?, text: String, vararg extra: JsonObject) =
        message(id, parent, "USER", extra.toList() + request(text))

    fun assistant(id: Int, parent: Int, vararg fragments: JsonObject, status: String = "FINISHED") =
        message(id, parent, "ASSISTANT", fragments.toList(), status)

    fun session(id: String, title: String?, current: Int, messages: List<JsonObject>) = obj(
        "id" to id,
        "title" to title,
        "pinned" to false,
        "current_message_id" to current,
        "inserted_at" to at(0),
        "updated_at" to at(100),
        "messages" to messages,
    )

    fun index(vararg sessions: JsonObject) = buildJsonArray {
        for (session in sessions) {
            add(
                obj(
                    "id" to session["id"],
                    "title" to session["title"],
                    "pinned" to false,
                    "message_count" to (session["messages"] as JsonArray).size,
                    "inserted_at" to session["inserted_at"],
                    "updated_at" to session["updated_at"],
                ),
            )
        }
    }

    fun manifest(version: Any = 3) = obj("export_format_version" to version)

    /** ZIP 条目：值为 JsonElement（写成 JSON）、String 或 ByteArray；stored 为 true 时使用 STORE。 */
    fun zip(vararg entries: Pair<String, Any>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.UTF_8).use { zip ->
            for ((name, value) in entries) {
                val bytes = when (value) {
                    is JsonElement -> value.toString().toByteArray()
                    is String -> value.toByteArray()
                    is ByteArray -> value
                    else -> error("unsupported $value")
                }
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4)
    val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 5, 6)
}
