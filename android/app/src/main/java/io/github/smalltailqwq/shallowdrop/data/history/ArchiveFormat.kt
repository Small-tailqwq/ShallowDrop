@file:OptIn(ExperimentalSerializationApi::class)

package io.github.smalltailqwq.shallowdrop.data.history

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 历史会话导出产物（deepseek_data-*.zip）的类型定义，对照 src/services/chat/history/format.ts。
 *
 * - 时间均为 UTC RFC 3339、Z 结尾，小数秒精度不固定；这里保存原文，解析见 ArchiveTime；
 * - 消息树按 id 排序和引用；
 * - 可空字段用 null 表示（编码时总是写出）；可选字段允许缺省（默认 null，编码时为 null 则省略，
 *   标注 EncodeDefault.Mode.NEVER）。
 *
 * 读取不使用自动反序列化，由 ArchiveSchema 逐字段校验后构造；这些类型的序列化只用于导出。
 */

const val EXPORT_FORMAT_VERSION = 3

/** 导出用 JSON：可空字段写 null，可选字段为 null 时省略。 */
internal val ArchiveJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

/** manifest.json，ZIP 的第一个条目 */
@Serializable
data class ArchiveManifest(
    @SerialName("export_format_version") val exportFormatVersion: Int = EXPORT_FORMAT_VERSION,
)

