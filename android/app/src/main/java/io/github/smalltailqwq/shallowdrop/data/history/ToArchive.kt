package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.StopReason

/** 返回图片原件在 ZIP 里的条目名；无原件或非图片附件返回 null，仅导出元数据。 */
typealias ExportImage = (FileAttachment) -> String?

/** 本地消息到归档片段的转换，对照 toArchive.ts。 */
internal class ToArchive(private val notice: ReplyNotice) {
    private fun iso(ms: Long) = ArchiveTime.format(ms)

    private fun userFragments(message: Message.User, exportImage: ExportImage): List<Fragment> {
        val files = message.files.map { file ->
            FileEntry(
                id = file.id,
                fileName = file.name,
                fileSize = file.size,
                tokenUsage = null,
                insertedAt = iso(message.createdAt),
                exportPath = exportImage(file),
                isImage = file.isImage,
                width = null,
                height = null,
            )
        }
        return listOfNotNull(
            if (files.isNotEmpty()) Fragment.File(files) else null,
            Fragment.Request(message.content),
        )
    }

    private fun toolFragment(tool: ReplyBlock.ServerToolUse, result: ReplyBlock?): List<Fragment> {
        when (val input = tool.input) {
            is ServerToolInput.Search -> {
                val search = result as? ReplyBlock.WebSearchToolResult
                return listOf(
                    Fragment.ToolSearch(
                        queries = input.queries,
                        status = when {
                            search == null -> SearchStatus.WIP
                            search.errorCode != null -> SearchStatus.FAILED
                            else -> SearchStatus.FINISHED
                        },
                        results = search?.takeIf { it.errorCode == null }?.results.orEmpty().map { item ->
                            SearchResult(
                                url = item.url,
                                title = item.title,
                                snippet = item.snippet,
                                publishedAt = ArchiveTime.normalizeOrNull(item.pageAge),
                                siteName = item.siteName,
                                siteIcon = item.siteIcon,
                            )
                        },
                    ),
                )
            }
            // 成功结果的 URL / 标题用于展示，加密页面签名原样保存供后续请求恢复上下文。
            is ServerToolInput.ReadLink -> {
                val fetch = result as? ReplyBlock.WebFetchToolResult
                val signature = fetch?.signature
                if (fetch == null || fetch.kind != ServerToolKind.ReadLink || fetch.errorCode != null || signature.isNullOrBlank()) {
                    return emptyList()
                }
                val url = fetch.url?.ifEmpty { null } ?: input.url.ifEmpty { null }
                return listOf(Fragment.ReadLink(listOf(ReadLinkResult(url = url, title = fetch.title, signature = signature))))
            }
            is ServerToolInput.FindInPage, is ServerToolInput.OpenPage -> {
                val fetch = result as? ReplyBlock.WebFetchToolResult
                val page = fetch?.takeIf { it.errorCode == null }
                val status = when {
                    fetch == null -> SearchStatus.WIP
                    page != null -> SearchStatus.FINISHED
                    else -> SearchStatus.FAILED
                }
                if (input is ServerToolInput.FindInPage) {
                    return listOf(
                        Fragment.ToolFind(
                            status = when (status) {
                                SearchStatus.WIP -> ToolFindStatus.WIP
                                SearchStatus.FINISHED -> ToolFindStatus.FINISHED
                                SearchStatus.FAILED -> ToolFindStatus.FAILED
                            },
                            pattern = input.pattern,
                        ),
                    )
                }
                input as ServerToolInput.OpenPage
                return listOf(
                    Fragment.ToolOpen(
                        status = status,
                        url = input.url.ifEmpty { null } ?: page?.url?.ifEmpty { null },
                        title = page?.title,
                        snippet = if (page != null) "" else null,
                        siteName = null,
                        publishedAt = if (page != null) ArchiveTime.normalizeOrNull(page.publishedAt) else null,
                    ),
                )
            }
        }
    }

    /**
     * 内容块按顺序转换为片段：相邻同类正文合并，服务端模板保留 TEMPLATE_RESPONSE；
     * 引用改由正文外的来源表示，不写入标记；
     * 本轮调用过联网能力时，剥除模型原样输出的 [citation:N]。
     */
    private fun assistantFragments(message: Message.Assistant): List<Fragment> {
        val blocks = message.blocks
        val results = HashMap<String, ReplyBlock>()
        for (block in blocks) {
            when (block) {
                is ReplyBlock.WebSearchToolResult -> results[block.toolUseId] = block
                is ReplyBlock.WebFetchToolResult -> results[block.toolUseId] = block
                else -> Unit
            }
        }
        val usedWeb = blocks.any { it is ReplyBlock.ServerToolUse }
        val fragments = mutableListOf<Fragment>()
        // 正在合并的正文：类型与内容
        var responseTemplate: Boolean? = null
        val responseText = StringBuilder()
        fun flush() {
            val template = responseTemplate ?: return
            val content = responseText.toString()
            fragments += if (template) Fragment.TemplateResponse(content) else Fragment.Response(content)
            responseTemplate = null
            responseText.clear()
        }
        for (block in blocks) {
            if (block is ReplyBlock.Text) {
                if (responseTemplate != block.isTemplate) {
                    flush()
                    responseTemplate = block.isTemplate
                }
                responseText.append(if (usedWeb) block.text.replace(CITATION, "") else block.text)
                continue
            }
            val converted: List<Fragment> = when (block) {
                is ReplyBlock.Thinking -> if (block.thinking.isEmpty()) {
                    emptyList()
                } else {
                    val started = block.startedAt
                    val ended = block.endedAt
                    listOf(
                        Fragment.Think(
                            content = block.thinking,
                            elapsedSecs = block.elapsedSeconds
                                ?: if (started != null && ended != null) (ended - started) / 1000.0 else null,
                        ),
                    )
                }
                is ReplyBlock.ServerToolUse -> toolFragment(block, results[block.id])
                else -> emptyList()
            }
            if (converted.isNotEmpty()) {
                flush()
                fragments += converted
            }
        }
        flush()
        // 错误、拒绝提示作为模板保存，导入后仍不进上下文。
        // 正常空回复不补造提示。
        // 已有的模板直接沿用，避免反复导入导出时重复追加提示。
        val hasTemplate = blocks.any { it is ReplyBlock.Text && it.isTemplate }
        if ((message.status == ReplyStatus.Error && message.error != null) ||
            (!hasTemplate && message.stopReason == StopReason.Refusal)
        ) {
            notice.describe(message)?.let { fragments += Fragment.TemplateResponse(it) }
        }
        return fragments
    }

