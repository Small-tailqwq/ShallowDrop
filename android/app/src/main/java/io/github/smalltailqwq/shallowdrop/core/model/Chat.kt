package io.github.smalltailqwq.shallowdrop.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * 本地消息模型，对照 src/services/chat/types.ts。
 * 时间戳统一为 epoch 毫秒。会话内消息构成树：parentId 指向上一条，selectedChildId 记录分支选择。
 * 这些类型是各工作流的共享契约，改字段需同步 data/db、data/history、domain/chat、ui。
 */

/**
 * 消息附件。id 是本地标识（原件按它存储，引用同一 id 的消息共用一份），导入的附件沿用归档文件 ID。
 * serverId 是 Files API 的 file_id；导入或尚未上传的为 null，发送前补传。
 */
@Serializable
data class FileAttachment(
    val id: String,
    val serverId: String? = null,
    val name: String,
    val size: Long,
    /** 导入时沿用归档的 is_image，不由 MIME 决定 */
    val isImage: Boolean,
    /** 具体 MIME；未知为空串 */
    val mimeType: String = "",
)

/** 回复内容块，按接收顺序排列；续写产生的块追加在末尾。 */
@Serializable
sealed interface ReplyBlock {
    /** 思考块；历史轮次的思考不回传。 */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val thinking: String,
        val signature: String? = null,
        /** content_block_start 的时间 */
        val startedAt: Long? = null,
        /** content_block_stop（或回复结束）的时间 */
        val endedAt: Long? = null,
        /** 仅有耗时、没有事件时间时保存秒数 */
        val elapsedSeconds: Double? = null,
    ) : ReplyBlock

    /** 文本块；isTemplate 为服务端提示文案，只展示，不进入上下文或续写。 */
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val citations: List<TextCitation> = emptyList(),
        val isTemplate: Boolean = false,
    ) : ReplyBlock

    /** 服务端工具调用块，原子下发。 */
    @Serializable
    @SerialName("server_tool_use")
    data class ServerToolUse(
        /** srvtoolu_{kind}_{序号}_{hex} */
        val id: String,
        /** deepseek_web_search 或 deepseek_read_url */
        val name: String,
        val input: ServerToolInput,
        val kind: ServerToolKind,
    ) : ReplyBlock

    /** 搜索结果；成功无结果为空列表，失败时 errorCode 非空。 */
    @Serializable
    @SerialName("web_search_tool_result")
    data class WebSearchToolResult(
        val toolUseId: String,
        val results: List<WebSearchResult> = emptyList(),
        val errorCode: String? = null,
        val kind: ServerToolKind,
    ) : ReplyBlock

    /** 网页读取结果；仅 read_link 成功时有 signature，多轮请求原样回传。 */
    @Serializable
    @SerialName("web_fetch_tool_result")
    data class WebFetchToolResult(
        val toolUseId: String,
        val url: String? = null,
        val title: String? = null,
        val publishedAt: String? = null,
        val errorCode: String? = null,
        val kind: ServerToolKind,
        val signature: String? = null,
        /** 协议原始 content，回传时原样使用；可空 */
        val raw: JsonElement? = null,
    ) : ReplyBlock
}

@Serializable
enum class ReplyStatus {
    /** 已发出请求，还没收到事件 */
    Pending,
    Streaming,

    /** 收到 message_stop */
    Done,

    /** 用户停止或进程退出 */
    Stopped,
    Error,
}

@Serializable
enum class ReplyErrorKind { Http, Stream, Network, Interrupted, Protocol, Upload }

@Serializable
data class ReplyError(
    val kind: ReplyErrorKind,
    val message: String,
    val status: Int? = null,
    /** 本地化错误码，UI 映射为文案；为空时直接显示 message */
    val code: String? = null,
    /** 仅 Upload：重新上传是否可能成功 */
    val retryable: Boolean? = null,
)

@Serializable
sealed interface Message {
    val id: String
    val createdAt: Long
    val parentId: String?
    val selectedChildId: String?

    @Serializable
    @SerialName("user")
    data class User(
        override val id: String,
        override val createdAt: Long,
        override val parentId: String? = null,
        override val selectedChildId: String? = null,
        val content: String,
        val files: List<FileAttachment> = emptyList(),
    ) : Message

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        override val id: String,
        override val createdAt: Long,
        override val parentId: String? = null,
        override val selectedChildId: String? = null,
        /** 未记录模型时为 null，续写使用当前默认模型 */
        val model: ChatModel? = null,
        val thinking: Boolean = false,
        val search: Boolean = false,
        /** 本轮请求的系统信息快照；续写沿用 */
        val latestReminder: String? = null,
        /** 是否允许作为续写前缀；导入的回复为 false */
        val prefix: Boolean = false,
        val status: ReplyStatus,
        val blocks: List<ReplyBlock> = emptyList(),
        val stopReason: StopReason? = null,
        val stopDetails: StopDetails? = null,
        val usage: MessageUsage? = null,
        val error: ReplyError? = null,
    ) : Message
}

/** 会话；messages 含所有分支，按创建顺序。rootMessageId 为 null 表示空会话。 */
@Serializable
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<Message> = emptyList(),
    val rootMessageId: String? = null,
)

/** 会话列表元数据，不含消息。 */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

fun Session.toSummary() = SessionSummary(id, title, createdAt, updatedAt)
