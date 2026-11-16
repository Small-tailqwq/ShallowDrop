package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.domain.voice.voiceSystemPrompt

import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.contract.AttachmentStore
import io.github.smalltailqwq.shallowdrop.core.contract.ChatApi
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.contract.SessionStore
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ContextSummary
import io.github.smalltailqwq.shallowdrop.core.model.CreateMessageRequest
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.ReplyError
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.RequestTool
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.toSummary
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 会话状态与生成流程，对照 src/services/chat/store.ts。
 *
 * - 打开中或生成中的会话缓存在内存（[cache]）；生成期间以内存为准，流式增量只写内存，
 *   每 [persistIntervalMs]、块结束、message_delta 及回复收尾时才 upsertMessage 落盘。
 * - 生成运行在应用级 [scope]，界面离开不会中断；[stop] 取消协程即停止，回复记为 Stopped。
 * - 同一会话同时只能有一个生成；生成中拒绝发送、编辑、重新生成、续写与切换分支。
 * - 远端文件不删：导入、编辑、重新生成都会复用同一附件，同一服务端文件可能被多个会话引用。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatEngine(
    private val api: ChatApi,
    private val store: SessionStore,
    private val attachments: AttachmentStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val resolveLocation: suspend (io.github.smalltailqwq.shallowdrop.core.model.LocationPreference, Locale) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    /** latest_reminder 的时区 */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** 语言设置为跟随系统时使用的系统语言 */
    private val systemLocale: () -> Locale = { Locale.getDefault() },
    private val persistIntervalMs: Long = PERSIST_INTERVAL_MS,
    private val fileRetryBaseDelayMs: Long = FILE_RETRY_BASE_DELAY_MS,
    /**
     * 有附件既没有 file_id、本地也取不到原件时调用：尝试完成导入图片的恢复，返回图片是否已全部就绪。
     * 返回 false 表示原件只是暂时不可用（导入已提交、图片待恢复），本次不发请求。
     */
    private val restoreAttachments: suspend () -> Boolean = { true },
) : ChatRepository {

    companion object {
        /** 所有生成请求统一携带的助手身份说明 */
        const val SYSTEM_PROMPT = "该助手为DeepSeek，由深度求索公司创造。"

        /** 流式期间两次落盘的最小间隔 */
        const val PERSIST_INTERVAL_MS = 300L

        /** 服务端判定 file_id 失效后，重新补传并重发的最多次数（不含首次请求） */
        const val FILE_RETRY_LIMIT = 2

        /** 退避基数，第 n 次重试等待 2^n 倍；连续失败达到上限就放弃，把原始报错展示出来 */
        const val FILE_RETRY_BASE_DELAY_MS = 1000L

        /** 阅读链接始终开启，智能搜索开关控制是否另行声明搜索工具（含边想边搜）。 */
        fun serverTools(search: Boolean): List<RequestTool> =
            if (search) listOf(RequestTool.ReadUrl, RequestTool.WebSearch) else listOf(RequestTool.ReadUrl)

        /** 仍在生成中的回复（进程被杀或导出时未结束）按「已停止」处理，以会话 updatedAt 收尾思考计时 */
        fun settleInterrupted(session: Session): Session {
            if (session.messages.none { it.isUnfinished() }) return session
            return session.copy(
                messages = session.messages.map {
                    if (it is Message.Assistant && it.isUnfinished()) {
                        settleReply(it, ReplyStatus.Stopped, session.updatedAt)
                    } else {
                        it
                    }
                },
            )
        }

        private fun Message.isUnfinished() =
            this is Message.Assistant && (status == ReplyStatus.Pending || status == ReplyStatus.Streaming)
    }

    /** 一次生成；titleRequested 在手动重命名或标题写入后取消 */
    private class Generation(@Volatile var titleRequested: Boolean) {
        lateinit var job: Job
    }

    private data class Request(
        val history: List<Message>,
        val question: Message.User,
        val prefix: Message.Assistant? = null,
        /** 语音对话轮次，system 追加语音模式说明 */
        val voice: Boolean = false,
    )

    private val cache = MutableStateFlow<Map<String, Session>>(emptyMap())
    private val generations = ConcurrentHashMap<String, Generation>()
    private val generatingIds = MutableStateFlow<Set<String>>(emptySet())
    private val watchers = HashMap<String, Int>()

    /** 串行化会改动会话结构的操作，保证「检查是否在生成 → 开始生成」是原子的 */
    private val opMutex = Mutex()

    /** 串行化落盘，写入时总是取内存中的最新消息 */
    private val writeMutex = Mutex()

    /** 进行中的上传，按附件本地 id 去重 */
    private val uploads = ConcurrentHashMap<String, Deferred<Result<String>>>()

    /** 本进程内已上传成功的附件：本地 id → file_id；失效时清除 */
    private val uploaded = ConcurrentHashMap<String, String>()

    private val compaction = AsyncContextCompaction(scope, ::summarizeContext, ::installContextSummary,
        thresholds = { settings.settings.value.compactionThresholds },
        enabled = { settings.settings.value.compactionEnabled })

    init {
        scope.launch {
            settings.settings.map { it.compactionEnabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) compaction.clear()
            }
        }
    }

    private suspend fun installContextSummary(sessionId: String, anchorId: String, summary: ContextSummary): Boolean {
        var installed = false
        val updated = updateCached(sessionId) { session ->
            val path = visibleMessages(session)
            val index = path.indexOfFirst { it.id == anchorId }
            installed = settings.settings.value.compactionEnabled && index >= 0 && contextFingerprint(path.take(index + 1)) == summary.sourceHash
            if (!installed) session else session.copy(messages = session.messages.map { message ->
                if (message.id == anchorId && message is Message.Assistant) message.copy(contextSummary = summary) else message
            })
        }
        if (installed && updated != null) persistMessages(updated.second, setOf(anchorId))
        return installed && updated != null
    }

    private suspend fun summarizeContext(sessionId: String, history: List<Message>, model: ChatModel): String {
        val context = compactionContext(history, model)
        val question = Message.User("context-compaction", clock(), content = COMPACTION_PROMPT)
        val prepared = prepareFiles(sessionId, Request(context.history, question))
        val messages = context.copy(history = prepared.history).messages(question)
        val body = CreateMessageRequest(
            model = model,
            system = SYSTEM_PROMPT,
            messages = messages,
            thinking = false,
            tools = emptyList(),
            hasFiles = hasFileBlocks(messages),
            maxTokens = COMPACTION_MAX_OUTPUT_TOKENS,
        )
        var reply = Message.Assistant("context-summary", clock(), status = ReplyStatus.Pending)
        val indexes = HashMap<Int, Int>()
        var finished = false
        api.streamMessage(settings.apiKey().orEmpty(), body).collect { event ->
            reply = applyEvent(reply, event, clock(), indexes)
            if (event is MessageStreamEvent.MessageStop) finished = true
        }
        reply = settleReply(reply, ReplyStatus.Done, clock())
        check(finished && reply.stopReason == StopReason.EndTurn && isUsableReply(reply)) {
            "上下文摘要未完整生成，请重试。"
        }
        return replyText(reply)
    }

    /** 正在生成回复的会话 id */
    val generating: StateFlow<Set<String>> = generatingIds.asStateFlow()

    override val sessions: Flow<List<SessionSummary>> =
        store.observeSummaries().map { list -> list.sortedByDescending { it.updatedAt } }

    private fun isGenerating(sessionId: String) = generations.containsKey(sessionId)

    // ---------- 观察 ----------

    override fun observeConversation(sessionId: String): Flow<ConversationState?> = channelFlow {
        retain(sessionId)
        try {
            ensureLoaded(sessionId)
            launch { watchExternalChanges(sessionId) }
            launch {
                combine(
                    cache.map { it[sessionId] }.distinctUntilChanged(),
                    generatingIds.map { sessionId in it }.distinctUntilChanged(),
                ) { session, generating -> session?.let { conversationState(it, generating) } }
                    .distinctUntilChanged()
                    .collect { send(it) }
            }
            awaitClose()
        } finally {
            release(sessionId)
        }
    }

    /**
     * 库中出现更新的版本（如导入、另一入口修改）时重新读取；会话被删除时移出缓存。
     * 自己的写入不会比内存新，所以只比较 updatedAt 是否更大。生成期间以内存为准。
     */
    private suspend fun watchExternalChanges(sessionId: String) {
        var seen = false
        store.observeSummaries().collect { list ->
            if (isGenerating(sessionId)) return@collect
            val summary = list.find { it.id == sessionId }
            if (summary == null) {
                if (seen) cache.update { it - sessionId }
                return@collect
            }
            seen = true
            val cached = cache.value[sessionId]
            if (cached == null || summary.updatedAt > cached.updatedAt) reload(sessionId)
        }
    }

    private fun conversationState(session: Session, generating: Boolean): ConversationState {
        val path = visibleMessages(session)
        val positions = siblingPositions(session)
        val turns = path.mapIndexed { index, message ->
            val (siblingIndex, siblingCount) = positions[message.id] ?: (0 to 1)
            val afterQuestion = path.getOrNull(index - 1) is Message.User
            TurnView(
                message = message,
                siblingIndex = siblingIndex,
                siblingCount = siblingCount,
                canRegenerate = message is Message.Assistant &&
                    !generating &&
                    index == path.lastIndex &&
                    afterQuestion &&
                    message.stopReason != StopReason.Refusal,
                canContinue = message is Message.Assistant &&
                    !generating &&
                    afterQuestion &&
                    index == path.lastIndex &&
                    canContinueReply(message),
            )
        }
        return ConversationState(session.toSummary(), turns, generating)
    }

    private fun retain(sessionId: String) = synchronized(watchers) {
        watchers[sessionId] = (watchers[sessionId] ?: 0) + 1
    }

    private fun release(sessionId: String) {
        synchronized(watchers) {
            val count = (watchers[sessionId] ?: 1) - 1
            if (count > 0) watchers[sessionId] = count else watchers.remove(sessionId)
        }
        maybeEvict(sessionId)
    }

    /** 没有界面观察且不在生成的会话移出内存 */
    private fun maybeEvict(sessionId: String) {
        val watched = synchronized(watchers) { sessionId in watchers }
        if (!watched && !isGenerating(sessionId)) cache.update { it - sessionId }
    }

    // ---------- 缓存与落盘 ----------

    private suspend fun ensureLoaded(sessionId: String): Session? {
        cache.value[sessionId]?.let { return it }
        val stored = store.load(sessionId) ?: return null
        val settled = if (isGenerating(sessionId)) stored else settleInterrupted(stored)
        var result = settled
        cache.update { current ->
            val existing = current[sessionId]
            if (existing != null) {
                result = existing
                current
            } else {
                result = settled
                current + (sessionId to settled)
            }
        }
        if (result === settled && settled !== stored) persistDiff(stored, settled)
        return result
    }

    private suspend fun reload(sessionId: String) {
        val stored = store.load(sessionId) ?: return
        if (isGenerating(sessionId)) return
        val settled = settleInterrupted(stored)
        cache.update { it + (sessionId to settled) }
        if (settled !== stored) persistDiff(stored, settled)
    }

    /** 原子更新缓存中的会话；不在缓存时返回 null */
    private fun updateCached(sessionId: String, update: (Session) -> Session): Pair<Session, Session>? {
        while (true) {
            val current = cache.value
            val old = current[sessionId] ?: return null
            val new = update(old)
            if (new === old) return old to old
            if (cache.compareAndSet(current, current + (sessionId to new))) return old to new
        }
    }

    /** 缓存中有则更新缓存并落盘，否则读库修改后落盘 */
    private suspend fun modifySession(sessionId: String, update: (Session) -> Session) {
        val cached = updateCached(sessionId, update)
        if (cached != null) {
            persistDiff(cached.first, cached.second)
            return
        }
        val stored = store.load(sessionId) ?: return
        persistDiff(stored, update(stored))
    }

    /** 写入变化的消息；只有 rootMessageId / updatedAt 变化时借根消息写入元数据 */
    private suspend fun persistDiff(old: Session, new: Session) {
        if (old === new) return
        val oldById = old.messages.associateBy { it.id }
        val changed = new.messages.filter { oldById[it.id] != it }.mapTo(LinkedHashSet()) { it.id }
        val metaChanged = old.rootMessageId != new.rootMessageId || old.updatedAt != new.updatedAt
        persistMessages(new, changed, metaChanged)
    }

    private suspend fun persistMessages(snapshot: Session, ids: Set<String>, metaChanged: Boolean = false) {
        writeMutex.withLock {
            // 取写入时的最新内容，避免并发更新的写入顺序与内存顺序不一致
            val session = cache.value[snapshot.id] ?: snapshot
            val targets = if (ids.isEmpty()) {
                if (!metaChanged) return
                listOfNotNull(session.messages.find { it.id == session.rootMessageId } ?: session.messages.firstOrNull())
            } else {
                session.messages.filter { it.id in ids }
            }
            for (message in targets) {
                store.upsertMessage(session.id, message, session.updatedAt, session.rootMessageId)
            }
        }
    }

    /** 用最新回复替换内存中的消息，保留分支字段；persist 为 true 时落盘 */
    private suspend fun writeReply(sessionId: String, reply: Message.Assistant, persist: Boolean) {
        val updated = updateCached(sessionId) { session ->
            session.copy(
                messages = session.messages.map {
                    if (it.id == reply.id) reply.copy(parentId = it.parentId, selectedChildId = it.selectedChildId) else it
                },
            )
        }
        if (persist && updated != null) {
            try {
                persistMessages(updated.second, setOf(reply.id))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 写库失败不中断生成，内存仍是最新内容，下次落盘会覆盖
            }
        }
    }

    // ---------- 操作 ----------

    /** 按本轮开关创建回复：深度思考对应 thinking，智能搜索控制 deepseek_web_search。 */
    private fun createReply(options: SendOptions) = Message.Assistant(
        id = idGenerator(),
        createdAt = clock(),
        model = options.model,
        thinking = options.thinking,
        search = options.search,
        prefix = true,
        status = ReplyStatus.Pending,
    )

    private fun requireIdle(sessionId: String) {
        check(!isGenerating(sessionId)) { "Session $sessionId is generating" }
    }

    /** 发送新提问；text 去除首尾空白，文字与附件不能同时为空。生成中的会话抛 IllegalStateException。 */
    override suspend fun send(
        sessionId: String?,
        text: String,
        files: List<FileAttachment>,
        options: SendOptions,
    ): String {
        val content = text.trim()
        require(content.isNotEmpty() || files.isNotEmpty()) { "Message is empty" }
        return opMutex.withLock {
            val now = clock()
            val current = if (sessionId == null) {
                null
            } else {
                requireIdle(sessionId)
                ensureLoaded(sessionId) ?: throw IllegalArgumentException("Session $sessionId not found")
            }
            val base = current ?: Session(id = idGenerator(), title = "", createdAt = now, updatedAt = now)
            val history = visibleMessages(base)
            val question = Message.User(id = idGenerator(), createdAt = now, content = content, files = files)
            val reply = createReply(options)
            val build = { session: Session ->
                appendBranch(session, history.lastOrNull()?.id, listOf(question, reply)).copy(updatedAt = now)
            }
            if (current == null) {
                val session = build(base)
                cache.update { it + (session.id to session) }
                store.save(session)
            } else {
                val (old, new) = updateCached(base.id, build) ?: error("Session ${base.id} not loaded")
                persistDiff(old, new)
            }
            startGeneration(base.id, reply, Request(history, question, voice = options.voice), titleRequested = base.title.isEmpty())
            base.id
        }
    }

    override fun stop(sessionId: String) {
        generations[sessionId]?.job?.cancel()
    }

    /** 重新生成当前路径的最后一条回答，在原问题下新增版本。 */
    override suspend fun regenerate(sessionId: String, assistantMessageId: String, options: SendOptions) {
        opMutex.withLock {
            if (isGenerating(sessionId)) return
            val session = ensureLoaded(sessionId) ?: return
            val path = visibleMessages(session)
            val index = path.indexOfFirst { it.id == assistantMessageId }
            if (index != path.lastIndex) return
            val question = path.getOrNull(index - 1) as? Message.User ?: return
            val previous = path.getOrNull(index) as? Message.Assistant ?: return
            if (previous.stopReason == StopReason.Refusal) return
            val reply = createReply(options)
            val (old, new) = updateCached(sessionId) {
                appendBranch(it, question.id, listOf(reply)).copy(updatedAt = clock())
            } ?: return
            persistDiff(old, new)
            startGeneration(sessionId, reply, Request(path.subList(0, index - 1), question, voice = options.voice), new.title.isEmpty())
        }
    }

    /** 编辑提问后新增同父版本，保留原文件；旧问题的所有回答与后续对话仍可切回。 */
    override suspend fun editAndResend(sessionId: String, userMessageId: String, text: String, options: SendOptions) {
        opMutex.withLock {
            if (isGenerating(sessionId)) return
            val session = ensureLoaded(sessionId) ?: return
            val path = visibleMessages(session)
            val index = path.indexOfFirst { it.id == userMessageId }
            val previous = path.getOrNull(index) as? Message.User ?: return
            val content = text.trim()
            if (content.isEmpty() && previous.files.isEmpty()) return
            val now = clock()
            val question = Message.User(id = idGenerator(), createdAt = now, content = content, files = previous.files)
            val reply = createReply(options)
            val (old, new) = updateCached(sessionId) {
                appendBranch(it, path.getOrNull(index - 1)?.id, listOf(question, reply)).copy(updatedAt = now)
            } ?: return
            persistDiff(old, new)
            startGeneration(sessionId, reply, Request(path.subList(0, index), question, voice = options.voice), new.title.isEmpty())
        }
    }

    /** 继续最后一条未完成的回答：保留原消息与开关，仅将新流的内容追加到原块之后。 */
    override suspend fun continueReply(sessionId: String, assistantMessageId: String) {
        opMutex.withLock {
            if (isGenerating(sessionId)) return
            val session = ensureLoaded(sessionId) ?: return
            val path = visibleMessages(session)
            val question = path.getOrNull(path.size - 2) as? Message.User ?: return
            val previous = path.lastOrNull() as? Message.Assistant ?: return
            if (previous.id != assistantMessageId || !canContinueReply(previous)) return
            val reply = previous.copy(
                // 未记录模型的回复，续写时使用当前默认模型
                model = previous.model ?: settings.settings.value.model,
                status = ReplyStatus.Pending,
                stopReason = null,
                stopDetails = null,
                error = null,
            )
            val (old, new) = updateCached(sessionId) { s ->
                s.copy(
                    updatedAt = clock(),
                    messages = s.messages.map { if (it.id == reply.id) reply else it },
                )
            } ?: return
            persistDiff(old, new)
            startGeneration(
                sessionId,
                reply,
                Request(path.subList(0, path.size - 2), question, prefix = previous),
                new.title.isEmpty(),
            )
        }
    }

    /**
     * 附件补传失败后重试：保留原回答位置，重新补传并请求。
     * 界面在当前路径末条为 retryable 的 Upload 错误时提供入口（契约暂未包含，见集成说明）。
     */
    suspend fun retryUpload(sessionId: String, assistantMessageId: String) {
        opMutex.withLock {
            if (isGenerating(sessionId)) return
            val session = ensureLoaded(sessionId) ?: return
            val path = visibleMessages(session)
            val index = path.indexOfFirst { it.id == assistantMessageId }
            val question = path.getOrNull(index - 1) as? Message.User ?: return
            val previous = path.getOrNull(index) as? Message.Assistant ?: return
            if (previous.error?.kind != ReplyErrorKind.Upload) return
            val reply = previous.copy(status = ReplyStatus.Pending, error = null)
            val (old, new) = updateCached(sessionId) { s ->
                s.copy(messages = s.messages.map { if (it.id == reply.id) reply else it })
            } ?: return
            persistDiff(old, new)
            startGeneration(sessionId, reply, Request(path.subList(0, index - 1), question), new.title.isEmpty())
        }
    }

    /** 切换到 messageId 的第 index 个兄弟版本；仅限当前路径上的节点，生成中不可切换。 */
    override suspend fun switchBranch(sessionId: String, messageId: String, index: Int) {
        opMutex.withLock {
            if (isGenerating(sessionId)) return
            val session = ensureLoaded(sessionId) ?: return
            val target = siblingsOf(session, messageId).getOrNull(index) ?: return
            val (old, new) = updateCached(sessionId) { selectVersion(it, target.id) } ?: return
            persistDiff(old, new)
        }
    }

    override suspend fun addAttachment(uri: Uri): FileAttachment = attachments.importFromUri(uri)

    /** 手动重命名后取消本轮自动标题写入，包含主动清空标题的情况。 */
    override suspend fun rename(sessionId: String, title: String) {
        generations[sessionId]?.titleRequested = false
        updateCached(sessionId) { it.copy(title = title) }
        store.rename(sessionId, title)
    }

    /** 删除会话：先停止生成并等待收尾落盘完成，避免删除后又被写回。 */
    override suspend fun delete(sessionId: String) {
        opMutex.withLock {
            generations[sessionId]?.job?.cancelAndJoin()
            compaction.cancel(sessionId)
            store.delete(sessionId)
            cache.update { it - sessionId }
        }
    }

    override suspend fun searchMessages(query: String) = store.searchMessages(query)

    override suspend fun deleteAll() {
        opMutex.withLock {
            generations.values.toList().forEach { it.job.cancelAndJoin() }
            compaction.clear()
            store.deleteAll()
            cache.value = emptyMap()
            uploaded.clear()
        }
    }

    /**
     * 整体替换本地数据（历史导入）：先停止全部生成，期间禁止其他会话操作，完成后刷新缓存。
     * 对应 Web 版导入时「停止生成、导入期间禁止修改会话」。
     */
    suspend fun <T> replaceAll(block: suspend () -> T): T {
        try {
            return opMutex.withLock {
                generations.values.toList().forEach { it.job.cancelAndJoin() }
                compaction.clear()
                block()
            }
        } finally {
            // 失败或取消时数据也可能已被替换，一律刷新
            withContext(NonCancellable) { invalidateCache() }
        }
    }

    /** 启动时把库中残留的 Pending / Streaming 回复标为 Stopped（进程被杀时未能收尾）。 */
    suspend fun markInterruptedOnStartup() {
        for (summary in store.observeSummaries().first()) {
            if (isGenerating(summary.id)) continue
            val stored = store.load(summary.id) ?: continue
            val settled = settleInterrupted(stored)
            if (settled === stored) continue
            updateCached(summary.id) { settleInterrupted(it) }
            persistDiff(stored, settled)
        }
    }

    /** 历史导入或外部整体替换数据后调用：丢弃内存中不在生成的会话，观察中的会话重新读取。 */
    suspend fun invalidateCache() {
        for (id in cache.value.keys) {
            if (isGenerating(id)) continue
            val watched = synchronized(watchers) { id in watchers }
            if (watched) {
                if (store.load(id) == null) cache.update { it - id } else reload(id)
            } else {
                cache.update { it - id }
            }
        }
        uploaded.clear()
    }

    // ---------- 生成 ----------

    private fun startGeneration(sessionId: String, initial: Message.Assistant, request: Request, titleRequested: Boolean) {
        val generation = Generation(titleRequested)
        generations[sessionId] = generation
        generatingIds.update { it + sessionId }
        // ATOMIC：调度前就被停止（发送后立即 stop）也会进入协程体，
        // 由 runGeneration 把 Pending 回复收尾为 Stopped 并落盘后再解除生成标记。
        // LAZY 启动前取消会跳过协程体，只剩 invokeOnCompletion 清标记，回复永远停在 Pending。
        val job = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                runGeneration(sessionId, initial, request, generation)
            } finally {
                finishGeneration(sessionId, generation)
            }
        }
        generation.job = job
        // 兜底：协程体异常退出时也要解除生成状态；按 generation 身份移除，不会误清后续任务
        job.invokeOnCompletion { finishGeneration(sessionId, generation) }
    }

    private fun finishGeneration(sessionId: String, generation: Generation) {
        if (generations.remove(sessionId, generation)) {
            generatingIds.update { it - sessionId }
            maybeEvict(sessionId)
        }
    }

    /**
     * 请求并消费流：先补齐附件的 serverId，再发请求；完成、失败或停止后落盘。
     * 补传失败时回复记为 Upload 错误、不发请求，可重试。
     * 服务端判定 file_id 失效时重新补传后重发，退避重试且次数有上限，超过则按原始报错收尾。
     */
    private suspend fun runGeneration(
        sessionId: String,
        initial: Message.Assistant,
        request: Request,
        generation: Generation,
    ) {
        var reply = initial
        var lastPersist = clock()
        try {
            var attempt = 0
            while (true) {
                // 附件补齐前出错属于上传失败，请求还没有发出
                var preparing = false
                var receivedContent = false
                try {
                    // ATOMIC 启动不检查取消：已被停止时不再刷新上下文、不发请求
                    currentCoroutineContext().ensureActive()
                    val model = initial.model ?: settings.settings.value.model
                    val currentRequest = refresh(sessionId, request)
                    val context = compaction.prepare(sessionId, currentRequest.history, currentRequest.question, model)
                    preparing = true
                    val prepared = prepareFiles(sessionId, currentRequest.copy(history = context.history))
                    preparing = false
                    // 附件就绪后获取本轮信息；续写沿用原回答快照，重新生成的快照独立保存。
                    val latestReminder = if (prepared.prefix != null) {
                        prepared.prefix.latestReminder
                    } else {
                        val currentSettings = settings.settings.value
                        val locale = ReminderLocale.resolve(currentSettings.locale, systemLocale())
                        val location = settings.settings.map { it.location }.distinctUntilChanged()
                            .mapLatest { resolveLocation(it, locale.javaLocale) }.first()
                        createLatestReminder(locale, clock(), zone(), location)
                    }
                    reply = reply.copy(latestReminder = latestReminder, contextSummaryId = context.summary?.sourceHash)
                    writeReply(sessionId, reply, persist = true)
                    val messages = context.copy(history = prepared.history).messages(
                        prepared.question,
                        prepared.prefix,
                        initial.thinking,
                        latestReminder,
                    )
                    val body = CreateMessageRequest(
                        model = model,
                        system = if (request.voice) {
                            SYSTEM_PROMPT + "\n\n" + voiceSystemPrompt(ReminderLocale.resolve(settings.settings.value.locale, systemLocale()))
                        } else {
                            SYSTEM_PROMPT
                        },
                        messages = messages,
                        thinking = initial.thinking,
                        tools = serverTools(initial.search),
                        title = generation.titleRequested,
                        hasFiles = hasFileBlocks(messages),
                    )
                    val apiKey = settings.apiKey().orEmpty()
                    val blockIndexes = HashMap<Int, Int>()
                    api.streamMessage(apiKey, body).collect { event ->
                        if (event is MessageStreamEvent.ContentBlockStart) receivedContent = true
                        if (event is MessageStreamEvent.MessageDelta) applyTitle(sessionId, event, generation)
                        val now = clock()
                        reply = applyEvent(reply, event, now, blockIndexes)
                        val persist = event is MessageStreamEvent.ContentBlockStop ||
                            event is MessageStreamEvent.MessageDelta ||
                            now - lastPersist >= persistIntervalMs
                        if (persist) lastPersist = now
                        writeReply(sessionId, reply, persist)
                    }
                    reply = settleReply(reply, ReplyStatus.Done, clock())
                    break
                } catch (e: Throwable) {
                    if (e is CancellationException || !currentCoroutineContext().isActive) {
                        reply = settleReply(reply, ReplyStatus.Stopped, clock())
                        break
                    }
                    if (preparing) {
                        reply = settleReply(
                            reply,
                            ReplyStatus.Error,
                            clock(),
                            ReplyError(
                                kind = ReplyErrorKind.Upload,
                                message = e.message.orEmpty(),
                                status = (e as? ApiException)?.status,
                                code = describeUploadError(e),
                                retryable = isRetryableApiError(e),
                            ),
                        )
                        break
                    }
                    // 附件失效：重新补传后重发。收到过内容块（含丢弃的客户端工具）后不自动重发。
                    if (attempt < FILE_RETRY_LIMIT && !receivedContent && reuploadInvalidatedFiles(sessionId, request, e)) {
                        delay(fileRetryBaseDelayMs shl attempt)
                        attempt++
                        continue
                    }
                    reply = when {
                        // 已收到 message_delta 的结束原因，只是缺了结尾的 message_stop
                        e is ApiException && e.kind == ReplyErrorKind.Interrupted && reply.stopReason != null ->
                            settleReply(reply, ReplyStatus.Done, clock())
                        e is ApiException -> settleReply(
                            reply,
                            ReplyStatus.Error,
                            clock(),
                            ReplyError(kind = e.kind, message = e.message, status = e.status, code = e.code),
                        )
                        else -> settleReply(
                            reply,
                            ReplyStatus.Error,
                            clock(),
                            ReplyError(kind = ReplyErrorKind.Protocol, message = e.message ?: e.toString()),
                        )
                    }
                    break
                }
            }
        } catch (e: Throwable) {
            // 退避等待中被停止，或补传失效处理本身出错
            reply = if (e is CancellationException || !currentCoroutineContext().isActive) {
                settleReply(reply, ReplyStatus.Stopped, clock())
            } else {
                settleReply(
                    reply,
                    ReplyStatus.Error,
                    clock(),
                    ReplyError(kind = ReplyErrorKind.Protocol, message = e.message ?: e.toString()),
                )
            }
        } finally {
            withContext(NonCancellable) { writeReply(sessionId, reply, persist = true) }
        }
        if (reply.status == ReplyStatus.Done && currentCoroutineContext().isActive) {
            cache.value[sessionId]?.let { session ->
                compaction.maybeStart(sessionId, visibleMessages(session), initial.model ?: settings.settings.value.model)
            }
        }
    }

    /** 首轮请求开启标题：正常结束且标题非空、会话仍无标题、未被手动重命名时写入。 */
    private suspend fun applyTitle(sessionId: String, event: MessageStreamEvent.MessageDelta, generation: Generation) {
        val title = event.title?.trim().orEmpty()
        if ((event.stopReason != StopReason.EndTurn && event.stopReason != StopReason.MaxTokens) ||
            title.isEmpty() ||
            !generation.titleRequested ||
            !currentCoroutineContext().isActive
        ) {
            return
        }
        generation.titleRequested = false
        var applied = false
        updateCached(sessionId) { session ->
            if (session.title.isEmpty()) {
                applied = true
                session.copy(title = title)
            } else {
                applied = false
                session
            }
        }
        if (applied) {
            try {
                store.rename(sessionId, title)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 标题写库失败不影响回复
            }
        }
    }

    // ---------- 附件补传 ----------

    /** 用会话里的最新消息替换请求快照：补传与失效重置都会改附件的 serverId */
    private fun refresh(sessionId: String, request: Request): Request {
        val session = cache.value[sessionId] ?: return request
        val byId = session.messages.associateBy { it.id }
        return request.copy(
            history = request.history.map { byId[it.id] ?: it },
            question = byId[request.question.id] as? Message.User ?: request.question,
        )
    }

    /**
     * 发送前补齐附件的 serverId。
     * - 本轮提问的附件并行补齐，任一失败则整体失败，已成功的照常写回，重试时复用成功结果；
     * - 历史轮次（仅进入上下文的）中没有 serverId 的附件，多为导入的图片：从最新的消息往前逐个补传，
     *   失败静默跳过，该附件不进入本次请求，下次发送时再试。
     * 停止生成立即生效；上传本身在应用作用域中继续，完成后照常写回 serverId。
     */
    private suspend fun prepareFiles(sessionId: String, original: Request): Request {
        val request = refresh(sessionId, original)
        // 原件取不到时先确认不是「导入图片待恢复」：恢复完成则照常补传，仍未就绪则不发请求，避免悄悄漏掉图片。
        // 恢复就绪后仍没有原件的附件（归档里本来就缺）按原规则只展示、不进入请求。
        if (lacksOriginal(request) && !restoreAttachments()) throw AttachmentsRestoringException()
        val apiKey = settings.apiKey().orEmpty()
        val serverIds = HashMap<String, String>()
        val tasks = request.question.files.map { it to ensureServerId(sessionId, apiKey, it) }
        var failure: Throwable? = null
        for ((file, task) in tasks) {
            task.await()
                .onSuccess { if (it.isNotEmpty()) serverIds[file.id] = it }
                .onFailure { if (failure == null) failure = it }
        }
        saveServerIds(sessionId, serverIds)
        failure?.let { throw if (it is CancellationException) IllegalStateException(it.message, it) else it }

        val pending = LinkedHashMap<String, FileAttachment>()
        for ((user, _) in contextRounds(request.history).asReversed()) {
            for (file in user.files) {
                if (file.serverId == null && file.id !in serverIds && file.id !in pending) pending[file.id] = file
            }
        }
        for (file in pending.values) {
            currentCoroutineContext().ensureActive()
            ensureServerId(sessionId, apiKey, file).await().onSuccess {
                if (it.isNotEmpty()) {
                    serverIds[file.id] = it
                    saveServerIds(sessionId, mapOf(file.id to it))
                }
            }
        }
        // 用补齐后的会话重新取历史与提问，保证请求里都是 serverId
        return refresh(sessionId, request)
    }

    /** 本次请求会用到的附件里，是否有既没有 file_id、本地也没有原件的 */
    private fun lacksOriginal(request: Request): Boolean {
        val files = request.question.files + contextRounds(request.history).flatMap { it.first.files }
        return files.any { it.serverId == null && uploaded[it.id] == null && attachments.file(it.id) == null }
    }

    /**
     * 附件的服务端 id：已有直接用；正在上传的等待同一任务；否则读取本地原件补传。
     * 本地也没有原件（导入的文档附件只有元数据）时为空串，该附件只展示、不进入请求。
     */
    private fun ensureServerId(sessionId: String, apiKey: String, file: FileAttachment): Deferred<Result<String>> {
        file.serverId?.let { return CompletableDeferred(Result.success(it)) }
        uploaded[file.id]?.let { return CompletableDeferred(Result.success(it)) }
        uploads[file.id]?.let { return it }
        val local = attachments.file(file.id) ?: return CompletableDeferred(Result.success(""))
        val task = scope.async(start = CoroutineStart.LAZY) {
            try {
                val result = api.uploadFile(
                    apiKey,
                    file.name,
                    uploadMimeType(file.name, file.mimeType),
                    uploadPurpose(file.name, file.isImage),
                ) { local.inputStream() }
                uploaded[file.id] = result.id
                withContext(NonCancellable) {
                    runCatching { attachments.setServerId(file.id, result.id) }
                    runCatching { saveServerIds(sessionId, mapOf(file.id to result.id)) }
                }
                Result.success(result.id)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val existing = uploads.putIfAbsent(file.id, task)
        if (existing != null) return existing
        task.invokeOnCompletion { uploads.remove(file.id, task) }
        task.start()
        return task
    }

    /** 把补传得到的 serverId 写回会话里所有引用同一附件的消息（编辑、重新生成会共用附件） */
    private suspend fun saveServerIds(sessionId: String, serverIds: Map<String, String>) {
        if (serverIds.isEmpty()) return
        modifySession(sessionId) { session ->
            if (session.messages.none { m -> m is Message.User && m.files.any { serverIds[it.id]?.let { id -> id != it.serverId } == true } }) {
                return@modifySession session
            }
            session.copy(
                messages = session.messages.map { message ->
                    if (message is Message.User && message.files.any { it.id in serverIds }) {
                        message.copy(files = message.files.map { f -> serverIds[f.id]?.let { f.copy(serverId = it) } ?: f })
                    } else {
                        message
                    }
                },
            )
        }
    }

    /** 本次请求实际会用到的服务端 file_id */
    private fun collectServerFileIds(request: Request): Set<String> {
        val ids = LinkedHashSet<String>()
        request.question.files.mapNotNullTo(ids) { it.serverId }
        for ((user, _) in contextRounds(request.history)) user.files.mapNotNullTo(ids) { it.serverId }
        return ids
    }

    /**
     * 服务端判定 file_id 失效时，清掉对应附件的 serverId，下一次补齐会重新读取本地原件上传。
     * 报错里点名了 file_id 就只重置这些，没点名（如 invalid file_id）则重置本次请求用到的全部附件。
     */
    private suspend fun reuploadInvalidatedFiles(sessionId: String, request: Request, error: Throwable): Boolean {
        if (!isFileInvalidatedError(error)) return false
        val mentioned = extractFileIds(error.message.orEmpty())
        val targets = if (mentioned.isNotEmpty()) mentioned.toSet() else collectServerFileIds(refresh(sessionId, request))
        if (targets.isEmpty()) return false
        val localIds = HashSet<String>()
        val updated = updateCached(sessionId) { session ->
            localIds.clear()
            session.copy(
                messages = session.messages.map { message ->
                    if (message !is Message.User || message.files.none { it.serverId in targets }) {
                        message
                    } else {
                        message.copy(
                            files = message.files.map { file ->
                                if (file.serverId != null && file.serverId in targets) {
                                    localIds.add(file.id)
                                    file.copy(serverId = null)
                                } else {
                                    file
                                }
                            },
                        )
                    }
                },
            )
        } ?: return false
        if (localIds.isEmpty()) return false
        localIds.forEach { uploaded.remove(it) }
        persistDiff(updated.first, updated.second)
        return true
    }
}
