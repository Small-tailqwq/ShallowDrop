package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ConversationSnapshot
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.Session
import java.math.BigInteger

private val forkSuffix = Regex("(?:^|\\s)#[1-9][0-9]*(?:-[1-9][0-9]*)*$")

/** 编号取自当前标题，重命名后按新标题继续编号。 */
internal fun nextForkTitle(title: String, existingTitles: List<String>): String {
    val base = title.trimEnd()
    val prefix = if (forkSuffix.containsMatchIn(base)) "$base-" else "$base #".trimStart()
    val largest = existingTitles.asSequence()
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix) }
        .filter { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        .map { it.toBigInteger() }
        .maxOrNull() ?: BigInteger.ZERO
    return "$prefix${largest + BigInteger.ONE}"
}

/** 复制当前选中路径至已完成的回答，附件 ID 保持不变以共用原件。 */
internal fun forkSession(source: Session, messageId: String, id: String, title: String, now: Long): Session? {
    return forkPath(visibleMessages(source), messageId, id, title, now)
}

internal fun forkSession(source: ConversationSnapshot, messageId: String, id: String, title: String, now: Long): Session? =
    forkPath(source.messages, messageId, id, title, now)

private fun forkPath(path: List<Message>, messageId: String, id: String, title: String, now: Long): Session? {
    val end = path.indexOfFirst { it.id == messageId }
    val reply = path.getOrNull(end) as? Message.Assistant ?: return null
    if (reply.status != ReplyStatus.Done) return null
    val messages = path.take(end + 1).mapIndexed { index, message ->
        message.withBranch(
            parentId = path.getOrNull(index - 1)?.id,
            selectedChildId = if (index < end) path[index + 1].id else null,
        )
    }
    return Session(id, title, now, now, messages, messages.first().id)
}
