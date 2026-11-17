package io.github.smalltailqwq.shallowdrop.core.model

import kotlinx.serialization.Serializable

/*
 * 跨对话记忆。分两层：
 * - 摘要：MemoryEntry，一条一件事，注入之后每个新对话；
 * - 原始：MemoryRecord，每次变动的来源（哪个对话、用户的哪句原话）和改了什么，只用于查看与提问。
 * 方案与实测见 docs/android/cross-window-memory-2026-11-17.md。
 */

/** 作为依据的用户原话。保存文字快照，原对话被删除后仍可查看；sessionId 为 null 表示不来自对话。 */
@Serializable
data class MemoryQuote(
    val text: String,
    val at: Long,
    val sessionId: String? = null,
    val messageId: String? = null,
    val sessionTitle: String = "",
)

@Serializable
data class MemoryEntry(
    val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 最近几次写入或修改这条记忆时依据的原话，旧的在前 */
    val quotes: List<MemoryQuote> = emptyList(),
    /** 短期安排（行程、预约、截止日期）结束的日期，ISO 格式；过期一段时间后自动清理。长期事实为 null */
    val until: String? = null,
)

@Serializable
enum class MemoryChangeType { Add, Update, Delete }

@Serializable
data class MemoryChange(
    val type: MemoryChangeType,
    val entryId: String,
    val before: String? = null,
    val after: String? = null,
)

@Serializable
enum class MemoryOrigin {
    /** 对话结束后自动整理 */
    Conversation,

    /** 用户在记忆页直接增删改 */
    Manual,

    /** 用户在记忆页提问时要求的修改 */
    Ask,

    /** 自动整理：清理过期的安排，篇幅接近上限时合并 */
    Tidy,
}

/** 一次记忆变动：来源、依据的原话和具体改动。 */
@Serializable
data class MemoryRecord(
    val id: String,
    val at: Long,
    val origin: MemoryOrigin,
    val changes: List<MemoryChange>,
    val quotes: List<MemoryQuote> = emptyList(),
    val sessionId: String? = null,
    val sessionTitle: String = "",
)

@Serializable
data class MemoryState(
    val entries: List<MemoryEntry> = emptyList(),
    /** 新的在后 */
    val records: List<MemoryRecord> = emptyList(),
    /** 各会话已整理到的消息 id */
    val cursors: Map<String, String> = emptyMap(),
    /** 有新对话内容、尚未整理的会话 */
    val pending: Set<String> = emptySet(),
    /** 上次合并之后的记忆总字数；合并没能压到阈值以下时，增长一段后才再试 */
    val tidiedAt: Int = 0,
)

/** 记忆总字数已到上限，手动新增或加长被拒绝。 */
class MemoryFullException : Exception("记忆已满")

/** 记忆页的一轮提问。 */
data class MemoryExchange(
    val question: String,
    val answer: String,
    val changes: List<MemoryChange> = emptyList(),
)
