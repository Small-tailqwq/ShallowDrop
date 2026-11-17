package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageBranch
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.conversationPath
import io.github.smalltailqwq.shallowdrop.core.model.toConversationSnapshot
import org.junit.Assert.*
import org.junit.Test

class ConversationSnapshotTest {
    private val session = Session("s", "title", 0, 0, listOf(
        Message.User("u", 0, selectedChildId = "new", content = "question"),
        Message.Assistant("old", 0, "u", "follow", status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text("old answer"))),
        Message.User("follow", 0, "old", content = "follow-up"),
        Message.Assistant("new", 0, "u", status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text("new answer"))),
    ), "u")

    @Test
    fun loadedSnapshotContainsOnlySelectedBodiesAndAllBranchChoices() {
        val snapshot = session.toConversationSnapshot()!!
        assertEquals(listOf("u", "new"), snapshot.messages.map { it.id })
        assertEquals(session.messages.map { it.id }, snapshot.branches.map { it.id })
        assertEquals(1 to 2, siblingPositions(snapshot)["new"])
    }

    @Test
    fun selectingHiddenVersionRestoresItsFollowingPathWithoutChangingOriginal() {
        val selected = session.toConversationSnapshot("old")!!
        assertEquals(listOf("u", "old", "follow"), selected.messages.map { it.id })
        assertEquals("old", selected.messages.first().selectedChildId)
        assertEquals("old", selected.branches.first().selectedChildId)
        assertEquals("new", session.messages.first().selectedChildId)
        assertEquals("follow", selected.messages[1].selectedChildId)
    }

    @Test
    fun appendingRetryRetainsOldBranchStructureWithoutRetainingOldBody() {
        val initial = session.toConversationSnapshot()!!
        val retry = Message.Assistant("retry", 1, status = ReplyStatus.Pending)
        val appended = appendBranch(initial, "u", listOf(retry))
        assertEquals(listOf("u", "retry"), appended.messages.map { it.id })
        assertEquals(listOf("u", "old", "follow", "new", "retry"), appended.branches.map { it.id })
        assertEquals(2 to 3, siblingPositions(appended)["retry"])
        assertEquals("follow", appended.branches.first { it.id == "old" }.selectedChildId)
        assertEquals("new answer", replyText(initial.messages.last() as Message.Assistant))
    }

    @Test
    fun missingTargetsAndCyclicAncestryCannotSelectAPath() {
        assertNull(session.toConversationSnapshot("missing"))
        assertNull(conversationPath("a", listOf(MessageBranch("a", "b", "b"), MessageBranch("b", "a", "a")), "a"))
    }

    @Test
    fun childCyclesAndMissingChildrenEndTheVisiblePath() {
        assertEquals(listOf("a", "b"), conversationPath("a", listOf(MessageBranch("a", null, "b"), MessageBranch("b", "a", "a")))!!.messageIds)
        assertEquals(listOf("a"), conversationPath("a", listOf(MessageBranch("a", null, "missing")))!!.messageIds)
    }
}
