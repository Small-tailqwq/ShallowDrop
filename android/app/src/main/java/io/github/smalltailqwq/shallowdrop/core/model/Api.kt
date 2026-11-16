package io.github.smalltailqwq.shallowdrop.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Messages API 请求与流式事件，对照 src/services/api/types.ts。
 * domain/chat 构建请求、data/api 负责序列化与解析；两边都只依赖这里的类型。
 */

// ---------- 请求 ----------

/** 用户消息内容片段。图片用 image、其余用 document，均引用 Files API 的 file_id。 */
@Serializable
sealed interface UserContentParam {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : UserContentParam

    @Serializable
    @SerialName("image")
    data class Image(val source: FileSource) : UserContentParam

    @Serializable
    @SerialName("document")
    data class Document(val source: FileSource) : UserContentParam
}

@Serializable
data class FileSource(
    val type: String = "file",
    @SerialName("file_id") val fileId: String,
)

/**
 * 消息参数。latest_reminder 是 Chat Engine 扩展角色，位于对应 user 前。
 * assistant 内容块按协议原样构造（text / thinking / server_tool_use / web_fetch_tool_result 等），
 * 由 domain/chat 生成 JsonObject，避免重复定义只用于回传的结构。
 * 末尾为 assistant 时服务端自动续写。
 */
sealed interface MessageParam {
    data class LatestReminder(val content: String) : MessageParam

    data class User(val content: List<UserContentParam>) : MessageParam

    data class Assistant(val content: List<JsonObject>) : MessageParam
}

enum class RequestTool(val type: String) {
    WebSearch("deepseek_web_search"),
    ReadUrl("deepseek_read_url"),
}

data class CreateMessageRequest(
    val model: ChatModel,
    val system: String,
    val messages: List<MessageParam>,
    val thinking: Boolean,
    val tools: List<RequestTool>,
    /** 是否请求生成标题（message_delta.title） */
    val title: Boolean = false,
    /** 是否引用了 Files API 文件；为 true 时需携带 anthropic-beta 头 */
    val hasFiles: Boolean = false,
    /** 摘要请求限制输出长度；普通对话沿用服务端默认值。 */
    val maxTokens: Int? = null,
)

// ---------- 流式事件 ----------

@Serializable
sealed interface ContentBlockDelta {
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val text: String) : ContentBlockDelta

    @Serializable
    @SerialName("thinking_delta")
    data class ThinkingDelta(val thinking: String) : ContentBlockDelta

    @Serializable
    @SerialName("signature_delta")
    data class SignatureDelta(val signature: String) : ContentBlockDelta

    @Serializable
    @SerialName("citations_delta")
    data class CitationsDelta(val citation: TextCitation) : ContentBlockDelta

    @Serializable
    @SerialName("input_json_delta")
    data class InputJsonDelta(@SerialName("partial_json") val partialJson: String) : ContentBlockDelta
}

/** 流式事件；以 message_stop 结束。ping 与无法识别的事件不产出。 */
sealed interface MessageStreamEvent {
    /** id 为不带 msg_ 前缀的 UUID */
    data class MessageStart(val id: String, val model: String, val usage: MessageUsage?) : MessageStreamEvent

    /**
     * 内容块开始。block 为已转换的本地块（工具块已完整）；
     * 无法识别的块（如客户端 tool_use）为 null，raw 保留原始 JSON。
     */
    data class ContentBlockStart(val index: Int, val block: ReplyBlock?, val raw: JsonElement) : MessageStreamEvent

    data class ContentBlockDeltaEvent(val index: Int, val delta: ContentBlockDelta) : MessageStreamEvent

    data class ContentBlockStop(val index: Int) : MessageStreamEvent

    /** title 仅在开启标题且生成成功时出现；stopDetails 仅在 refusal 时出现 */
    data class MessageDelta(
        val stopReason: StopReason?,
        val stopDetails: StopDetails?,
        val title: String?,
        val usage: MessageUsage?,
    ) : MessageStreamEvent

    data object MessageStop : MessageStreamEvent
}

/** Files API 上传 purpose：PDF / Word / PPT 需版面解析；其余文本类用 user_data；图片为 null。 */
enum class FilePurpose(val value: String) {
    RichLayoutRecognition("rich_layout_recognition"),
    UserData("user_data"),
}

@Serializable
data class FileObject(
    val id: String,
    val type: String = "file",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    val filename: String = "",
    @SerialName("mime_type") val mimeType: String = "",
)

/** API 调用失败。kind 与 ReplyErrorKind 一致（不含 Upload）。 */
class ApiException(
    val kind: ReplyErrorKind,
    override val message: String,
    val status: Int? = null,
    /** 本地化错误码，见 data/api 对 errors.ts 的移植 */
    val code: String? = null,
) : Exception(message)
