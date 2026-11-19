package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.isWebUrl
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.WebSearchResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 转换后的会话，以及本会话目录下应存在的图片：ZIP 条目名 → 文件 ID。 */
data class LocalSession(val session: Session, val images: Map<String, String>)

/** 归档片段到本地消息的转换，对照 toLocal.ts。 */
internal object ToLocal {
    private const val UNAVAILABLE = "unavailable"

    private fun time(value: String): Long = ArchiveTime.parseMillis(value) ?: fail(HistoryErrorCodes.INCOMPLETE)

    private fun toFile(file: FileEntry) = FileAttachment(
        id = file.id,
        serverId = null,
        name = file.fileName,
        size = file.fileSize,
        isImage = file.isImage,
        // 图片身份来自归档；原件随后从 ZIP 读取后补充具体 MIME。
        mimeType = "",
    )

    private fun toUser(message: ChatMessage, parentId: String?, selectedChildId: String?) = Message.User(
        id = message.id.toString(),
        createdAt = time(message.insertedAt),
        parentId = parentId,
        selectedChildId = selectedChildId,
        content = message.fragments.joinToString("") { (it as? Fragment.Request)?.content ?: "" },
        files = message.fragments.flatMap { item -> (item as? Fragment.File)?.files?.map(::toFile).orEmpty() },
    )

    /** 归档不带原调用 ID，以消息和片段位置生成稳定 ID，调用与结果共用。 */
    private fun toolBlocks(id: String, input: ServerToolInput, kind: ServerToolKind, result: ReplyBlock?): List<ReplyBlock> =
        listOfNotNull(
            ReplyBlock.ServerToolUse(
                id = id,
                name = if (kind == ServerToolKind.ReadLink) "deepseek_read_url" else "deepseek_web_search",
                input = input,
                kind = kind,
            ),
            result,
        )

