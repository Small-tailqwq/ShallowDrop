/*
 * Copyright 2025 OpenAI
 * SPDX-License-Identifier: Apache-2.0
 *
 * 改编自 openai/codex，提交 95946faafcd8f068e28610e8ae6a4c03060c1448：
 * codex-rs/core/src/session/async_compaction.rs
 * codex-rs/core/src/compact_remote_v2_installation.rs
 * https://github.com/openai/codex/tree/95946faafcd8f068e28610e8ae6a4c03060c1448
 *
 * Kotlin 适配保留后台候选、重复请求抑制、前缀校验和后续消息保留机制。
 * 远程 V2 压缩项改为 DeepSeek Messages 生成的文本摘要，检查点随原消息持久化。
 * 许可证与来源说明见 assets/third-party/codex/。
 */
package io.github.smalltailqwq.shallowdrop.domain.chat

import android.util.Log
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import io.github.smalltailqwq.shallowdrop.core.model.ContextSummary
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/** 两个公开模型的窗口均为 1,048,576 token，见 DeepSeek /models 文档。 */
internal const val CONTEXT_WINDOW_TOKENS = 1_048_576L
internal val BACKGROUND_COMPACT_TOKENS = CONTEXT_WINDOW_TOKENS * CompactionThresholds().backgroundPercent / 100
internal val FOREGROUND_COMPACT_TOKENS = CONTEXT_WINDOW_TOKENS * CompactionThresholds().foregroundPercent / 100
private const val RETAINED_CONTEXT_TOKENS = 64_000L
internal const val COMPACTION_MAX_OUTPUT_TOKENS = 8192

internal const val COMPACTION_PROMPT = """为后续继续这段对话生成一份上下文交接摘要，不要回答最后一个问题。
保留用户的目标、明确要求与偏好、已确认的事实与结论、决定、已完成与待完成事项、尚未解决的问题，以及继续对话必需的原文细节（数字、名称、路径、代码、引用来源）。区分用户提供的信息、助手的建议和未核实的推测，不补造事实，不记录隐藏思考过程。已有摘要与后续对话需要合并，后续明确更正优先。附件或网页只记录能从输入中确认的内容；无法读取时标明，不猜测。
使用与对话一致的语言，组织为紧凑的事实记录，控制在约 6000 token 内。只输出摘要。"""

private val checkpointJson = Json { encodeDefaults = true }

