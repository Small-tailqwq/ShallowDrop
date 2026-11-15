package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.ContentBlockDelta
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.FileSource
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyError
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * 回复累积、续写条件与请求上下文构建，对照 src/services/chat/reply.ts。
 */

private typealias Assistant = Message.Assistant

/**
 * 文件按 Files API 的 file_id（附件的 serverId）引用：图片用 image 块，其余用 document 块。
 * 发送前 ChatEngine 会先补齐 serverId；仍为 null 的是本地没有原件的附件，不进入请求。
 */
internal fun toFileBlocks(files: List<FileAttachment>): List<UserContentParam> = files.mapNotNull { file ->
    val serverId = file.serverId ?: return@mapNotNull null
    val source = FileSource(fileId = serverId)
    if (file.isImage) UserContentParam.Image(source) else UserContentParam.Document(source)
}

private fun toReplyBlock(block: ReplyBlock, now: Long): ReplyBlock = when (block) {
    is ReplyBlock.Text -> block
    is ReplyBlock.Thinking -> block.copy(startedAt = now, endedAt = null)
    else -> block
}

private fun Assistant.updateBlock(index: Int, update: (ReplyBlock) -> ReplyBlock): Assistant {
    val block = blocks.getOrNull(index) ?: return this
    val next = update(block)
    if (next === block) return this
    return copy(blocks = blocks.toMutableList().also { it[index] = next })
}

/**
 * 把一个流式事件合并进回复：只追加可识别的内容块，用本次请求的临时索引映射处理增量。
 * 跳过的块不占位；续写直接追加到已有内容之后，message_delta 更新结束原因与累计用量。
 * blockIndexes 是「事件 index → blocks 下标」，每次请求单独一份。
 */