/** chat_session_index.json 的一项，只列出成功写出的会话；位于全部会话 JSON 之后、图片之前 */
@Serializable
data class SessionIndexEntry(
    val id: String,
    val title: String?,
    val pinned: Boolean = false,
    @SerialName("message_count") val messageCount: Long,
    @SerialName("inserted_at") val insertedAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** chat_sessions/{session_id}.json */
@Serializable
data class SessionFile(
    val id: String,
    val title: String?,
    val pinned: Boolean = false,
    /** 用户当前停留的消息，线性化消息树的唯一依据 */
    @SerialName("current_message_id") val currentMessageId: Long,
    @SerialName("inserted_at") val insertedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** 消息树，分支靠 parent_id */
    val messages: List<ChatMessage>,
)

@Serializable
enum class ChatRole { USER, ASSISTANT }

@Serializable
enum class ChatMessageStatus { WIP, FINISHED, CONTENT_FILTER, FAILED, INCOMPLETE, CONTEXT_LENGTH_EXCEEDED, TIMEOUT }

@Serializable
data class ChatMessage(
    /** 会话内自增 */
    val id: Long,
    /** null 表示该会话的第一条 */
    @SerialName("parent_id") val parentId: Long?,
    val role: ChatRole,
    val status: ChatMessageStatus,
    @SerialName("thinking_enabled") val thinkingEnabled: Boolean,
    @SerialName("search_enabled") val searchEnabled: Boolean,
    /** 沿分支累计的上下文 token */
    @SerialName("accumulated_token_usage") val accumulatedTokenUsage: Long,
    @SerialName("inserted_at") val insertedAt: String,
    /** 按发生顺序排列 */
    val fragments: List<Fragment>,
)

@Serializable
enum class TipStyle { INFO, WARNING, CAUTION }

/** 普通搜索、打开网页与图搜共用的状态 */
@Serializable
enum class SearchStatus { WIP, FINISHED, FAILED }

@Serializable
enum class ToolFindStatus { WIP, FINISHED, NO_RESULT, FAILED }

@Serializable
data class SearchResult(
    val url: String,
    val title: String,
    val snippet: String,
    @SerialName("published_at") val publishedAt: String?,
    @SerialName("site_name") val siteName: String?,
    /** 可选的站点图标；Web 导出总是写出（可为 null） */
    @SerialName("site_icon") val siteIcon: String? = null,
)

/** 阅读链接结果：可选的 url/title 用于展示；signature 由服务端解密恢复正文 */
@Serializable
data class ReadLinkResult(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val url: String? = null,
    val title: String? = null,
    val signature: String,
)

@Serializable
data class FileEntry(
    /** 不带 file- 前缀的 UUID */
    val id: String,
    /** 用户上传时的文件名 */
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Long,
    @SerialName("token_usage") val tokenUsage: Long?,
    @SerialName("inserted_at") val insertedAt: String,
    /** 图片导出成功时为 ZIP 条目名，否则为 null；条目也可能不存在 */
    @SerialName("export_path") val exportPath: String?,
    @SerialName("is_image") val isImage: Boolean,
    val width: Long?,
    val height: Long?,
)

@Serializable
data class ImageSearchResult(
    val title: String,
    @SerialName("source_page_url") val sourcePageUrl: String,
    val snippet: String,
    @SerialName("site_name") val siteName: String?,
    @SerialName("published_at") val publishedAt: String?,
)

/** 消息片段，以 type 判别。 */
@Serializable
sealed interface Fragment {
    /** 用户的提问 */
    @Serializable
    @SerialName("REQUEST")
    data class Request(val content: String) : Fragment

    /** 模型的回答，引用标记已剥除 */
    @Serializable
    @SerialName("RESPONSE")
    data class Response(val content: String) : Fragment

    @Serializable
    @SerialName("THINK")
    data class Think(
        val content: String,
        /** 思考耗时（秒） */
        @SerialName("elapsed_secs") val elapsedSecs: Double?,
    ) : Fragment

    /** 服务端模板（过载、内容过滤、超长等），不能当正常回答放进上下文 */
    @Serializable
    @SerialName("TEMPLATE_RESPONSE")
    data class TemplateResponse(val content: String) : Fragment

    /** 文案使用导出时的兜底语言（en） */
    @Serializable
    @SerialName("TIP")
    data class Tip(val content: String, val style: TipStyle) : Fragment

    /** 内容风险提示条，保留原回答内容 */
    @Serializable
    @SerialName("CONTENT_RISK")
    data class ContentRisk(val content: String, val style: TipStyle) : Fragment

    @Serializable
    @SerialName("PARTIAL_FILE_READ")
    data class PartialFileRead(
        /** 截断后占原文的百分比，0–100 */
        val ratio: Double,
    ) : Fragment

    /** 普通搜索与边想边搜共用的搜索片段，包含可见结果 */
    @Serializable
    @SerialName("TOOL_SEARCH")
    data class ToolSearch(
        val queries: List<String>,
        val results: List<SearchResult>,
        /** 可选的搜索状态；FINISHED 允许空结果 */
        @EncodeDefault(EncodeDefault.Mode.NEVER) val status: SearchStatus? = null,
        /** 可选的模型推理轮次编号 */
        @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("stage_id") val stageId: Long? = null,
        /** 部分产物携带的分组标识 */
        @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("group_id") val groupId: Long? = null,
    ) : Fragment

    /** 边想边搜里打开的页面；页面信息同时为 null 时只有动作、没有内容 */
    @Serializable
    @SerialName("TOOL_OPEN")
    data class ToolOpen(
        val status: SearchStatus,
        val url: String?,
        val title: String?,
        /** 可选；Web 导出总是写出 */
        val snippet: String? = null,
        /** 可选；Web 导出总是写出 */
        @SerialName("site_name") val siteName: String? = null,
        @SerialName("published_at") val publishedAt: String?,
        @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("stage_id") val stageId: Long? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("group_id") val groupId: Long? = null,
    ) : Fragment

    /** 页面内查找，查找词可选 */
    @Serializable
    @SerialName("TOOL_FIND")
    data class ToolFind(
        val status: ToolFindStatus,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val pattern: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("stage_id") val stageId: Long? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("group_id") val groupId: Long? = null,
    ) : Fragment

    @Serializable
    @SerialName("READ_LINK")
    data class ReadLink(val results: List<ReadLinkResult>) : Fragment

    /** 非图片文件只有元数据，本体不在产物里 */
    @Serializable
    @SerialName("FILE")
    data class File(val files: List<FileEntry>) : Fragment

    /** 图搜结果元数据，支持可选的 image_indexes */
    @Serializable
    @SerialName("TOOL_IMAGE_SEARCH")
    data class ToolImageSearch(
        val status: SearchStatus,
        @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("image_indexes") val imageIndexes: List<Long>? = null,
        val results: List<ImageSearchResult>,
        @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("stage_id") val stageId: Long? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("group_id") val groupId: Long? = null,
    ) : Fragment
}