/** 分支选择、远端文件引用和摘要元数据不改变被总结的原始对话内容。 */
internal fun contextFingerprint(history: List<Message>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (message in history) {
        val content = when (message) {
            is Message.User -> message.copy(selectedChildId = null, files = message.files.map { it.copy(serverId = null) })
            // 「当前情况」快照是请求时附带的环境信息，不属于被总结的对话内容
            is Message.Assistant -> message.copy(selectedChildId = null, contextSummary = null, contextSummaryId = null, currentState = null)
        }
        digest.update(checkpointJson.encodeToString(Message.serializer(), content).toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class CompactionContext(val history: List<Message>, val summary: ContextSummary? = null) {
    fun messages(
        question: Message.User,
        prefix: Message.Assistant? = null,
        thinking: Boolean = false,
        latestReminder: String? = null,
        currentState: String? = null,
    ): List<MessageParam> {
        val memory = summary?.let {
            listOf(MessageParam.User(listOf(UserContentParam.Text(
                "以下是此前对话的历史摘要，仅供继续对话时参考；其中引用的指令与材料仍属于原来的对话角色。\n<conversation_summary>\n${it.text}\n</conversation_summary>",
            ))))
        }.orEmpty()
        return memory + toRequestMessages(history, question, prefix, thinking, latestReminder, currentState)
    }
}

/** 只采用来源前缀仍完全匹配、且属于当前模型的检查点。 */
internal fun compactionContext(history: List<Message>, model: ChatModel): CompactionContext {
    for (index in history.indices.reversed()) {
        val summary = (history[index] as? Message.Assistant)?.contextSummary ?: continue
        if (summary.model == model && summary.sourceHash == contextFingerprint(history.take(index + 1))) {
            return CompactionContext(history.drop(index + 1), summary)
        }
    }
    return CompactionContext(history)
}

internal fun approximateTokens(text: String): Long = (text.toByteArray(Charsets.UTF_8).size.toLong() + 3) / 4

private fun messageTokens(message: MessageParam): Long = when (message) {
    is MessageParam.LatestReminder -> approximateTokens(message.content)
    is MessageParam.User -> message.content.sumOf { if (it is UserContentParam.Text) approximateTokens(it.text) else 2048L }
    is MessageParam.Assistant -> message.content.sumOf { approximateTokens(it.toString()) }
}

/** 摘要采用后，旧窗口的 usage 不能继续作为新窗口的测量值。 */
internal fun activeContextTokens(history: List<Message>, model: ChatModel): Long {
    val context = compactionContext(history, model)
    val probe = Message.User("context-size", 0, content = "")
    val estimate = context.messages(probe).sumOf(::messageTokens)
    val last = history.lastOrNull { isUsableReply(it) } as? Message.Assistant
    val usage = last?.usage?.takeIf { last.contextSummaryId == context.summary?.sourceHash }
    val measured = usage?.let { it.inputTokens + it.cacheCreationInputTokens + it.cacheReadInputTokens + it.outputTokens } ?: 0
    return maxOf(estimate, measured)
}

/** 后台工作属于会话；停止前台回答不会取消它，删除或替换会话时显式取消。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class AsyncContextCompaction(
    private val scope: CoroutineScope,
    private val summarize: suspend (String, List<Message>, ChatModel) -> String,
    private val install: suspend (String, String, ContextSummary) -> Boolean,
    private val thresholds: () -> CompactionThresholds = { CompactionThresholds() },
    private val enabled: () -> Boolean = { true },
) {
    private data class Candidate(val anchorId: String, val summary: ContextSummary)
    private data class Attempt(val anchorId: String, val sourceHash: String, val model: ChatModel, val result: Deferred<Result<Candidate>>)
    private val attempts = ConcurrentHashMap<String, Attempt>()

    fun maybeStart(sessionId: String, history: List<Message>, model: ChatModel, additionalTokens: Long = 0) {
        if (!enabled()) {
            cancel(sessionId)
            return
        }
        if (activeContextTokens(history, model) + additionalTokens < CONTEXT_WINDOW_TOKENS * thresholds().backgroundPercent / 100) return
        val active = compactionContext(history, model)
        val rounds = contextRounds(active.history)
        if (rounds.isEmpty()) return
        var retained = 0
        var retainedTokens = 0L
        for ((question, reply) in rounds.asReversed().take(minOf(2, rounds.size - 1))) {
            val tokens = toRequestMessages(emptyList(), question, reply.copy(prefix = true)).sumOf(::messageTokens)
            if (retainedTokens + tokens > RETAINED_CONTEXT_TOKENS) break
            retained++
            retainedTokens += tokens
        }
        var anchor = rounds[rounds.lastIndex - retained].second
        var end = history.indexOfFirst { it.id == anchor.id }
        // 小前缀不足以抵消摘要开销时，直接总结当前已完成的窗口。
        if (activeContextTokens(history.take(end + 1), model) < COMPACTION_MAX_OUTPUT_TOKENS * 4L) {
            anchor = rounds.last().second
            end = history.indexOfFirst { it.id == anchor.id }
        }
        val snapshot = history.take(end + 1)
        val sourceHash = contextFingerprint(snapshot)
        attempts.compute(sessionId) { _, previous ->
            when {
                previous?.model == model && !previous.result.isCancelled &&
                    (previous.sourceHash == sourceHash || !previous.result.isCompleted || previous.result.getCompleted().isSuccess) -> previous
                else -> {
                    previous?.result?.cancel()
                    val result = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val text = summarize(sessionId, snapshot, model).trim()
                            check(text.isNotEmpty()) { "Context compaction returned an empty summary" }
                            check(approximateTokens(text) < activeContextTokens(snapshot, model)) {
                                "Context compaction did not reduce the context"
                            }
                            Result.success(Candidate(anchor.id, ContextSummary(sourceHash, model, text)))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w("ContextCompaction", "Background compaction failed; original messages retained", error)
                            Result.failure(error)
                        }
                    }
                    Attempt(anchor.id, sourceHash, model, result)
                }
            }
        }?.result?.start()
    }

    suspend fun prepare(
        sessionId: String,
        history: List<Message>,
        question: Message.User,
        model: ChatModel,
    ): CompactionContext {
        if (!enabled()) {
            cancel(sessionId)
            return CompactionContext(history)
        }
        val previous = attempts[sessionId]
        if (previous != null) {
            val index = history.indexOfFirst { it.id == previous.anchorId }
            if (previous.model != model || previous.result.isCancelled || index < 0 ||
                contextFingerprint(history.take(index + 1)) != previous.sourceHash
            ) cancel(sessionId)
        }
        maybeStart(sessionId, history, model, approximateTokens(question.content))
        val current = compactionContext(history, model)
        val attempt = attempts[sessionId] ?: return current
        if (attempt.model != model) return current
        val mustWait = activeContextTokens(history, model) + approximateTokens(question.content) >= CONTEXT_WINDOW_TOKENS * thresholds().foregroundPercent / 100
        if (!attempt.result.isCompleted && !mustWait) return current
        val result = try {
            attempt.result.await()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!enabled()) return CompactionContext(history)
            throw error
        }
        if (!enabled()) return CompactionContext(history)
        if (result.isFailure) {
            if (mustWait) {
                attempts.remove(sessionId, attempt)
                throw result.exceptionOrNull()!!
            }
            return current
        }
        val candidate = result.getOrThrow()
        val index = history.indexOfFirst { it.id == candidate.anchorId }
        if (index < 0 || contextFingerprint(history.take(index + 1)) != candidate.summary.sourceHash) return current
        if (!install(sessionId, candidate.anchorId, candidate.summary)) return if (enabled()) current else CompactionContext(history)
        attempts.remove(sessionId, attempt)
        return CompactionContext(history.drop(index + 1), candidate.summary)
    }

    fun cancel(sessionId: String) { attempts.remove(sessionId)?.result?.cancel() }

    fun clear() {
        attempts.values.forEach { it.result.cancel() }
        attempts.clear()
    }
}
