package io.github.smalltailqwq.shallowdrop.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageBranch
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.Session
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    @Test
    fun versionOneRetainsMessagesBranchesAttachmentsAndPendingImport() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}.db"
        val attachment = FileAttachment("image", name = "photo.png", size = 3, isImage = true)
        val question = Message.User("u", 1, selectedChildId = "a2", content = "question", files = listOf(attachment))
        val old = Message.Assistant("a1", 2, parentId = "u", status = ReplyStatus.Done,
            blocks = listOf(ReplyBlock.Text("正文".repeat(20_000))))
        val chosen = Message.Assistant("a2", 3, parentId = "u", status = ReplyStatus.Streaming,
            blocks = listOf(ReplyBlock.Thinking("思考", startedAt = 2), ReplyBlock.Text("部分回答")))
        val messages = listOf(question, old, chosen)
        val fileDir = File(context.filesDir, "migration-${System.nanoTime()}")
        val paths = AttachmentPaths(fileDir)
        paths.file(attachment.id).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
                val schema = DbJson.json.parseToJsonElement(
                    File("schemas/${AppDatabase::class.java.name}/1.json").readText(),
                ).jsonObject.getValue("database").jsonObject
                for (entityValue in schema.getValue("entities").jsonArray) {
                    val entity = entityValue.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    database.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    for (index in entity["indices"]?.jsonArray.orEmpty()) {
                        database.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                }
                for (query in schema.getValue("setupQueries").jsonArray) database.execSQL(query.jsonPrimitive.content)
                database.execSQL("INSERT INTO sessions VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>("s", "标题", 1, 3, "u"))
                database.execSQL("INSERT INTO staging_sessions VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>("pending", "待导入", 1, 3, "u"))
                messages.forEachIndexed { seq, message ->
                    database.execSQL("INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>("s", message.id, seq, message.createdAt, message.role(), DbJson.encode(message)))
                    database.execSQL("INSERT INTO staging_messages VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>("pending", message.id, seq, message.createdAt, message.role(), DbJson.encode(message)))
                }
                database.execSQL("INSERT INTO files VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any?>("image", "file-image", "photo.png", 3, 1, "image/png"))
                database.execSQL("INSERT INTO message_files VALUES (?, ?, ?)", arrayOf("s", "u", "image"))
                database.execSQL("INSERT INTO import_state VALUES (?, ?)", arrayOf("files_swap_pending", "1"))
                database.version = 1
            }
            val database = AppDatabase.create(context, name)
            try {
                val store = RoomSessionStore(database, paths)
                assertEquals(Session("s", "标题", 1, 3, messages, "u"), store.load("s"))
                assertEquals(listOf(question, chosen), store.loadConversation("s")!!.messages)
                assertEquals(listOf(question.copy(selectedChildId = "a1"), old), store.loadConversation("s", "a1")!!.messages)
                assertEquals(messages.map { MessageBranch(it.id, it.parentId, it.selectedChildId) }, database.chatDao().branches("s"))
                assertEquals(1, database.chatDao().referenceCount("image"))
                assertEquals("file-image", database.chatDao().file("image")!!.serverId)
                assertNotNull(paths.file("image").takeIf { it.isFile })
                assertEquals("1", database.stagingDao().state("files_swap_pending"))
                val staged = database.stagingDao().message("pending", "u")!!
                assertEquals(question, DbJson.decode(staged.json))
                assertEquals("a2", staged.selectedChildId)
                database.stagingDao().commit()
                assertEquals(listOf(question, chosen), store.loadConversation("pending")!!.messages)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
            fileDir.deleteRecursively()
        }
    }
}
