package io.github.smalltailqwq.shallowdrop.data.api

import io.github.smalltailqwq.shallowdrop.core.model.ContentBlockDelta
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.MessageUsage
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.StopDetails
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import io.github.smalltailqwq.shallowdrop.core.model.WebSearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * 流式事件解析，移植自 src/services/api/parseEvent.ts。
 * 只检查消费内容所需的字段，签名及服务端附带的信息保持原样；
 * 未识别或无法安全消费的事件静默丢弃（返回 null），不影响后续有效内容与正常结束。
 */

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/** 是字符串时返回内容，否则 null */
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.isString() = str() != null

/** 缺省或字符串 */
private fun JsonElement?.isOptionalString() = this == null || isString()

/** 缺省、null 或字符串 */
private fun JsonElement?.isNullableString() = this == null || this is JsonNull || isString()

/** 非负安全整数 */
private fun JsonElement?.index(): Int? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString) return null
    val n = p.intOrNull ?: return null
    return n.takeIf { it >= 0 }
}

private val SERVER_TOOL_USE_KINDS = setOf("search", "deep_search", "read_link", "image_search", "deep_image_search")
private val SERVER_TOOL_NAMES = setOf("deepseek_web_search", "deepseek_read_url")
private val WEB_SEARCH_KINDS = setOf("search", "deep_search", "image_search", "deep_image_search")
private val WEB_FETCH_KINDS = setOf("read_link", "deep_search")
private val USAGE_KEYS = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens")

private fun toolKind(value: String): ServerToolKind = when (value) {
    "search" -> ServerToolKind.Search
    "deep_search" -> ServerToolKind.DeepSearch
    "read_link" -> ServerToolKind.ReadLink
    "image_search" -> ServerToolKind.ImageSearch
    else -> ServerToolKind.DeepImageSearch
}

private fun isCitation(value: JsonElement?): Boolean {
    val o = value.obj() ?: return false
    return o["type"].str() == "web_search_result_location" && o["url"].isString() && o["title"].isNullableString()
}

/** 把服务端事件解析为 MessageStreamEvent；json 需开启 ignoreUnknownKeys 与 coerceInputValues */
internal class MessageEventReader(private val json: Json) {

    fun read(value: JsonElement?): MessageStreamEvent? {
        val o = value.obj() ?: return null
        return when (o["type"].str()) {
            "message_start" -> {
                val message = o["message"].obj() ?: return null
                MessageStreamEvent.MessageStart(
                    id = message["id"].str() ?: "",
                    model = message["model"].str() ?: "",
                    usage = usage(message["usage"]),
                )
            }
            "content_block_start" -> {
                val index = o["index"].index() ?: return null
                val raw = o["content_block"].obj() ?: return null
                val block = readBlock(raw) ?: return null
                MessageStreamEvent.ContentBlockStart(index, block.getOrNull(), raw)
            }
            "content_block_delta" -> {
                val index = o["index"].index() ?: return null
                val delta = readDelta(o["delta"]) ?: return null
                MessageStreamEvent.ContentBlockDeltaEvent(index, delta)
            }
            "content_block_stop" -> MessageStreamEvent.ContentBlockStop(o["index"].index() ?: return null)
            "message_delta" -> {
                val delta = o["delta"].obj() ?: return null
                val stopReason = when (delta["stop_reason"].str()) {
                    "end_turn" -> StopReason.EndTurn
                    "max_tokens" -> StopReason.MaxTokens
                    "tool_use" -> StopReason.ToolUse
                    "refusal" -> StopReason.Refusal
                    else -> return null
                }
                MessageStreamEvent.MessageDelta(
                    stopReason = stopReason,
                    stopDetails = delta["stop_details"].obj()?.let { decodeOrNull(StopDetails.serializer(), it) },
                    title = delta["title"].takeIf { it.isOptionalString() }.str(),
                    usage = usage(o["usage"]),
                )
            }
            "message_stop" -> MessageStreamEvent.MessageStop
            // ping 及其余事件不产出
            else -> null
        }
    }

    private fun usage(value: JsonElement?): MessageUsage? {
        val o = value.obj() ?: return null
        val valid = USAGE_KEYS.all { key ->
            val p = o[key] as? JsonPrimitive
            p != null && !p.isString && p.doubleOrNull?.isFinite() == true
        }
        return if (valid) decodeOrNull(MessageUsage.serializer(), o) else null
    }

