package io.github.smalltailqwq.shallowdrop.data.db

import android.net.Uri
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ContextSummary
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RoomSessionStoreTest {
    private val env = TestEnv()
    private val store = RoomSessionStore(env.db, env.paths)
    private val attachments = FileAttachmentStore(env.db, env.paths, env.context.contentResolver)

    @After
    fun tearDown() = env.close()

    private val image = FileAttachment(id = "img-1", name = "a.png", size = 3, isImage = true, mimeType = "image/png")

    private fun session(id: String, updatedAt: Long, files: List<FileAttachment> = emptyList()) = Session(
        id = id,
        title = "标题 $id",
        createdAt = 1,
        updatedAt = updatedAt,
        rootMessageId = "u1",
        messages = listOf(
            Message.User(id = "u1", createdAt = 10, selectedChildId = "a1", content = "你好", files = files),
            Message.Assistant(
                id = "a1",
                createdAt = 20,
                parentId = "u1",
                model = ChatModel.V4Pro,
                thinking = true,
                search = true,
                prefix = true,
                status = ReplyStatus.Done,
                blocks = listOf(
                    ReplyBlock.Thinking("想", signature = "sig", startedAt = 1, endedAt = 2),
                    ReplyBlock.ServerToolUse(
                        id = "srvtoolu_search_0_ab",
                        name = "deepseek_web_search",
                        input = ServerToolInput.Search("q", listOf("q")),
                        kind = ServerToolKind.Search,
                    ),
                    ReplyBlock.Text("答", citations = listOf(TextCitation(url = "https://a.example", title = null))),
                ),
            ),
        ),
    )

    @Test
    fun contextSummarySurvivesMessageStorage() = runTest {
        val original = session("s1", 100)
        val reply = (original.messages.last() as Message.Assistant).copy(
            contextSummary = ContextSummary("prefix-sha256", ChatModel.V4Pro, "已确认的上下文摘要"),
            contextSummaryId = "previous-prefix-sha256",
        )
        store.save(original)
        store.upsertMessage("s1", reply, 100, original.rootMessageId)
        assertEquals(reply, store.load("s1")!!.messages.last())
    }

    @Test
    fun searchMessagesFindsTextReplyAndFileNameOnly() = runTest {
        val report = FileAttachment(id = "f1", name = "Quarterly_Report.pdf", size = 1, isImage = false)
        store.save(session("s1", 100, listOf(report)))
        store.save(session("s2", 200))

        // 回复正文（忽略大小写）、提问；思考块与引用不参与
        assertEquals(listOf("s1" to "a1", "s2" to "a1"), store.searchMessages("答").map { it.sessionId to it.messageId }.sortedBy { it.first })
        assertTrue(store.searchMessages("想").isEmpty())
        assertEquals(2, store.searchMessages("你好").size)

        // 文件名：通配符按字面匹配
        val file = store.searchMessages("quarterly_report").single()
        assertEquals(Triple("s1", "u1", true), Triple(file.sessionId, file.messageId, file.isFileName))
        assertEquals("Quarterly_Report.pdf", file.text)
        assertTrue(store.searchMessages("quarterly%report").isEmpty())
        assertTrue(store.searchMessages("  ").isEmpty())
    }

    @Test
    fun asciiQueryFindsNonAsciiCaseFoldedCharacters() = runTest {
        // Kotlin ignoreCase 把 K(开尔文符号)、ſ、ı、İ 分别视同 k、s、i、i；SQLite LIKE 只折叠 ASCII，粗筛不能漏掉它们
        val texts = mapOf("kelvin" to "K", "long-s" to "ſ", "dotless" to "ı", "dotted" to "İ")
        texts.forEach { (id, char) ->
            store.save(
                Session(id, id, 1, 1, rootMessageId = "m", messages = listOf(Message.User("m", 1, content = "x${char}x"))),
            )
        }
        assertEquals(setOf("kelvin"), store.searchMessages("xkx").map { it.sessionId }.toSet())
        assertEquals(setOf("long-s"), store.searchMessages("xsx").map { it.sessionId }.toSet())
        assertEquals(setOf("dotless", "dotted"), store.searchMessages("xix").map { it.sessionId }.toSet())
        assertEquals(setOf("kelvin"), store.searchMessages("XKX").map { it.sessionId }.toSet())
    }

    @Test
    fun saveLoadAndSummaries() = runTest {
        store.save(session("s1", 100))
        store.save(session("s2", 200))
        assertEquals(session("s1", 100), store.load("s1"))
        assertEquals(listOf("s2", "s1"), store.observeSummaries().first().map { it.id })
        assertNull(store.load("missing"))

        store.rename("s1", "新标题")
        assertEquals("新标题", store.load("s1")!!.title)
        assertEquals(100, store.load("s1")!!.updatedAt)
    }

    @Test
    fun upsertMessageKeepsOrderAndTouchesSession() = runTest {
        store.save(session("s1", 100))
        val reply = Message.Assistant(id = "a2", createdAt = 5, parentId = "u1", status = ReplyStatus.Streaming)
        store.upsertMessage("s1", reply, updatedAt = 300, rootMessageId = "u1")
        // 覆盖已有消息不改变顺序
        store.upsertMessage("s1", reply.copy(status = ReplyStatus.Done), updatedAt = 400, rootMessageId = "u1")
        val loaded = store.load("s1")!!
        assertEquals(listOf("u1", "a1", "a2"), loaded.messages.map { it.id })
        assertEquals(ReplyStatus.Done, (loaded.messages[2] as Message.Assistant).status)
        assertEquals(400, loaded.updatedAt)
        // 会话不存在时忽略
        store.upsertMessage("nope", reply, 1, null)
        assertNull(store.load("nope"))
    }

    @Test
    fun deleteRemovesOnlyUnreferencedOriginals() = runTest {
        attachments.put(image, byteArrayOf(1, 2, 3).inputStream())
        store.save(session("s1", 100, listOf(image)))
        store.save(session("s2", 200, listOf(image)))
        store.delete("s1")
        assertNotNull(attachments.file(image.id))
        store.delete("s2")
        assertNull(attachments.file(image.id))
        assertNull(attachments.metadata(image.id))
    }

    @Test
    fun deleteAllClearsEverything() = runTest {
        attachments.put(image, byteArrayOf(1, 2, 3).inputStream())
        store.save(session("s1", 100, listOf(image)))
        store.deleteAll()
        assertTrue(store.observeSummaries().first().isEmpty())
        assertNull(attachments.file(image.id))
    }

    @Test
    fun setServerIdUpdatesMessages() = runTest {
        attachments.put(image, byteArrayOf(1, 2, 3).inputStream())
        store.save(session("s1", 100, listOf(image)))
        attachments.setServerId(image.id, "file-xyz")
        assertEquals("file-xyz", attachments.metadata(image.id)!!.serverId)
        val user = store.load("s1")!!.messages[0] as Message.User
        assertEquals("file-xyz", user.files.single().serverId)
    }

    @Test
    fun importFromUriCopiesContent() = runTest {
        val uri = Uri.parse("content://test.provider/docs/photo.png")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 9, 9)
        shadowOf(env.context.contentResolver).registerInputStream(uri, bytes.inputStream())
        val attachment = attachments.importFromUri(uri)
        assertEquals("photo.png", attachment.name)
        assertEquals(bytes.size.toLong(), attachment.size)
        assertArrayEquals(bytes, attachments.file(attachment.id)!!.readBytes())
        assertEquals(attachment, attachments.metadata(attachment.id))
    }

    @Test
    fun unsafeIdsAreHashed() {
        assertEquals("abc-123_x.y", AttachmentPaths.fileName("abc-123_x.y"))
        val hashed = AttachmentPaths.fileName("../../etc/passwd")
        assertTrue(hashed.startsWith("h_") && '/' !in hashed)
        assertTrue(AttachmentPaths.fileName("..").startsWith("h_"))
    }
}