fun applyEvent(
    reply: Assistant,
    event: MessageStreamEvent,
    now: Long,
    blockIndexes: MutableMap<Int, Int>,
): Assistant {
    val next = if (reply.status == ReplyStatus.Streaming) reply else reply.copy(status = ReplyStatus.Streaming)
    return when (event) {
        is MessageStreamEvent.MessageStart -> next.copy(usage = event.usage ?: next.usage)
        is MessageStreamEvent.ContentBlockStart -> {
            blockIndexes.remove(event.index)
            val block = event.block
            if (block == null) {
                // 客户端工具内容不保存；未完成的工具往返也不能作为续写前缀。
                val type = (event.raw as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
                if (type == "tool_use") next.copy(prefix = false) else next
            } else {
                blockIndexes[event.index] = next.blocks.size
                next.copy(blocks = next.blocks + toReplyBlock(block, now))
            }
        }
        is MessageStreamEvent.ContentBlockDeltaEvent -> {
            val index = blockIndexes[event.index] ?: return next
            val delta = event.delta
            next.updateBlock(index) { block ->
                when {
                    block is ReplyBlock.Text && delta is ContentBlockDelta.TextDelta ->
                        block.copy(text = block.text + delta.text)
                    block is ReplyBlock.Thinking && delta is ContentBlockDelta.ThinkingDelta ->
                        block.copy(thinking = block.thinking + delta.thinking)
                    block is ReplyBlock.Thinking && delta is ContentBlockDelta.SignatureDelta ->
                        block.copy(signature = (block.signature ?: "") + delta.signature)
                    block is ReplyBlock.Text && delta is ContentBlockDelta.CitationsDelta ->
                        block.copy(citations = block.citations + delta.citation)
                    else -> block
                }
            }
        }
        is MessageStreamEvent.ContentBlockStop -> {
            val index = blockIndexes.remove(event.index) ?: return next
            next.updateBlock(index) { block ->
                if (block is ReplyBlock.Thinking && block.startedAt != null && block.endedAt == null) {
                    block.copy(endedAt = now)
                } else {
                    block
                }
            }
        }
        is MessageStreamEvent.MessageDelta -> next.copy(
            stopReason = event.stopReason,
            stopDetails = event.stopDetails,
            usage = event.usage ?: next.usage,
        )
        MessageStreamEvent.MessageStop -> next
    }
}

/**
 * 回复结束：未结束的思考块以当前时间收尾。
 * 安全拒绝（stop_reason: refusal）时已下发的思考与正文不能撤回，按协议要求由客户端丢弃，只保留工具块。
 */
fun settleReply(reply: Assistant, status: ReplyStatus, now: Long, error: ReplyError? = null): Assistant {
    require(status == ReplyStatus.Done || status == ReplyStatus.Stopped || status == ReplyStatus.Error)
    val refused = reply.stopReason == StopReason.Refusal
    return reply.copy(
        status = status,
        error = error,
        blocks = reply.blocks.mapNotNull { block ->
            when {
                refused && (block is ReplyBlock.Thinking || block is ReplyBlock.Text) -> null
                block is ReplyBlock.Thinking && block.startedAt != null && block.endedAt == null ->
                    block.copy(endedAt = now)
                else -> block
            }
        },
    )
}

/** 回复里所有文本块拼起来，用于复制与判断是否有回答 */
fun replyText(reply: Assistant): String =
    reply.blocks.joinToString("") { if (it is ReplyBlock.Text) it.text else "" }

/**
 * 只有正常达到输出上限或被停止的回复可续写，报错回复不能续写。
 * 末条限制由调用方判断，拒绝和客户端工具调用不能续写。
 * 本轮含服务端工具调用或结果（含 READ_LINK 和孤立结果块）的回复不能续写。
 * 服务端模板提示不能作为续写前缀。
 */
fun canContinueReply(reply: Assistant): Boolean {
    if (!reply.prefix ||
        reply.stopReason == StopReason.Refusal ||
        reply.stopReason == StopReason.ToolUse ||
        reply.blocks.any {
            it is ReplyBlock.ServerToolUse ||
                it is ReplyBlock.WebSearchToolResult ||
                it is ReplyBlock.WebFetchToolResult ||
                (it is ReplyBlock.Text && it.isTemplate)
        }
    ) {
        return false
    }
    return reply.status == ReplyStatus.Stopped ||
        (reply.status == ReplyStatus.Done && reply.stopReason == StopReason.MaxTokens)
}

/**
 * 已结束或停止且有回答文本的回复才放进上下文；被安全拒绝的轮次不回传。
 * 服务端模板提示不进入上下文。
 */
internal fun isUsableReply(message: Message): Boolean =
    message is Assistant &&
        (message.status == ReplyStatus.Done || message.status == ReplyStatus.Stopped) &&
        message.stopReason != StopReason.Refusal &&
        message.blocks.none { it is ReplyBlock.Text && it.isTemplate } &&
        replyText(message) != ""

/** 历史中会进入请求上下文的轮次：提问后紧跟可用回答的才保留 */
fun contextRounds(history: List<Message>): List<Pair<Message.User, Assistant>> {
    val rounds = ArrayList<Pair<Message.User, Assistant>>()
    var i = 0
    while (i + 1 < history.size) {
        val user = history[i]
        val reply = history[i + 1]
        if (user is Message.User && isUsableReply(reply)) {
            rounds.add(user to reply as Assistant)
            i++
        }
        i++
    }
    return rounds
}

/**
 * 有文件时用内容块数组：先文件后文本。没有文件时为单个文本块
 * （Web 版此时 content 为字符串，契约只有列表形式，序列化方可按需压缩成字符串）。
 */
private fun toUserParam(user: Message.User): MessageParam.User {
    val blocks = toFileBlocks(user.files)
    return if (blocks.isEmpty()) {
        MessageParam.User(listOf(UserContentParam.Text(user.content)))
    } else {
        MessageParam.User(blocks + if (user.content.isNotEmpty()) listOf(UserContentParam.Text(user.content)) else emptyList())
    }
}

private val protocolJson = Json { encodeDefaults = true }

/** 网页读取是否成功：优先看原始 content 的 type，没有原始内容时以 errorCode 为准。 */
internal fun ReplyBlock.WebFetchToolResult.isSuccess(): Boolean {
    val type = (raw as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
    return if (type != null) type == "web_fetch_result" else errorCode == null
}

private fun kindValue(kind: ServerToolKind): JsonPrimitive =
    protocolJson.encodeToJsonElement(ServerToolKind.serializer(), kind) as JsonPrimitive

private fun toolUseParam(block: ReplyBlock.ServerToolUse): JsonObject = buildJsonObject {
    put("type", "server_tool_use")
    put("id", block.id)
    put("name", block.name)
    put("input", protocolJson.encodeToJsonElement(ServerToolInput.serializer(), block.input))
    put("kind", kindValue(block.kind))
}

/** 结果块原样回传：content 优先用协议原始 JSON，缺失时按字段还原。 */
private fun fetchResultParam(block: ReplyBlock.WebFetchToolResult): JsonObject = buildJsonObject {
    put("type", "web_fetch_tool_result")
    put("tool_use_id", block.toolUseId)
    val raw = block.raw as? JsonObject
    put(
        "content",
        raw ?: if (block.errorCode != null) {
            buildJsonObject {
                put("type", "web_fetch_tool_result_error")
                put("error_code", block.errorCode)
            }
        } else {
            buildJsonObject {
                put("type", "web_fetch_result")
                put("url", block.url ?: "")
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
                        put("title", block.title)
                    },
                )
                put("published_at", block.publishedAt)
            }
        },
    )
    put("kind", kindValue(block.kind))
    block.signature?.let { put("signature", it) }
}

/** 构建 assistant 参数期间的中间块：文本需要原地拼接 */
private sealed interface Draft {
    class Text(val text: StringBuilder) : Draft

    class Json(val value: JsonObject) : Draft
}

