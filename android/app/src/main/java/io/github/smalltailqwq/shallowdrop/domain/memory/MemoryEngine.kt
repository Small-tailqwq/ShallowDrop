package io.github.smalltailqwq.shallowdrop.domain.memory

import io.github.smalltailqwq.shallowdrop.core.contract.ChatApi
import io.github.smalltailqwq.shallowdrop.core.contract.MemoryRepository
import io.github.smalltailqwq.shallowdrop.core.contract.MemoryStore
import io.github.smalltailqwq.shallowdrop.core.contract.SessionStore
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.CreateMessageRequest
import io.github.smalltailqwq.shallowdrop.core.model.MemoryChange
import io.github.smalltailqwq.shallowdrop.core.model.MemoryChangeType
import io.github.smalltailqwq.shallowdrop.core.model.MemoryEntry
import io.github.smalltailqwq.shallowdrop.core.model.MemoryExchange
import io.github.smalltailqwq.shallowdrop.core.model.MemoryFullException
import io.github.smalltailqwq.shallowdrop.core.model.MemoryOrigin
import io.github.smalltailqwq.shallowdrop.core.model.MemoryQuote
import io.github.smalltailqwq.shallowdrop.core.model.MemoryRecord
import io.github.smalltailqwq.shallowdrop.core.model.MemoryState
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import io.github.smalltailqwq.shallowdrop.domain.chat.applyEvent
import io.github.smalltailqwq.shallowdrop.domain.chat.replyText
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 跨对话记忆的读写流程。
 *
 * - 读取：[systemBlock] 给出追加到对话 system 的记忆块；记忆关闭或为空时为 null。
 * - 写入：回复结束后把会话标为待整理（[onReplyFinished]），闲置 [idleMs] 后只把上次整理之后的新消息
 *   送去提取；在别的会话发消息前（[settleOthers]）和应用启动时（[start]）也会整理，
 *   让刚说过的内容能进入下一个窗口。待整理标记随记忆一起落盘，进程被杀后下次启动补做。
 * - 提取与提问固定使用 deepseek-flash，关闭思考与工具，单独请求 API，会产生额外用量。
 * - 篇幅：全部记忆的总字数有上限；每次整理后清理过期的短期安排，接近上限时请模型合并一次（[tidy]）。
 * - 请求失败时保留待整理标记，下次触发重试；输出无法解析时放弃这一段，避免反复消耗。
 * - 关闭记忆后不再读取、不再整理，已有记忆保留，仍可查看、修改和提问。
 */