    /** 与 Web 版 web_fetch_result 结构一致，作为 raw 保存，回传时可原样使用。 */
    private fun fetchedRaw(url: String, title: String?, publishedAt: String?): JsonElement = buildJsonObject {
        put("type", "web_fetch_result")
        put("url", url)
        put(
            "content",
            buildJsonObject {
                put("type", "document")
                put(
                    "source",
                    buildJsonObject {
                        put("type", "text")
                        put("media_type", "text/plain")
                        put("data", "")
                    },
                )
                put("title", title?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
        put("published_at", publishedAt?.let(::JsonPrimitive) ?: JsonNull)
    }

    private val unavailableRaw: JsonElement = buildJsonObject {
        put("type", "web_fetch_tool_result_error")
        put("error_code", UNAVAILABLE)
    }

    private fun fetched(
        toolUseId: String,
        kind: ServerToolKind,
        url: String,
        title: String?,
        publishedAt: String?,
        signature: String? = null,
    ) = ReplyBlock.WebFetchToolResult(
        toolUseId = toolUseId,
        url = url,
        title = title,
        publishedAt = publishedAt,
        kind = kind,
        signature = signature,
        raw = fetchedRaw(url, title, publishedAt),
    )

    private fun unavailable(toolUseId: String) = ReplyBlock.WebFetchToolResult(
        toolUseId = toolUseId,
        errorCode = UNAVAILABLE,
        kind = ServerToolKind.DeepSearch,
        raw = unavailableRaw,
    )

    /**
     * 片段一次性转换为正式内容块，展示、请求和导出共用：
     * - 普通搜索与边想边搜共用 TOOL_SEARCH，以同一回复是否有打开 / 查找网页来区分；
     * - READ_LINK 将 URL、标题和签名存入工具结果；仅含签名的结果也保留；
     * - 没有链接的 TOOL_OPEN 不展示；
     * - 没有对应展示的片段（TIP、CONTENT_RISK、PARTIAL_FILE_READ、TOOL_IMAGE_SEARCH 等）直接丢弃。
     */
    fun toBlocks(fragments: List<Fragment>, messageId: String): List<ReplyBlock> {
        val searchKind = if (fragments.any { it is Fragment.ToolOpen || it is Fragment.ToolFind }) {
            ServerToolKind.DeepSearch
        } else {
            ServerToolKind.Search
        }
        return fragments.flatMapIndexed { index, item ->
            val id = "history-$messageId-$index"
            when (item) {
                is Fragment.Response -> listOf(ReplyBlock.Text(item.content))
                is Fragment.TemplateResponse -> listOf(ReplyBlock.Text(item.content, isTemplate = true))
                is Fragment.Think -> listOf(ReplyBlock.Thinking(item.content, elapsedSeconds = item.elapsedSecs))
                is Fragment.ToolSearch -> toolBlocks(
                    id,
                    ServerToolInput.Search(query = item.queries.firstOrNull() ?: "", queries = item.queries),
                    searchKind,
                    if (item.status == SearchStatus.WIP) {
                        null
                    } else if (item.status == SearchStatus.FAILED) {
                        ReplyBlock.WebSearchToolResult(toolUseId = id, errorCode = UNAVAILABLE, kind = searchKind)
                    } else {
                        ReplyBlock.WebSearchToolResult(
                            toolUseId = id,
                            kind = searchKind,
                            results = item.results.filter { isWebUrl(it.url) }.map { result ->
                                WebSearchResult(
                                    url = result.url,
                                    title = result.title,
                                    snippet = result.snippet,
                                    siteIcon = result.siteIcon?.takeIf(::isWebUrl),
                                    siteName = result.siteName,
                                    pageAge = result.publishedAt,
                                    encryptedContent = "",
                                )
                            },
                        )
                    },
                )
                is Fragment.ToolOpen -> {
                    val url = item.url
                    if (url == null || !isWebUrl(url)) {
                        emptyList()
                    } else {
                        toolBlocks(
                            id,
                            ServerToolInput.OpenPage(url),
                            ServerToolKind.DeepSearch,
                            when (item.status) {
                                SearchStatus.WIP -> null
                                SearchStatus.FINISHED -> fetched(id, ServerToolKind.DeepSearch, url, item.title, item.publishedAt)
                                SearchStatus.FAILED -> unavailable(id)
                            },
                        )
                    }
                }
                is Fragment.ToolFind -> toolBlocks(
                    id,
                    ServerToolInput.FindInPage(url = "", pattern = item.pattern ?: ""),
                    ServerToolKind.DeepSearch,
                    when (item.status) {
                        ToolFindStatus.WIP -> null
                        ToolFindStatus.FINISHED -> fetched(id, ServerToolKind.DeepSearch, "", null, null)
                        else -> unavailable(id)
                    },
                )
                is Fragment.ReadLink -> item.results.flatMapIndexed { resultIndex, result ->
                    val url = result.url?.takeIf(::isWebUrl) ?: ""
                    if (url.isEmpty() && result.signature.isBlank()) {
                        emptyList()
                    } else {
                        val toolId = "$id-$resultIndex"
                        toolBlocks(
                            toolId,
                            ServerToolInput.ReadLink(url),
                            ServerToolKind.ReadLink,
                            fetched(toolId, ServerToolKind.ReadLink, url, result.title, null, result.signature),
                        )
                    }
                }
                else -> emptyList()
            }
        }
    }

    /** 统一为正式回复状态：失败不进入上下文，未完成的按已停止处理。 */
    fun replyStatus(status: ChatMessageStatus): ReplyStatus = when (status) {
        ChatMessageStatus.FINISHED, ChatMessageStatus.CONTENT_FILTER -> ReplyStatus.Done
        ChatMessageStatus.FAILED, ChatMessageStatus.CONTEXT_LENGTH_EXCEEDED, ChatMessageStatus.TIMEOUT -> ReplyStatus.Error
        ChatMessageStatus.INCOMPLETE, ChatMessageStatus.WIP -> ReplyStatus.Stopped
    }

    private fun toAssistant(message: ChatMessage, parentId: String?, selectedChildId: String?): Message.Assistant {
        val id = message.id.toString()
        val blocks = toBlocks(message.fragments, id)
        val refused = message.status == ChatMessageStatus.CONTENT_FILTER
        return Message.Assistant(
            id = id,
            createdAt = time(message.insertedAt),
            parentId = parentId,
            selectedChildId = selectedChildId,
            model = null,
            thinking = message.thinkingEnabled,
            search = message.searchEnabled,
            prefix = false,
            status = replyStatus(message.status),
            // 拒绝回复丢弃模型正文和思考，保留模板提示与工具步骤。
            stopReason = if (refused) StopReason.Refusal else null,
            blocks = if (refused) {
                blocks.filter { it !is ReplyBlock.Thinking && (it !is ReplyBlock.Text || it.isTemplate) }
            } else {
                blocks
            },
        )
    }

    /**
     * 按 id 排序还原消息树：父消息的 id 必须更小、角色交替、根为提问。
     * current_message_id 到根的路径为当前分支；其余节点默认选中 id 最大的子消息。
     */
    private fun buildTree(file: SessionFile): Pair<List<Message>, String?> {
        val sorted = file.messages.sortedBy { it.id }
        val byId = HashMap<Long, ChatMessage>()
        val parentOf = HashMap<Long, Long?>()
        val selected = HashMap<Long, Long?>()
        for (message in sorted) {
            val parentId = message.parentId
            val parent = parentId?.let(byId::get)
            if (
                message.id in byId ||
                (if (parentId == null) message.role != ChatRole.USER else parent == null) ||
                (parent != null && parent.role == message.role)
            ) {
                fail(HistoryErrorCodes.INVALID_BRANCHES)
            }
            if (parent != null) selected[parent.id] = message.id
            parentOf[message.id] = parentId
            byId[message.id] = message
        }
        if (sorted.isEmpty()) return emptyList<Message>() to null

        var current = file.currentMessageId
        if (current !in byId) fail(HistoryErrorCodes.INVALID_BRANCHES)
        // 当前消息就是用户停留的位置，后面即使还有消息也不继续展开。
        selected[current] = null
        while (true) {
            val parent = parentOf[current] ?: break
            selected[parent] = current
            current = parent
        }
        val root = current.toString()

        val messages = sorted.map { message ->
            val parentId = parentOf[message.id]?.toString()
            val childId = selected[message.id]?.toString()
            if (message.role == ChatRole.USER) {
                toUser(message, parentId, childId)
            } else {
                toAssistant(message, parentId, childId)
            }
        }
        return messages to root
    }

    /** 转换单个会话，并列出本会话目录下需要从 ZIP 读取的图片。 */
    fun toLocalSession(file: SessionFile): LocalSession {
        val (messages, root) = buildTree(file)
        val session = Session(
            id = file.id,
            // 空标题按「新对话」显示，导出时还原为 null。
            title = file.title ?: "",
            createdAt = time(file.insertedAt),
            updatedAt = time(file.updatedAt),
            messages = messages,
            rootMessageId = root,
        )
        val images = LinkedHashMap<String, String>()
        val dir = ArchivePaths.sessionFilesDir(file.id)
        for (message in file.messages) {
            for (item in message.fragments) {
                if (item !is Fragment.File) continue
                for (entry in item.files) {
                    if (!entry.isImage) continue
                    // 读取端把条目名规范为 NFC，引用也按同样规则比较。
                    val path = entry.exportPath?.let(::nfc) ?: continue
                    // 图片只能在本会话目录下，同一条目不能对应不同文件。
                    if (!path.startsWith(dir) || (images[path] ?: entry.id) != entry.id) {
                        fail(HistoryErrorCodes.INVALID_FILES)
                    }
                    images[path] = entry.id
                }
            }
        }
        return LocalSession(session, images)
    }
}
