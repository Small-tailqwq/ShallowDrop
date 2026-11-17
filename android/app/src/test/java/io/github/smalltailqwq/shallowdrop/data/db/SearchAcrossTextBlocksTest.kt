package io.github.smalltailqwq.shallowdrop.data.db

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.buildReplyView
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchAcrossTextBlocksTest {
    private val env = TestEnv()
    private val store = RoomSessionStore(env.db, env.paths)

    @After fun tearDown() = env.close()

    private suspend fun saveReply(): Message.Assistant {
        val reply = Message.Assistant("a", 2, parentId = "u", status = ReplyStatus.Done,
            blocks = listOf(ReplyBlock.Text("Shallow"), ReplyBlock.Text("Drop")))
        store.save(Session("s", "sample", 1, 2, rootMessageId = "u", messages = listOf(
            Message.User("u", 1, selectedChildId = "a", content = "question"), reply)))
        return reply
    }

    @Test fun findsVisibleWordSpanningTextBlocks() = runTest {
        val visible = buildReplyView(saveReply(), streaming = false).answer!!.text
        val results = store.searchMessages(visible)
        println("visible=$visible query=$visible matches=${results.map { it.messageId }}")
        assertEquals("ShallowDrop", visible)
        assertEquals(listOf("a"), results.map { it.messageId })
        assertEquals(visible, results.single().text)
    }

    @Test fun doesNotMatchInsertedLineBreakAbsentFromReply() = runTest {
        val visible = buildReplyView(saveReply(), streaming = false).answer!!.text
        val results = store.searchMessages("Shallow\nDrop")
        println("visible=$visible query=Shallow\\nDrop matches=${results.map { it.messageId }}")
        assertTrue(results.isEmpty())
    }
}
