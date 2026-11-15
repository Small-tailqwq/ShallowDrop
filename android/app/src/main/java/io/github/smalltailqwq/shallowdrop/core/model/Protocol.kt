package io.github.smalltailqwq.shallowdrop.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/*
 * Messages API（Anthropic 兼容格式）中被本地消息直接保存的协议结构。
 * 字段名与 src/services/api/types.ts 逐一对应。多态以 "type" 判别（ServerToolInput 以 "action"）。
 */

@Serializable
enum class ChatModel(val id: String) {
    @SerialName("deepseek-flash")
    Flash("deepseek-flash"),

    @SerialName("deepseek-v4-pro")
    V4Pro("deepseek-v4-pro"),
}

/** 正文引用：服务端把模型的引用标记还原为来源，挂在对应文本块上。 */
@Serializable
data class TextCitation(
    val type: String = "web_search_result_location",
    val url: String,
    /** 查不到标题时为 null */
    val title: String? = null,
    /** 当前文本块截至该引用处的全部正文（去除首尾空白） */
    @SerialName("cited_text") val citedText: String = "",
    @SerialName("encrypted_index") val encryptedIndex: String = "",
)

@Serializable
enum class ServerToolKind {
    @SerialName("search")
    Search,

    @SerialName("deep_search")
    DeepSearch,

    @SerialName("read_link")
    ReadLink,

    @SerialName("image_search")
    ImageSearch,

    @SerialName("deep_image_search")
    DeepImageSearch,
}

/** server_tool_use 的 input，结构由 action 决定。 */
@Serializable
@JsonClassDiscriminator("action")
sealed interface ServerToolInput {
    @Serializable
    @SerialName("search")
    data class Search(val query: String? = null, val queries: List<String> = emptyList()) : ServerToolInput

    @Serializable
    @SerialName("open_page")
    data class OpenPage(val url: String) : ServerToolInput

    /** 找不到关联页面时 url 为空串 */
    @Serializable
    @SerialName("find_in_page")
    data class FindInPage(val url: String, val pattern: String) : ServerToolInput

    @Serializable
    @SerialName("read_link")
    data class ReadLink(val url: String) : ServerToolInput
}

@Serializable
data class WebSearchResult(
    val type: String = "web_search_result",
    val title: String,
    val url: String,
    /** 图搜召回的原站图片地址；仅保存 */
    @SerialName("image_url") val imageUrl: String? = null,
    val snippet: String = "",
    @SerialName("site_icon") val siteIcon: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    /** 加密摘要，客户端无法解密 */
    @SerialName("encrypted_content") val encryptedContent: String = "",
    /** RFC 3339 UTC 时间 */
    @SerialName("page_age") val pageAge: String? = null,
)

/** 消息用量；message_delta 中 output_tokens 为全轮累计值。 */
@Serializable
data class MessageUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("server_tool_use") val serverToolUse: JsonElement? = null,
)

@Serializable
enum class StopReason {
    @SerialName("end_turn")
    EndTurn,

    @SerialName("max_tokens")
    MaxTokens,

    @SerialName("tool_use")
    ToolUse,

    @SerialName("refusal")
    Refusal,
}

@Serializable
data class StopDetails(
    val type: String = "refusal",
    val category: String? = null,
    val explanation: String? = null,
)