class MemoryEngine(
    private val api: ChatApi,
    private val sessions: SessionStore,
    private val store: MemoryStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val idleMs: Long = IDLE_MS,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) : MemoryRepository {
    companion object {
        /** 会话闲置多久后整理 */
        const val IDLE_MS = 3 * 60_000L

        /** 发消息前等待其他会话整理完成的上限；超时后整理继续在后台进行 */
        const val SETTLE_TIMEOUT_MS = 10_000L

        /** 一次整理最多送出的消息数（取最新的） */
        const val MAX_TURNS = 40

        const val RECORD_LIMIT = 500
    }

    private val state = MutableStateFlow(MemoryState())
    override val memory: StateFlow<MemoryState> = state.asStateFlow()

    private val updatingState = MutableStateFlow(false)
    override val updating: StateFlow<Boolean> = updatingState.asStateFlow()

    private val loading = scope.async(start = CoroutineStart.LAZY) { state.value = store.load() }

    /** 串行化记忆的修改与落盘 */
    private val stateMutex = Mutex()

    /** 同一时间只整理一个会话，后一次整理能看到前一次的结果 */
    private val extractMutex = Mutex()

    private val timers = ConcurrentHashMap<String, Job>()

    private val enabled get() = settings.settings.value.memoryEnabled

    init {
        scope.launch {
            settings.settings.map { it.memoryEnabled }.distinctUntilChanged().collect { on ->
                if (!on) timers.keys.toList().forEach { timers.remove(it)?.cancel() }
            }
        }
    }

    private suspend fun mutate(change: (MemoryState) -> MemoryState): MemoryState {
        loading.await()
        return stateMutex.withLock {
            val next = change(state.value)
            if (next != state.value) {
                store.save(next)
                state.value = next
            }
            next
        }
    }

    // ---------- 对话流程的接入点 ----------

    /** 应用启动：读取记忆，补做上次没来得及整理的会话。 */
    fun start() {
        scope.launch {
            loading.await()
            if (!enabled) return@launch
            try {
                expire()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("清理过期记忆失败", e)
            }
            state.value.pending.forEach { extract(it) }
        }
    }

    suspend fun systemBlock(): String? {
        if (!enabled) return null
        loading.await()
        return memorySystemBlock(state.value.entries, zone())
    }

    fun onReplyFinished(sessionId: String) {
        if (!enabled) return
        scope.launch {
            mutate { it.copy(pending = it.pending + sessionId) }
            timers.put(sessionId, scope.launch {
                delay(idleMs)
                extract(sessionId)
            })?.cancel()
        }
    }

    /** 在 [sessionId] 发请求前整理其他待整理的会话，最多等 [timeoutMs]。 */
    suspend fun settleOthers(sessionId: String, timeoutMs: Long = SETTLE_TIMEOUT_MS) {
        if (!enabled) return
        loading.await()
        val others = state.value.pending - sessionId
        if (others.isEmpty()) return
        val jobs = others.map { scope.launch { extract(it) } }
        withTimeoutOrNull(timeoutMs) { jobs.joinAll() }
    }

    /** 会话被删除（null 表示全部会话被删除或整体替换）：丢弃整理进度，已有记忆保留。 */
    suspend fun forgetSessions(sessionId: String?) {
        if (sessionId == null) {
            timers.keys.toList().forEach { timers.remove(it)?.cancel() }
            mutate { it.copy(cursors = emptyMap(), pending = emptySet()) }
        } else {
            timers.remove(sessionId)?.cancel()
            mutate { it.copy(cursors = it.cursors - sessionId, pending = it.pending - sessionId) }
        }
    }

    // ---------- 整理 ----------

    private suspend fun extract(sessionId: String) {
        val self = currentCoroutineContext()[Job]
        timers[sessionId]?.let { if (it !== self && timers.remove(sessionId, it)) it.cancel() }
        extractMutex.withLock {
            if (!enabled || sessionId !in state.value.pending) return
            updatingState.value = true
            try {
                extractLocked(sessionId)
                tidy()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 请求失败：保留待整理标记，下次触发时重试
                log("整理记忆失败", e)
            } finally {
                updatingState.value = false
            }
        }
    }

    private suspend fun extractLocked(sessionId: String) {
        val snapshot = sessions.loadConversation(sessionId)
        if (snapshot == null) {
            mutate { it.copy(cursors = it.cursors - sessionId, pending = it.pending - sessionId) }
            return
        }
        val path = snapshot.messages
        // 仍在生成的回复及其提问留到下次
        val unfinished = path.indexOfFirst { it is Message.Assistant && (it.status == ReplyStatus.Pending || it.status == ReplyStatus.Streaming) }
        val end = if (unfinished < 0) path.size else (unfinished - 1).coerceAtLeast(0)
        val start = path.indexOfFirst { it.id == state.value.cursors[sessionId] }.let { if (it in 0 until end) it + 1 else 0 }
        val fresh = path.subList(start.coerceAtMost(end), end)
        val turns = fresh.mapIndexedNotNull { index, message ->
            when (message) {
                // 「当前情况」快照保存在这条提问对应的回复上
                is Message.User -> message.content.takeIf { it.isNotBlank() }?.let {
                    MemoryTurn(true, it, message.id, message.createdAt, (fresh.getOrNull(index + 1) as? Message.Assistant)?.currentState)
                }
                is Message.Assistant -> replyText(message).takeIf { it.isNotBlank() }?.let { MemoryTurn(false, it) }
            }
        }.takeLast(MAX_TURNS).dropWhile { !it.fromUser }
        val cursor = fresh.lastOrNull()?.id
        fun done(current: MemoryState) = current.copy(
            pending = current.pending - sessionId,
            cursors = if (cursor == null) current.cursors else current.cursors + (sessionId to cursor),
        )
        if (turns.none { it.fromUser }) {
            mutate(::done)
            return
        }
        val shown = state.value.entries
        val today = memoryDate(clock(), zone())
        val ops = parseMemoryOps(request(MEMORY_EXTRACT_PROMPT, listOf(memoryExtractionInput(shown, turns, today, zone()))))
        val userTurns = turns.filter { it.fromUser }
        fun quotes(op: MemoryOp): List<MemoryQuote> {
            val cited = op.from.mapNotNull { userTurns.getOrNull(it - 1) }.ifEmpty { userTurns.takeIf { it.size == 1 }.orEmpty() }
            return cited.distinct().map { MemoryQuote(it.text.take(MEMORY_QUOTE_TEXT_LIMIT), it.at, sessionId, it.messageId, snapshot.title) }
        }
        val now = clock()
        mutate { current ->
            if (ops.isNullOrEmpty()) return@mutate done(current)
            val update = applyMemoryOps(current.entries, ops, now, idGenerator, ::quotes, shown = shown.map { it.id })
            if (update.changes.isEmpty()) return@mutate done(current)
            val record = MemoryRecord(
                idGenerator(), now, MemoryOrigin.Conversation, update.changes,
                ops.flatMap(::quotes).distinct(), sessionId, snapshot.title,
            )
            done(current).copy(entries = update.entries, records = (current.records + record).takeLast(RECORD_LIMIT))
        }
    }

    // ---------- 控制篇幅 ----------

    private fun MemoryState.withRecord(origin: MemoryOrigin, changes: List<MemoryChange>, now: Long) =
        copy(records = (records + MemoryRecord(idGenerator(), now, origin, changes)).takeLast(RECORD_LIMIT))

    /** 删除到期已久的短期安排，改动记入原始记录。 */
    private suspend fun expire() {
        val today = memoryDate(clock(), zone())
        val now = clock()
        mutate { current ->
            val expired = expiredMemories(current.entries, today)
            if (expired.isEmpty()) return@mutate current
            current.copy(entries = current.entries - expired.toSet())
                .withRecord(MemoryOrigin.Tidy, expired.map { MemoryChange(MemoryChangeType.Delete, it.id, before = it.text) }, now)
        }
    }

    /**
     * 整理完一段对话后控制篇幅：先清理过期的安排；总字数超过 [MEMORY_TIDY_AT] 时请模型合并一次。
     * 合并没能压到阈值以下时，要再增长 [MEMORY_TIDY_STEP] 才重试。请求失败时下次整理再试。
     */
    private suspend fun tidy() {
        expire()
        val shown = state.value.entries
        val size = memorySize(shown)
        if (size <= MEMORY_TIDY_AT) {
            if (state.value.tidiedAt != 0) mutate { it.copy(tidiedAt = 0) }
            return
        }
        if (size < state.value.tidiedAt + MEMORY_TIDY_STEP) return
        val ops = parseMemoryOps(request(MEMORY_TIDY_PROMPT, listOf(memoryTidyInput(shown, memoryDate(clock(), zone()), zone()))))
        val now = clock()
        mutate { current ->
            val update = applyMemoryTidy(current.entries, ops.orEmpty(), shown)
            val next = current.copy(entries = update.entries, tidiedAt = memorySize(update.entries))
            if (update.changes.isEmpty()) next else next.withRecord(MemoryOrigin.Tidy, update.changes, now)
        }
    }

    private suspend fun request(system: String, turns: List<String>): String {
        val messages = turns.mapIndexed { index, text ->
            if (index % 2 == 0) {
                MessageParam.User(listOf(UserContentParam.Text(text)))
            } else {
                MessageParam.Assistant(listOf(buildJsonObject { put("type", "text"); put("text", text) }))
            }
        }
        var reply = Message.Assistant("memory", clock(), status = ReplyStatus.Pending)
        val indexes = HashMap<Int, Int>()
        var finished = false
        api.streamMessage(
            settings.apiKey().orEmpty(),
            CreateMessageRequest(ChatModel.Flash, system, messages, thinking = false, tools = emptyList(), maxTokens = MEMORY_MAX_OUTPUT_TOKENS),
        ).collect { event ->
            reply = applyEvent(reply, event, clock(), indexes)
            if (event is MessageStreamEvent.MessageStop) finished = true
        }
        if (!finished || reply.stopReason != StopReason.EndTurn) throw ApiException(ReplyErrorKind.Interrupted, "记忆请求未完整生成")
        return replyText(reply)
    }

    // ---------- 记忆页 ----------

    private fun MemoryState.withManual(entries: List<MemoryEntry>, change: MemoryChange, now: Long) = copy(
        entries = entries,
        records = (records + MemoryRecord(idGenerator(), now, MemoryOrigin.Manual, listOf(change))).takeLast(RECORD_LIMIT),
    )

    override suspend fun add(text: String) {
        val content = text.trim().take(MEMORY_TEXT_LIMIT)
        if (content.isEmpty()) return
        val now = clock()
        mutate { current ->
            if (current.entries.any { it.text == content }) return@mutate current
            if (memorySize(current.entries) + content.length > MEMORY_BUDGET) throw MemoryFullException()
            val entry = MemoryEntry(idGenerator(), content, now, now)
            current.withManual(current.entries + entry, MemoryChange(MemoryChangeType.Add, entry.id, after = content), now)
        }
    }

    override suspend fun edit(entryId: String, text: String) {
        val content = text.trim().take(MEMORY_TEXT_LIMIT)
        if (content.isEmpty()) return
        val now = clock()
        mutate { current ->
            val old = current.entries.find { it.id == entryId }?.takeIf { it.text != content } ?: return@mutate current
            if (content.length > old.text.length && memorySize(current.entries) - old.text.length + content.length > MEMORY_BUDGET) throw MemoryFullException()
            current.withManual(
                current.entries.map { if (it.id == entryId) it.copy(text = content, updatedAt = now) else it },
                MemoryChange(MemoryChangeType.Update, entryId, before = old.text, after = content), now,
            )
        }
    }

    override suspend fun remove(entryId: String) {
        val now = clock()
        mutate { current ->
            val old = current.entries.find { it.id == entryId } ?: return@mutate current
            current.withManual(current.entries - old, MemoryChange(MemoryChangeType.Delete, entryId, before = old.text), now)
        }
    }

    /** 清空后保留整理进度，旧对话不会被重新整理成记忆。 */
    override suspend fun clear() {
        mutate { MemoryState(cursors = it.cursors) }
    }

    override suspend fun ask(question: String, previous: List<MemoryExchange>): MemoryExchange {
        val content = question.trim()
        loading.await()
        val shown = state.value.entries
        val (answer, ops) = parseMemoryAnswer(
            request(memoryAskSystem(shown, memoryDate(clock(), zone()), zone()), memoryAskTurns(content, previous)),
        )
        if (ops.isEmpty()) return MemoryExchange(content, answer)
        val now = clock()
        val quote = MemoryQuote(content.take(MEMORY_QUOTE_TEXT_LIMIT), now)
        var changes = emptyList<MemoryChange>()
        mutate { current ->
            val update = applyMemoryOps(current.entries, ops, now, idGenerator, { listOf(quote) }, guardDeletes = false, shown = shown.map { it.id })
            changes = update.changes
            if (changes.isEmpty()) return@mutate current
            current.copy(
                entries = update.entries,
                records = (current.records + MemoryRecord(idGenerator(), now, MemoryOrigin.Ask, changes, listOf(quote))).takeLast(RECORD_LIMIT),
            )
        }
        return MemoryExchange(content, answer, changes)
    }
}