/**
 * assistant 的内容块按历史与续写场景转换：
 * - 历史思考及其签名不回传；开启思考时续写只保留本轮非空白思考及其签名；
 * - 文本块去掉 citations（服务端忽略）。相邻文本直接拼接，被思考或工具隔开的文本用空行连接；
 *   省略历史思考后仍保留其前后正文的空行；
 * - 工具调用和结果只保留 kind 为 read_link 的块，其他历史工具块不回传；
 * - 阅读链接结果原样回传，保留 signature：服务端解密后将页面拼回该轮提问末尾；
 */
internal fun toAssistantParam(reply: Assistant, prefix: Boolean = false, thinking: Boolean = false): MessageParam.Assistant {
    // 成功阅读的网页靠签名恢复正文；缺少有效签名时，调用与结果都不回传。
    val unsignedReadLinks = reply.blocks.mapNotNullTo(HashSet()) { block ->
        if (block is ReplyBlock.WebFetchToolResult &&
            block.kind == ServerToolKind.ReadLink &&
            block.isSuccess() &&
            block.signature.isNullOrBlank()
        ) {
            block.toolUseId
        } else {
            null
        }
    }
    val content = ArrayList<Draft>()
    // 正在拼接的文本块；遇到任何非文本块（含不回传的思考块）就结束
    var text: Draft.Text? = null
    for (block in reply.blocks) {
        // 空思考不改变续写位置，也不应把两段正文隔开，导致后端额外插入空行。
        if (prefix && block is ReplyBlock.Thinking && block.thinking.isBlank()) continue
        if (block is ReplyBlock.Text) {
            val current = text
            if (current != null) {
                current.text.append(block.text)
            } else {
                val separator = if (content.lastOrNull() is Draft.Text) "\n\n" else ""
                text = Draft.Text(StringBuilder(separator).append(block.text)).also { content.add(it) }
            }
            continue
        }
        text = null
        when {
            prefix && thinking && block is ReplyBlock.Thinking -> content.add(
                Draft.Json(
                    buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.thinking)
                        put("signature", block.signature ?: "")
                    },
                ),
            )
            block is ReplyBlock.ServerToolUse &&
                block.kind == ServerToolKind.ReadLink &&
                block.id !in unsignedReadLinks -> content.add(Draft.Json(toolUseParam(block)))
            block is ReplyBlock.WebFetchToolResult &&
                block.kind == ServerToolKind.ReadLink &&
                block.toolUseId !in unsignedReadLinks -> content.add(Draft.Json(fetchResultParam(block)))
        }
    }
    return MessageParam.Assistant(
        content.mapNotNull {
            when (it) {
                is Draft.Text -> if (it.text.isEmpty()) {
                    null
                } else {
                    buildJsonObject {
                        put("type", "text")
                        put("text", it.text.toString())
                    }
                }
                is Draft.Json -> it.value
            }
        },
    )
}

/**
 * 把会话转成请求的 messages：只保留「提问 + 有回答的回复」成对的轮次，最后接上本轮提问。
 * 失败或没有回答的轮次不进上下文。续写时末尾再带上本轮 assistant。
 */
fun toRequestMessages(
    history: List<Message>,
    question: Message.User,
    prefix: Assistant? = null,
    thinking: Boolean = prefix?.thinking ?: false,
    latestReminder: String? = null,
): List<MessageParam> {
    val messages = ArrayList<MessageParam>()
    var previousReminder: String? = null
    // 仅在当前分支首次出现或内容变化时插入快照；缺少快照的消息保持原有内容。
    fun appendReminder(reminder: String?) {
        if (!reminder.isNullOrEmpty() && reminder != previousReminder) {
            messages.add(MessageParam.LatestReminder(reminder))
            previousReminder = reminder
        }
    }
    for ((user, reply) in contextRounds(history)) {
        // 历史提问没有文字、附件也都没有 serverId 时，提问为空，连同回答一起省略。
        if (user.content.isEmpty() && toFileBlocks(user.files).isEmpty()) continue
        appendReminder(reply.latestReminder)
        messages.add(toUserParam(user))
        messages.add(toAssistantParam(reply))
    }
    appendReminder(latestReminder)
    messages.add(toUserParam(question))
    if (prefix != null && prefix.prefix) {
        val assistant = toAssistantParam(prefix, prefix = true, thinking = thinking)
        // 没有非空白正文或有效思考时直接重发原问题，避免服务端拒绝空 Prefill。
        val meaningful = assistant.content.any { block ->
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                "thinking" -> block["thinking"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                else -> false
            }
        }
        if (meaningful) messages.add(assistant)
    }
    return messages
}

/** 请求是否引用了 Files API 文件（决定 anthropic-beta 头） */
internal fun hasFileBlocks(messages: List<MessageParam>): Boolean =
    messages.any { message ->
        message is MessageParam.User && message.content.any { it !is UserContentParam.Text }
    }

/** 思考用时（秒），至少 1 秒 */
fun thinkingSeconds(startedAt: Long, endedAt: Long): Long =
    maxOf(1L, Math.round((endedAt - startedAt) / 1000.0))