    /**
     * 外层 null 表示整条事件丢弃；Result 内为 null 表示能识别但不转换的块（客户端 tool_use），
     * 事件照常产出、block 为 null，交给回复归并层关闭这条回复的续写能力。
     */
    private fun readBlock(o: JsonObject): Result<ReplyBlock?>? = when (o["type"].str()) {
        "text" -> {
            val text = o["text"].str()
            if (text == null) {
                null
            } else {
                val citations = (o["citations"] as? JsonArray)
                    ?.filter(::isCitation)
                    ?.mapNotNull { decodeOrNull(TextCitation.serializer(), it) }
                    .orEmpty()
                Result.success(ReplyBlock.Text(text, citations))
            }
        }
        "thinking" -> {
            val thinking = o["thinking"].str()
            if (thinking == null) {
                null
            } else {
                Result.success(ReplyBlock.Thinking(thinking, signature = o["signature"].str()))
            }
        }
        "server_tool_use" -> serverToolUse(o)?.let { Result.success(it) }
        "web_search_tool_result" -> webSearchResult(o)?.let { Result.success(it) }
        "web_fetch_tool_result" -> webFetchResult(o)?.let { Result.success(it) }
        // 不展示客户端工具内容
        "tool_use" -> Result.success(null)
        else -> null
    }

    private fun serverToolUse(o: JsonObject): ReplyBlock.ServerToolUse? {
        val id = o["id"].str() ?: return null
        val kind = o["kind"].str()?.takeIf { it in SERVER_TOOL_USE_KINDS } ?: return null
        val name = o["name"].str()?.takeIf { it in SERVER_TOOL_NAMES } ?: return null
        val input = o["input"].obj() ?: return null
        val valid = when (input["action"].str()) {
            "search" -> (input["queries"] as? JsonArray)?.all { it.isString() } == true
            "open_page", "read_link" -> input["url"].isString()
            "find_in_page" -> input["url"].isString() && input["pattern"].isString()
            else -> false
        }
        if (!valid) return null
        val parsed = decodeOrNull(ServerToolInput.serializer(), input) ?: return null
        return ReplyBlock.ServerToolUse(id = id, name = name, input = parsed, kind = toolKind(kind))
    }

    private fun webSearchResult(o: JsonObject): ReplyBlock.WebSearchToolResult? {
        val toolUseId = o["tool_use_id"].str() ?: return null
        val kind = o["kind"].str()?.takeIf { it in WEB_SEARCH_KINDS } ?: return null
        val content = o["content"] as? JsonArray ?: return null
        var errorCode: String? = null
        val results = mutableListOf<WebSearchResult>()
        for (item in content) {
            val entry = item.obj() ?: continue
            when (entry["type"].str()) {
                "web_search_tool_result_error" -> entry["error_code"].str()?.let { errorCode = errorCode ?: it }
                "web_search_result" -> {
                    val valid = entry["url"].isString() && entry["title"].isString() &&
                        entry["snippet"].isNullableString() && entry["site_name"].isNullableString() &&
                        entry["site_icon"].isNullableString() && entry["page_age"].isNullableString()
                    if (valid) decodeOrNull(WebSearchResult.serializer(), entry)?.let(results::add)
                }
            }
        }
        return ReplyBlock.WebSearchToolResult(toolUseId, results, errorCode, toolKind(kind))
    }

    private fun webFetchResult(o: JsonObject): ReplyBlock.WebFetchToolResult? {
        val toolUseId = o["tool_use_id"].str() ?: return null
        val kind = o["kind"].str()?.takeIf { it in WEB_FETCH_KINDS } ?: return null
        val content = o["content"].obj() ?: return null
        val signature = o["signature"].takeIf { it.isOptionalString() }.str()
        return when (content["type"].str()) {
            "web_fetch_tool_result_error" -> {
                val errorCode = content["error_code"].str() ?: return null
                ReplyBlock.WebFetchToolResult(
                    toolUseId = toolUseId,
                    errorCode = errorCode,
                    kind = toolKind(kind),
                    signature = signature,
                    raw = content,
                )
            }
            "web_fetch_result" -> {
                val url = content["url"].str() ?: return null
                val document = content["content"].obj() ?: return null
                if (!document["title"].isNullableString() || !content["published_at"].isNullableString()) return null
                ReplyBlock.WebFetchToolResult(
                    toolUseId = toolUseId,
                    url = url,
                    title = document["title"].str(),
                    publishedAt = content["published_at"].str(),
                    kind = toolKind(kind),
                    signature = signature,
                    raw = content,
                )
            }
            else -> null
        }
    }

    private fun readDelta(value: JsonElement?): ContentBlockDelta? {
        val o = value.obj() ?: return null
        return when (o["type"].str()) {
            "text_delta" -> o["text"].str()?.let { ContentBlockDelta.TextDelta(it) }
            "thinking_delta" -> o["thinking"].str()?.let { ContentBlockDelta.ThinkingDelta(it) }
            "signature_delta" -> o["signature"].str()?.let { ContentBlockDelta.SignatureDelta(it) }
            "citations_delta" -> o["citation"].takeIf(::isCitation)
                ?.let { decodeOrNull(TextCitation.serializer(), it) }
                ?.let { ContentBlockDelta.CitationsDelta(it) }
            // input_json_delta 只属于客户端工具，与 Web 版一致丢弃
            else -> null
        }
    }

    private fun <T> decodeOrNull(serializer: kotlinx.serialization.DeserializationStrategy<T>, element: JsonElement): T? =
        try {
            json.decodeFromJsonElement(serializer, element)
        } catch (_: IllegalArgumentException) {
            null
        }
}
