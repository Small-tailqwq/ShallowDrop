package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.Session

/*
 * 消息树与当前路径，对照 src/services/chat/branches.ts。
 *
 * - 当前路径从 rootMessageId 出发，沿每条消息的 selectedChildId 前进；selectedChildId 为空即路径结束
 *   （导入时 current_message_id 之后的消息不展开，其余节点默认选中 id 最大的子消息，由导入方 W2 写入）。
 * - Android 的 rootMessageId 为 null 只表示空会话（Web 的 undefined「线性列表」在本地不存在）。
 * - 兄弟版本按 messages 的存储顺序（即创建顺序）排列，不比较 id：导入消息的数字 id 与本地新消息的 UUID
 *   不可比，新版本总是追加在列表末尾。
 */

/** 展示内容与请求上下文共用当前选中的分支路径；遇到缺失或成环的节点即停止。 */
fun visibleMessages(session: Session): List<Message> {
    val byId = HashMap<String, Message>(session.messages.size * 2)
    for (message in session.messages) byId[message.id] = message
    val path = ArrayList<Message>()
    var id = session.rootMessageId
    while (id != null) {
        val message = byId.remove(id) ?: break
        path.add(message)
        id = message.selectedChildId
    }
    return path
}

/** 与 messageId 同父的全部版本（含自身），按存储顺序。 */
fun siblingsOf(session: Session, messageId: String): List<Message> {
    val message = session.messages.find { it.id == messageId } ?: return emptyList()
    return session.messages.filter { it.parentId == message.parentId }
}

/** 一次遍历收集每条消息的兄弟序号与数量。 */
internal fun siblingPositions(session: Session): Map<String, Pair<Int, Int>> {
    val groups = LinkedHashMap<String?, MutableList<String>>()
    for (message in session.messages) {
        groups.getOrPut(message.parentId) { ArrayList() }.add(message.id)
    }
    val result = HashMap<String, Pair<Int, Int>>(session.messages.size * 2)
    for (ids in groups.values) {
        ids.forEachIndexed { index, id -> result[id] = index to ids.size }
    }
    return result
}

/** 新消息挂在指定父节点下，并选择新分支；原节点与其后续对话保持不变。 */
fun appendBranch(session: Session, parentId: String?, messages: List<Message>): Session {
    val first = messages.firstOrNull() ?: return session
    val chained = messages.mapIndexed { index, message ->
        message.withBranch(
            parentId = if (index == 0) parentId else messages[index - 1].id,
            selectedChildId = messages.getOrNull(index + 1)?.id,
        )
    }
    return session.copy(
        rootMessageId = if (parentId == null) first.id else session.rootMessageId,
        messages = session.messages.map {
            if (it.id == parentId) it.withBranch(it.parentId, first.id) else it
        } + chained,
    )
}

/** 仅允许切换当前路径上某一节点的兄弟版本，恢复该版本已选中的后续分支。 */
fun selectVersion(session: Session, messageId: String): Session {
    val message = session.messages.find { it.id == messageId } ?: return session
    if (visibleMessages(session).none { it.parentId == message.parentId }) {
        return session
    }
    return session.copy(
        rootMessageId = if (message.parentId == null) message.id else session.rootMessageId,
        messages = session.messages.map {
            if (it.id == message.parentId) it.withBranch(it.parentId, message.id) else it
        },
    )
}

internal fun Message.withBranch(parentId: String?, selectedChildId: String?): Message = when (this) {
    is Message.User -> copy(parentId = parentId, selectedChildId = selectedChildId)
    is Message.Assistant -> copy(parentId = parentId, selectedChildId = selectedChildId)
}
