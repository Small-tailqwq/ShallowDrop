package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.data.db.RoomSessionStore
import io.github.smalltailqwq.shallowdrop.data.db.TestEnv
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LongRetryArchiveTest {
    private val env = TestEnv()
    private val store = RoomSessionStore(env.db, env.paths)
    private val backup = ArchiveHistoryBackup(env.db, env.paths, { null }, { null })

    @After fun tearDown() = env.close()

    @Test fun exportedFiveThousandRepliesCanBeReadBack() = runTest {
        val text = "x".repeat(4_096)
        val messages = buildList {
            add(Message.User("u", 1, selectedChildId = "a4999", content = "question"))
            repeat(5_000) { index ->
                add(Message.Assistant("a$index", index.toLong() + 2, parentId = "u", status = ReplyStatus.Done,
                    blocks = listOf(ReplyBlock.Text(text))))
            }
        }
        store.save(Session("s", "Retries", 1, 5_001, messages, "u"))
        val file = File(env.filesDir, "history.zip")
        assertEquals(1, backup.exportTo { file.outputStream() })
        val jsonSize = ArchiveZipReader(file).use { reader ->
            reader.next()!!.skip()
            reader.next()!!.size
        }
        assertTrue(jsonSize > 16L * 1024 * 1024)
        val result = backup.importFrom({ file.inputStream() })
        val restored = store.load("s")!!.messages
        println("replies=5000 replyChars=4096 exportedJsonBytes=$jsonSize zipBytes=${file.length()} importedMessages=${result.messages}")
        assertEquals(1, result.sessions)
        assertEquals(5_001, result.messages)
        assertEquals(5_000, restored.filterIsInstance<Message.Assistant>().size)
        assertTrue(restored.filterIsInstance<Message.Assistant>().all { it.blocks == listOf(ReplyBlock.Text(text)) })
    }

    @Test fun importsBomAndReplacesMalformedUtf8() = runTest {
        val session = Fx.session("s", "sample", 1, listOf(Fx.user(1, null, "invalid: @")))
        val malformed = session.toString().toByteArray().apply { this[indexOf('@'.code.toByte())] = 0xff.toByte() }
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        val bytes = Fx.zip(
            ArchivePaths.MANIFEST to (bom + Fx.manifest().toString().toByteArray()),
            ArchivePaths.session("s") to (bom + malformed),
            ArchivePaths.INDEX to (bom + Fx.index(session).toString().toByteArray()),
        )
        backup.importFrom({ bytes.inputStream() })
        assertEquals("invalid: \uFFFD", (store.load("s")!!.messages.single() as Message.User).content)
    }

    @Test fun exportRejectsArchiveAboveImportLimit() {
        var written = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) { written++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { written += length }
        }
        val block = ByteArray(1024 * 1024)
        assertThrows(ArchiveLimitException::class.java) {
            ArchiveZipWriter(sink).use { writer ->
                repeat(512) { writer.add("$it", block) }
                writer.finish()
            }
        }
        assertTrue(written <= 512L * 1024 * 1024)
    }
}
