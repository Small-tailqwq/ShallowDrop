package io.github.smalltailqwq.shallowdrop.data.db

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeConversationTest {
    @Test fun opensAndSwitchesFiveThousandLargeRepliesWithoutLoadingHiddenBodies() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "large-conversation-${System.nanoTime()}"
        val db = AppDatabase.create(context, "$name.db")
        val files = File(context.cacheDir, name)
        val store = RoomSessionStore(db, AttachmentPaths(files))
        try {
            val dao = db.chatDao()
            dao.upsertSession(SessionEntity("s", "Long history", 0, 1, "u0"))
            var seq = 0
            suspend fun write(messages: List<Message>) {
                dao.upsertMessages(messages.map { it.toEntity("s", seq++) })
            }
            db.withTransaction {
                repeat(10) { batch ->
                    write(buildList {
                        repeat(100) { offset ->
                            val i = batch * 100 + offset
                            add(Message.User("u$i", i.toLong(), parentId = if (i == 0) null else "a${i - 1}",
                                selectedChildId = "a$i", content = "context-$i:" + "q".repeat(512)))
                            add(Message.Assistant("a$i", i.toLong(), parentId = "u$i",
                                selectedChildId = if (i == 999) "question" else "u${i + 1}",
                                status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text("answer-$i:" + "a".repeat(2048)))))
                        }
                    })
                }
                write(listOf(Message.User("question", 1000, parentId = "a999", selectedChildId = "retry4999", content = "Retry this")))
                repeat(50) { batch ->
                    write(List(100) { offset ->
                        val i = batch * 100 + offset
                        Message.Assistant("retry$i", 1001L + i, parentId = "question", status = ReplyStatus.Done,
                            blocks = listOf(ReplyBlock.Text("version-$i:" + "r".repeat(65_536))))
                    })
                }
            }
            val bytes = db.openHelper.readableDatabase.query("SELECT SUM(length(json)) FROM messages").use {
                it.moveToFirst()
                it.getLong(0)
            }
            val plan = db.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT id, parentId, selectedChildId FROM messages WHERE sessionId = 's' ORDER BY seq",
            ).use {
                it.moveToFirst()
                it.getString(3)
            }
            val samples = mutableListOf<Long>()
            repeat(5) { iteration ->
                val target = if (iteration % 2 == 0) "retry4999" else "retry0"
                val began = SystemClock.elapsedRealtimeNanos()
                val loaded = requireNotNull(store.loadConversation("s", target))
                samples += (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000
                assertEquals(7_001, loaded.branches.size)
                assertEquals(2_002, loaded.messages.size)
                assertEquals(target, loaded.messages.last().id)
                val text = (loaded.messages.last() as Message.Assistant).blocks.single() as ReplyBlock.Text
                assertTrue(text.text.startsWith("version-${target.removePrefix("retry")}:"))
                assertTrue(loaded.messages.none { it.id.startsWith("retry") && it.id != target })
            }
            val report = "rows=7001 hiddenReplies=4999 storedJsonBytes=$bytes loadedMessages=2002 loadMs=$samples pssKb=${Debug.getPss()} branchPlan=$plan"
            File(context.filesDir, "large-conversation-result.txt").writeText(report)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$report\n") })
        } finally {
            db.close()
            context.deleteDatabase("$name.db")
            files.deleteRecursively()
        }
    }
}
