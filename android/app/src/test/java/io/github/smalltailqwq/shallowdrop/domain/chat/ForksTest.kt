package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ContextSummary
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForksTest {
    @Test fun keepsCompletedPrefixCheckpointUsable() {
        val original = compactionHistory()
        val checkpoint = ContextSummary(contextFingerprint(original.take(2)), ChatModel.Flash, "保存的摘要")
        val messages = original.map { if (it.id == "a0") (it as Message.Assistant).copy(contextSummary = checkpoint) else it }
        val source = Session("s", "原会话", 1, 3, messages, "u0")
        val fork = requireNotNull(forkSession(source, "a1", "fork", "原会话 #1", 4))
        val prepared = compactionContext(visibleMessages(fork), ChatModel.Flash)
        assertEquals(checkpoint, prepared.summary)
        assertEquals(listOf("u1", "a1"), prepared.history.map { it.id })
        assertEquals(messages, source.messages)
    }

    @Test fun numbersSiblingsAndNestedForksFromCurrentTitle() {
        val titles = listOf("对话 #1", "对话 #2", "对话 #1-1", "对话 #1-3", "对话 #1-3-1")
        assertEquals("对话 #3", nextForkTitle("对话", titles))
        assertEquals("对话 #1-4", nextForkTitle("对话 #1", titles))
        assertEquals("对话 #2-1", nextForkTitle("对话 #2", titles))
        assertEquals("#1", nextForkTitle("", titles))
        assertEquals("新名字 #1", nextForkTitle("新名字", titles))
        assertEquals("C#1 #1", nextForkTitle("C#1", titles))
        assertEquals("话题 #9223372036854775809", nextForkTitle("话题", listOf("话题 #9223372036854775808")))
    }

    @Test fun copiesOnlySelectedAncestorsAndLeavesSourceAndAttachmentUnchanged() {
        val file = FileAttachment("image", name = "image.png", size = 10, isImage = true)
        val question = Message.User("u", 1, selectedChildId = "a2", content = "问题", files = listOf(file))
        val older = Message.Assistant("a1", 2, parentId = "u", status = ReplyStatus.Done)
        val chosen = Message.Assistant("a2", 3, parentId = "u", selectedChildId = "next", status = ReplyStatus.Done,
            blocks = listOf(ReplyBlock.Text("保留的回复")))
        val next = Message.User("next", 4, parentId = "a2", content = "之后的问题")
        val source = Session("source", "对话", 1, 4, listOf(question, older, chosen, next), "u")
        val fork = requireNotNull(forkSession(source, "a2", "fork", "对话 #1", 5))

        assertEquals(listOf("u", "a2"), visibleMessages(fork).map { it.id })
        assertEquals(2, fork.messages.size)
        assertNull(fork.messages.last().selectedChildId)
        assertEquals(listOf(file), (fork.messages.first() as Message.User).files)
        assertEquals(listOf("u", "a2", "next"), visibleMessages(source).map { it.id })
        assertEquals(5L, fork.createdAt)
        assertNull(forkSession(source, "a1", "fork", "对话 #1", 5))
        assertNull(forkSession(source, "u", "fork", "对话 #1", 5))
    }

    @Test fun unfinishedAndFailedRepliesCannotBeForked() {
        for (status in listOf(ReplyStatus.Pending, ReplyStatus.Streaming, ReplyStatus.Stopped, ReplyStatus.Error)) {
            val reply = Message.Assistant("a", 1, status = status)
            assertNull(forkSession(Session("s", "", 0, 1, listOf(reply), "a"), "a", "fork", "#1", 2))
        }
    }
}