    private fun replyStatus(message: Message.Assistant): ChatMessageStatus {
        if (message.stopReason == StopReason.Refusal) return ChatMessageStatus.CONTENT_FILTER
        return when (message.status) {
            ReplyStatus.Done -> if (message.stopReason == StopReason.MaxTokens) {
                ChatMessageStatus.INCOMPLETE
            } else {
                ChatMessageStatus.FINISHED
            }
            ReplyStatus.Stopped -> ChatMessageStatus.INCOMPLETE
            ReplyStatus.Error -> ChatMessageStatus.FAILED
            else -> ChatMessageStatus.WIP
        }
    }

    /** 最近一次请求的输入（含缓存）与输出之和，即这一条结束时的上下文 token。 */
    private fun contextTokens(message: Message.Assistant): Long =
        message.usage?.let {
            it.inputTokens + it.cacheCreationInputTokens + it.cacheReadInputTokens + it.outputTokens
        } ?: 0

    /** 消息按父节点优先重新编号为 1…n，保证 parent_id 小于 id。父节点缺失或成环时数据已损坏。 */
    private fun numberMessages(messages: List<Message>): Pair<List<Message>, Map<String, Long>> {
        val byId = messages.associateBy { it.id }
        val ids = HashMap<String, Long>()
        val ordered = ArrayList<Message>(messages.size)
        for (message in messages) {
            // 迭代向上收集未编号的祖先，避免长对话递归过深。
            val chain = ArrayList<Message>()
            var cursor: Message? = message
            while (cursor != null && cursor.id !in ids) {
                if (chain.size > messages.size) fail(HistoryErrorCodes.INVALID_BRANCHES)
                chain += cursor
                val parentId = cursor.parentId ?: break
                cursor = byId[parentId] ?: fail(HistoryErrorCodes.INVALID_BRANCHES)
            }
            for (item in chain.asReversed()) {
                if (item.id in ids) continue
                ordered += item
                ids[item.id] = ordered.size.toLong()
            }
        }
        return ordered to ids
    }

    /** 展示内容与请求上下文共用当前选中的分支路径，对照 branches.ts 的 getVisibleMessages。 */
    private fun visibleMessages(session: Session): List<Message> {
        val byId = session.messages.associateByTo(HashMap()) { it.id }
        val path = ArrayList<Message>()
        var id = session.rootMessageId
        while (id != null) {
            val message = byId.remove(id) ?: break
            path += message
            id = message.selectedChildId
        }
        return path
    }

    fun toSessionFile(session: Session, exportImage: ExportImage): SessionFile {
        val (ordered, ids) = numberMessages(session.messages)
        fun id(message: Message) = ids[message.id] ?: fail(HistoryErrorCodes.INVALID_BRANCHES)
        // 提问的开关取首个回答的请求设置。
        val firstReply = HashMap<String, Message.Assistant>()
        for (message in ordered) {
            val parentId = message.parentId
            if (message is Message.Assistant && parentId != null && parentId !in firstReply) {
                firstReply[parentId] = message
            }
        }
        val messages = ordered.map { message ->
            val reply = message as? Message.Assistant ?: firstReply[message.id]
            ChatMessage(
                id = id(message),
                parentId = message.parentId?.let { ids[it] },
                role = if (message is Message.User) ChatRole.USER else ChatRole.ASSISTANT,
                status = if (message is Message.Assistant) replyStatus(message) else ChatMessageStatus.FINISHED,
                thinkingEnabled = reply?.thinking ?: false,
                searchEnabled = reply?.search ?: false,
                accumulatedTokenUsage = if (message is Message.Assistant) contextTokens(message) else 0,
                insertedAt = iso(message.createdAt),
                fragments = when (message) {
                    is Message.User -> userFragments(message, exportImage)
                    is Message.Assistant -> assistantFragments(message)
                },
            )
        }
        val current = visibleMessages(session).lastOrNull()
        return SessionFile(
            id = session.id,
            title = session.title.ifEmpty { null },
            pinned = false,
            // 格式要求非空；空会话没有消息可指向，写 0。
            currentMessageId = current?.let(::id) ?: 0,
            insertedAt = iso(session.createdAt),
            updatedAt = iso(session.updatedAt),
            messages = messages,
        )
    }

    companion object {
        private val CITATION = Regex("\\[citation:\\d+]")

        fun toIndexEntry(file: SessionFile) = SessionIndexEntry(
            id = file.id,
            title = file.title,
            pinned = false,
            messageCount = file.messages.size.toLong(),
            insertedAt = file.insertedAt,
            updatedAt = file.updatedAt,
        )
    }
}
