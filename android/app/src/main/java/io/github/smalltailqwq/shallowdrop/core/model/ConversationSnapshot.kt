package io.github.smalltailqwq.shallowdrop.core.model

/** 不含正文的消息树节点，顺序与数据库中的消息创建顺序一致。 */
data class MessageBranch(
    val id: String,
    val parentId: String?,
    val selectedChildId: String?,
) {
    fun applyTo(message: Message): Message {
        if (message.parentId == parentId && message.selectedChildId == selectedChildId) return message
        return when (message) {
            is Message.User -> message.copy(parentId = parentId, selectedChildId = selectedChildId)
            is Message.Assistant -> message.copy(parentId = parentId, selectedChildId = selectedChildId)
        }
    }
}

/** 聊天内存快照：保留整棵树的结构，正文只加载当前路径。完整历史由 [Session] 表示。 */
data class ConversationSnapshot(
    val summary: SessionSummary,
    val rootMessageId: String? = null,
    val messages: List<Message> = emptyList(),
    val branches: List<MessageBranch> = emptyList(),
) {
    val id get() = summary.id
    val title get() = summary.title
    val updatedAt get() = summary.updatedAt
}

data class ConversationPath(
    val rootMessageId: String?,
    val branches: List<MessageBranch>,
    val messageIds: List<String>,
)

/** 选择目标的祖先，再沿已有子节点选择前进；不存在或祖先成环的目标不改变会话。 */
fun conversationPath(
    rootMessageId: String?,
    branches: List<MessageBranch>,
    selectedMessageId: String? = null,
): ConversationPath? {
    val byId = branches.associateBy { it.id }.toMutableMap()
    var root = rootMessageId
    var selected = branches
    if (selectedMessageId != null) {
        val ancestors = HashSet<String>()
        var id: String? = selectedMessageId
        var child: String? = null
        while (id != null) {
            if (!ancestors.add(id)) return null
            val branch = byId[id] ?: return null
            if (child != null) byId[id] = branch.copy(selectedChildId = child)
            child = id
            id = branch.parentId
        }
        root = child
        selected = branches.map { byId.getValue(it.id) }
    }
    val ids = ArrayList<String>()
    var id = root
    while (id != null) {
        val branch = byId.remove(id) ?: break
        ids.add(id)
        id = branch.selectedChildId
    }
    return ConversationPath(root, selected, ids)
}

fun Message.toBranch() = MessageBranch(id, parentId, selectedChildId)

fun Session.toConversationSnapshot(selectedMessageId: String? = null): ConversationSnapshot? {
    val path = conversationPath(rootMessageId, messages.map { it.toBranch() }, selectedMessageId) ?: return null
    val byId = messages.associateBy { it.id }
    val branches = path.branches.associateBy { it.id }
    return ConversationSnapshot(
        toSummary(), path.rootMessageId,
        path.messageIds.map { branches.getValue(it).applyTo(byId.getValue(it)) },
        path.branches,
    )
}
