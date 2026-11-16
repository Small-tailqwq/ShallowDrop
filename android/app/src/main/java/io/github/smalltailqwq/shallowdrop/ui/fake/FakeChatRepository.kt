package io.github.smalltailqwq.shallowdrop.ui.fake

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyError
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import io.github.smalltailqwq.shallowdrop.core.model.WebSearchResult
import io.github.smalltailqwq.shallowdrop.core.model.toSummary
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 开发期内存实现：带演示会话（多分支、思考、搜索来源、已停止可续写），
 * 发送时模拟流式回复。内容包含 “error” 时模拟余额不足错误。集成阶段替换为 domain/chat 的实现。
 */
class FakeChatRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ChatRepository {
    private val appContext = context.applicationContext
    private val store = MutableStateFlow(seedSessions(System.currentTimeMillis()).associateBy { it.id })
    private val generating = MutableStateFlow<Set<String>>(emptySet())
    private val jobs = HashMap<String, Job>()

    override val sessions: Flow<List<SessionSummary>> = store
        .map { map -> map.values.sortedByDescending { it.updatedAt }.map { it.toSummary() } }
        .distinctUntilChanged()

    override fun observeConversation(sessionId: String): Flow<ConversationState?> =
        combine(store, generating) { map, busy -> map[sessionId]?.let { buildState(it, sessionId in busy) } }
            .distinctUntilChanged()

    override suspend fun send(sessionId: String?, text: String, files: List<FileAttachment>, options: SendOptions): String {
        val now = System.currentTimeMillis()
        val id = sessionId ?: newId().also { newSessionId ->
            store.update { it + (newSessionId to Session(newSessionId, "", now, now)) }
        }
        val user = Message.User(newId(), now, content = text, files = files)
        val assistant = pendingReply(now + 1, options)
        mutate(id) { session ->
            val parent = currentPath(session).lastOrNull()
            session.attach(user, parent?.id).attach(assistant.copy(parentId = user.id), user.id)
                .copy(title = session.title.ifEmpty { text.lineSequence().first().take(24) })
        }
        startReply(id, assistant.id, text, options)
        return id
    }

    override fun stop(sessionId: String) {
        jobs.remove(sessionId)?.cancel()
    }

    override suspend fun regenerate(sessionId: String, assistantMessageId: String, options: SendOptions) {
        val session = store.value[sessionId] ?: return
        val latest = buildState(session, sessionId in generating.value).turns.lastOrNull() ?: return
        if (latest.message.id != assistantMessageId || !latest.canRegenerate) return
        val old = latest.message
        val question = old.parentId?.let { findMessage(sessionId, it) as? Message.User }
        val reply = pendingReply(System.currentTimeMillis(), options).copy(parentId = old.parentId)
        mutate(sessionId) { it.attach(reply, old.parentId) }
        startReply(sessionId, reply.id, question?.content.orEmpty(), options)
    }

    override suspend fun editAndResend(sessionId: String, userMessageId: String, text: String, options: SendOptions) {
        val old = findMessage(sessionId, userMessageId) as? Message.User ?: return
        val now = System.currentTimeMillis()
        val user = Message.User(newId(), now, parentId = old.parentId, content = text, files = old.files)
        val reply = pendingReply(now + 1, options).copy(parentId = user.id)
        mutate(sessionId) { it.attach(user, old.parentId).attach(reply, user.id) }
        startReply(sessionId, reply.id, text, options)
    }

    override suspend fun continueReply(sessionId: String, assistantMessageId: String) {
        updateReply(sessionId, assistantMessageId) { it.copy(status = ReplyStatus.Streaming, stopReason = null) }
        launchJob(sessionId, assistantMessageId) {
            streamText(sessionId, assistantMessageId, CONTINUATION, emptyList())
        }
    }

    override suspend fun switchBranch(sessionId: String, messageId: String, index: Int) {
        mutate(sessionId) { session ->
            val message = session.messages.firstOrNull { it.id == messageId } ?: return@mutate session
            val siblings = session.messages.filter { it.parentId == message.parentId }
            val target = siblings.getOrNull(index) ?: return@mutate session
            session.select(target.id, message.parentId)
        }
    }

