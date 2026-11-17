package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BranchesTest {
    private fun user(id: String, parent: String?, selected: String? = null) =
        Message.User(id = id, createdAt = 0, parentId = parent, selectedChildId = selected, content = id)

    private fun reply(id: String, parent: String?, selected: String? = null) =
        Message.Assistant(id = id, createdAt = 0, parentId = parent, selectedChildId = selected, status = ReplyStatus.Done)

    /** 导入树：提问 10 有两个回答 11、13（选中 13），13 → 14 → 15，但 14 未选中子节点 */
    private val imported = Session(
        id = "s",
        title = "",
        createdAt = 0,
        updatedAt = 0,
        rootMessageId = "10",
        messages = listOf(
            user("10", null, "13"),
            reply("11", "10", null),
            reply("13", "10", "14"),
            user("14", "13", null),
            reply("15", "14", null),
        ),
    )

    @Test
    fun pathFollowsSelectedChildAndStopsWhenUnselected() {
        // 14 没有选中子节点：导入时 current_message_id 之后的消息不展开
        assertEquals(listOf("10", "13", "14"), visibleMessages(imported).map { it.id })
    }

    @Test
    fun emptySessionHasNoPath() {
        assertEquals(emptyList<Message>(), visibleMessages(imported.copy(rootMessageId = null)))
    }

    @Test
    fun siblingsUseStorageOrderNotIdOrder() {
        // 本地新版本的 UUID 追加在导入的数字 id 之后，不按字符串比较
        val withLocal = appendBranch(imported, "10", listOf(reply("0a-uuid", null)))
        val positions = siblingPositions(withLocal)
        assertEquals(0 to 3, positions["11"])
        assertEquals(1 to 3, positions["13"])
        assertEquals(2 to 3, positions["0a-uuid"])
        assertEquals(listOf("11", "13", "0a-uuid"), siblingsOf(withLocal, "13").map { it.id })
    }

    @Test
    fun appendBranchSelectsNewBranchAndKeepsOldOnes() {
        val session = appendBranch(imported, "13", listOf(user("u2", null), reply("r2", null)))
        assertEquals(listOf("10", "13", "u2", "r2"), visibleMessages(session).map { it.id })
        val u2 = session.messages.first { it.id == "u2" }
        assertEquals("13", u2.parentId)
        assertEquals("r2", u2.selectedChildId)
        // 原分支 14 仍在，且自身的后续选择不变
        assertEquals(imported.messages.first { it.id == "14" }, session.messages.first { it.id == "14" })
    }

    @Test
    fun appendBranchAtRootChangesRoot() {
        val session = appendBranch(imported, null, listOf(user("u", null), reply("r", null)))
        assertEquals("u", session.rootMessageId)
        assertEquals(listOf("u", "r"), visibleMessages(session).map { it.id })
        assertEquals(0 to 2, siblingPositions(session)["10"])
    }

    @Test
    fun selectVersionRestoresBranchSelection() {
        val withEdit = appendBranch(imported, "13", listOf(user("u2", null), reply("r2", null)))
        val back = selectVersion(withEdit, "14")
        assertEquals(listOf("10", "13", "14"), visibleMessages(back).map { it.id })
        val other = selectVersion(back, "11")
        assertEquals(listOf("10", "11"), visibleMessages(other).map { it.id })
        // 切回 13 时恢复 13 原本选中的 14
        assertEquals(listOf("10", "13", "14"), visibleMessages(selectVersion(other, "13")).map { it.id })
    }

    @Test
    fun selectVersionIgnoresNodesOffPath() {
        // 15 的父节点 14 在路径上，但 15 的兄弟组（父为 14）不在当前路径的父集合里
        val session = selectVersion(imported, "11").let { selectVersion(it, "15") }
        assertEquals(listOf("10", "11"), visibleMessages(session).map { it.id })
        val unchanged = selectVersion(imported, "missing")
        assertSame(imported, unchanged)
    }

    @Test
    fun searchSelectsAncestorPathAndPreservesFollowingMessages() {
        val otherRoot = appendBranch(imported, null, listOf(user("u", null), reply("r", null)))
        val selected = selectMessagePath(otherRoot, "15")
        assertEquals(listOf("10", "13", "14", "15"), visibleMessages(selected).map { it.id })
        assertEquals(otherRoot.messages.size, selected.messages.size)
        assertEquals(selected, selectMessagePath(selected, "14"))
        assertSame(imported, selectMessagePath(imported, "missing"))
    }
}