    override suspend fun addAttachment(uri: Uri): FileAttachment = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        delay(400)
        FileAttachment(id = newId(), name = name, size = size, isImage = mime.startsWith("image/"), mimeType = mime)
    }

    override suspend fun rename(sessionId: String, title: String) {
        mutate(sessionId) { it.copy(title = title) }
    }

    override suspend fun delete(sessionId: String) {
        stop(sessionId)
        store.update { it - sessionId }
    }

    override suspend fun deleteAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        store.value = emptyMap()
    }

    // ---------- 模拟流式 ----------

    private fun pendingReply(now: Long, options: SendOptions) = Message.Assistant(
        id = newId(),
        createdAt = now,
        model = options.model,
        thinking = options.thinking,
        search = options.search,
        prefix = true,
        status = ReplyStatus.Pending,
    )

    private fun startReply(sessionId: String, replyId: String, question: String, options: SendOptions) {
        launchJob(sessionId, replyId) {
            delay(500)
            updateReply(sessionId, replyId) { it.copy(status = ReplyStatus.Streaming) }
            if (question.contains("error", ignoreCase = true)) {
                updateReply(sessionId, replyId) {
                    it.copy(
                        status = ReplyStatus.Error,
                        error = ReplyError(ReplyErrorKind.Http, "Insufficient Balance", status = 402),
                    )
                }
                return@launchJob
            }
            if (options.thinking) streamThinking(sessionId, replyId)
            val citations = if (options.search) streamSearch(sessionId, replyId, question) else emptyList()
            streamText(sessionId, replyId, ANSWER, citations)
        }
    }

    private fun launchJob(sessionId: String, replyId: String, block: suspend () -> Unit) {
        jobs.remove(sessionId)?.cancel()
        generating.update { it + sessionId }
        val job = scope.launch {
            var completed = false
            try {
                block()
                completed = true
            } finally {
                withContext(NonCancellable) {
                    updateReply(sessionId, replyId) { reply ->
                        val blocks = reply.blocks.map { b ->
                            if (b is ReplyBlock.Thinking && b.endedAt == null) b.copy(endedAt = System.currentTimeMillis()) else b
                        }
                        when {
                            reply.status == ReplyStatus.Error -> reply.copy(blocks = blocks)
                            completed -> reply.copy(status = ReplyStatus.Done, stopReason = StopReason.EndTurn, blocks = blocks)
                            else -> reply.copy(status = ReplyStatus.Stopped, blocks = blocks)
                        }
                    }
                    generating.update { it - sessionId }
                }
            }
        }
        jobs[sessionId] = job
    }

    private suspend fun streamThinking(sessionId: String, replyId: String) {
        val startedAt = System.currentTimeMillis()
        updateReply(sessionId, replyId) { it.copy(blocks = it.blocks + ReplyBlock.Thinking("", startedAt = startedAt)) }
        for (chunk in THINKING.chunked(4)) {
            delay(35)
            updateLast<ReplyBlock.Thinking>(sessionId, replyId) { it.copy(thinking = it.thinking + chunk) }
        }
        updateLast<ReplyBlock.Thinking>(sessionId, replyId) { it.copy(endedAt = System.currentTimeMillis()) }
    }

    private suspend fun streamSearch(sessionId: String, replyId: String, question: String): List<TextCitation> {
        val toolId = "srvtoolu_search_0_${newId().take(8)}"
        val query = question.lineSequence().first().take(30).ifEmpty { "ShallowDrop" }
        updateReply(sessionId, replyId) {
            it.copy(
                blocks = it.blocks + ReplyBlock.ServerToolUse(
                    id = toolId,
                    name = "deepseek_web_search",
                    input = ServerToolInput.Search(queries = listOf(query, "$query 2026")),
                    kind = ServerToolKind.Search,
                ),
            )
        }
        delay(1600)
        val results = sampleResults()
        updateReply(sessionId, replyId) {
            it.copy(blocks = it.blocks + ReplyBlock.WebSearchToolResult(toolId, results, kind = ServerToolKind.Search))
        }
        return results.take(2).map { TextCitation(url = it.url, title = it.title) }
    }

    private suspend fun streamText(sessionId: String, replyId: String, text: String, citations: List<TextCitation>) {
        // 引用挂在第一段末尾；服务端在挂过引用的块之后另起新块
        val split = text.indexOf("\n\n").takeIf { it > 0 && citations.isNotEmpty() } ?: text.length
        val parts = listOf(text.substring(0, split) to citations, text.substring(split) to emptyList())
        for ((part, partCitations) in parts) {
            if (part.isEmpty()) continue
            updateReply(sessionId, replyId) { it.copy(blocks = it.blocks + ReplyBlock.Text("")) }
            for (chunk in part.chunked(5)) {
                delay(30)
                updateLast<ReplyBlock.Text>(sessionId, replyId) { it.copy(text = it.text + chunk) }
            }
            if (partCitations.isNotEmpty()) {
                updateLast<ReplyBlock.Text>(sessionId, replyId) { it.copy(citations = partCitations) }
            }
        }
    }

    // ---------- 树操作 ----------

    private fun mutate(sessionId: String, transform: (Session) -> Session) {
        store.update { map ->
            val session = map[sessionId] ?: return@update map
            map + (sessionId to transform(session).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    private fun updateReply(sessionId: String, replyId: String, transform: (Message.Assistant) -> Message.Assistant) {
        store.update { map ->
            val session = map[sessionId] ?: return@update map
            map + (sessionId to session.copy(messages = session.messages.map { if (it.id == replyId && it is Message.Assistant) transform(it) else it }))
        }
    }

    private inline fun <reified B : ReplyBlock> updateLast(sessionId: String, replyId: String, crossinline transform: (B) -> B) {
        updateReply(sessionId, replyId) { reply ->
            val index = reply.blocks.indexOfLast { it is B }
            if (index < 0) reply else reply.copy(blocks = reply.blocks.toMutableList().also { it[index] = transform(it[index] as B) })
        }
    }

    private fun findMessage(sessionId: String, messageId: String) =
        store.value[sessionId]?.messages?.firstOrNull { it.id == messageId }

    private fun buildState(session: Session, busy: Boolean): ConversationState {
        val path = currentPath(session)
        val turns = path.mapIndexed { index, message ->
            val siblings = session.messages.filter { it.parentId == message.parentId }
            val isLast = index == path.lastIndex
            TurnView(
                message = message,
                siblingIndex = siblings.indexOfFirst { it.id == message.id },
                siblingCount = siblings.size,
                canRegenerate = message is Message.Assistant && isLast && !busy &&
                    path.getOrNull(index - 1) is Message.User && message.stopReason != StopReason.Refusal,
                canContinue = message is Message.Assistant && isLast && !busy && message.prefix &&
                    (message.status == ReplyStatus.Stopped || message.stopReason == StopReason.MaxTokens),
            )
        }
        return ConversationState(session.toSummary(), turns, busy)
    }

    companion object {
        private fun newId() = UUID.randomUUID().toString()

        /** 当前路径：从选中的根开始，沿 selectedChildId（缺省取最后一个子节点）向下。 */
        fun currentPath(session: Session): List<Message> {
            val byParent = session.messages.groupBy { it.parentId }
            val path = mutableListOf<Message>()
            var current = session.messages.firstOrNull { it.id == session.rootMessageId }
                ?: byParent[null]?.lastOrNull()
            while (current != null) {
                path += current
                val children = byParent[current.id].orEmpty()
                current = children.firstOrNull { it.id == current.selectedChildId } ?: children.lastOrNull()
            }
            return path
        }

        /** 加入新消息并让父节点选中它 */
        private fun Session.attach(message: Message, parentId: String?): Session =
            copy(messages = messages + message).select(message.id, parentId)

        private fun Session.select(messageId: String, parentId: String?): Session =
            if (parentId == null) {
                copy(rootMessageId = messageId)
            } else {
                copy(
                    messages = messages.map { m ->
                        if (m.id != parentId) {
                            m
                        } else {
                            when (m) {
                                is Message.User -> m.copy(selectedChildId = messageId)
                                is Message.Assistant -> m.copy(selectedChildId = messageId)
                            }
                        }
                    },
                )
            }

        private fun sampleResults() = listOf(
            WebSearchResult(
                title = "Jetpack Compose 概览",
                url = "https://developer.android.com/compose",
                snippet = "Jetpack Compose 是用于构建原生 Android 界面的现代工具包。",
                siteName = "Android Developers",
                pageAge = "2026-10-02T08:00:00Z",
            ),
            WebSearchResult(
                title = "Material Design 3",
                url = "https://m3.material.io/",
                snippet = "Material 3 是 Google 最新的开源设计系统。",
                siteName = "Material Design",
            ),
            WebSearchResult(
                title = "Kotlin 协程指南",
                url = "https://kotlinlang.org/docs/coroutines-guide.html",
                snippet = "协程让异步代码像同步代码一样书写。",
            ),
        )

        private const val THINKING =
            "用户想了解这个问题。先梳理关键概念，再给出结构化的回答。\n\n需要注意：回答要简洁，必要时给出示例代码。"

        private const val ANSWER =
            "这是一个**模拟回复**，用于在接入真实接口前调试界面。\n\n" +
                "## 要点\n\n- 流式输出会自动跟随到底部\n- 手动上滑后停止跟随\n- 可以停止、重新生成、编辑提问\n\n" +
                "```kotlin\nfun greet() = println(\"Hello\")\n```\n\n以上内容由 Fake 仓库生成。"

        private const val CONTINUATION = "\n\n（续写）补充一段内容，演示续写后追加的文本块。"

        private fun seedSessions(now: Long): List<Session> {
            val day = 86_400_000L
            fun simple(id: String, title: String, at: Long, question: String, answer: String): Session {
                val user = Message.User("$id-u", at, content = question, selectedChildId = "$id-a")
                val reply = Message.Assistant(
                    "$id-a", at + 1000, parentId = user.id, model = ChatModel.Flash,
                    status = ReplyStatus.Done, stopReason = StopReason.EndTurn,
                    blocks = listOf(ReplyBlock.Text(answer)),
                )
                return Session(id, title, at, at + 1000, listOf(user, reply), user.id)
            }

            // 多分支 + 思考 + 搜索
            val at = now - 3_600_000L
            val u1 = Message.User("demo-u1", at, content = "介绍一下 Jetpack Compose", selectedChildId = "demo-a2")
            val search = ReplyBlock.ServerToolUse(
                "srvtoolu_search_0_demo",
                "deepseek_web_search",
                ServerToolInput.Search(queries = listOf("Jetpack Compose 介绍")),
                ServerToolKind.Search,
            )
            val results = sampleResults()
            val a1 = Message.Assistant(
                "demo-a1", at + 1000, parentId = u1.id, model = ChatModel.Flash, status = ReplyStatus.Done,
                stopReason = StopReason.EndTurn, blocks = listOf(ReplyBlock.Text("第一个版本的回答：Compose 是声明式 UI 工具包。")),
            )
            val a2 = Message.Assistant(
                "demo-a2", at + 2000, parentId = u1.id, model = ChatModel.V4Pro, thinking = true, search = true,
                status = ReplyStatus.Done, stopReason = StopReason.EndTurn,
                blocks = listOf(
                    ReplyBlock.Thinking("先搜索官方资料，再总结核心特性。", startedAt = at + 2000, endedAt = at + 6200),
                    search,
                    ReplyBlock.WebSearchToolResult(search.id, results, kind = ServerToolKind.Search),
                    ReplyBlock.Text(
                        "Jetpack Compose 是 Android 的现代声明式 UI 工具包。",
                        citations = listOf(TextCitation(url = results[0].url, title = results[0].title)),
                    ),
                    ReplyBlock.Text("\n\n它与 Material 3 深度集成，配合 Kotlin 协程管理状态。"),
                ),
            )
            val branched = Session("demo", "Jetpack Compose 介绍", at, at + 2000, listOf(u1, a1, a2), u1.id)

            // 已停止、可续写
            val sAt = now - 5 * 3_600_000L
            val su = Message.User("stop-u", sAt, content = "写一首关于秋天的短诗", selectedChildId = "stop-a")
            val sa = Message.Assistant(
                "stop-a", sAt + 1000, parentId = su.id, model = ChatModel.Flash, prefix = true,
                status = ReplyStatus.Stopped, blocks = listOf(ReplyBlock.Text("秋风起，\n落叶")),
            )
            val stopped = Session("stopped", "秋天的短诗", sAt, sAt + 1000, listOf(su, sa), su.id)

            return listOf(
                branched,
                stopped,
                simple("yesterday", "周末出行计划", now - day - 600_000L, "周末去哪儿玩？", "可以考虑近郊徒步。"),
                simple("week", "Kotlin 协程问题", now - 4 * day, "Flow 和 Channel 的区别？", "Flow 是冷流，Channel 是热的。"),
                simple("month", "读书笔记", now - 20 * day, "总结一下这本书", "全书分为三部分。"),
                simple("old", "旧对话", now - 70 * day, "你好", "你好！有什么可以帮你？"),
                simple("older", "去年的对话", now - 400 * day, "新年快乐", "新年快乐！"),
            )
        }
    }
}
