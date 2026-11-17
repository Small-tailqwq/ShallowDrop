package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ContextSummary
import io.github.smalltailqwq.shallowdrop.core.model.LocationMode
import io.github.smalltailqwq.shallowdrop.core.model.LocationPreference
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import io.github.smalltailqwq.shallowdrop.data.db.DbJson
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // 2026-11-17 05:30 UTC
    private val now = 1_794_893_400_000L

    @Test
    fun formatsTimeZoneAndLocation() {
        assertEquals(
            "（当前情况：2026-11-17 星期二 14:30，UTC+09:00 Asia/Tokyo；位置：日本 东京）",
            createCurrentState(ReminderLocale.ZhCN, now, ZoneId.of("Asia/Tokyo"), "日本,东京"),
        )
        assertEquals(
            "(Current situation: 2026-11-16 Monday 21:30, UTC-08:00 America/Los_Angeles)",
            createCurrentState(ReminderLocale.EnUS, now, ZoneId.of("America/Los_Angeles")),
        )
        // 固定偏移没有地区名；UTC 不带偏移数字
        assertEquals("（当前情况：2026-11-17 星期二 05:30，UTC）", createCurrentState(ReminderLocale.ZhCN, now, ZoneOffset.UTC))
        assertEquals("（当前情况：2026-11-17 星期二 11:00，UTC+05:30）", createCurrentState(ReminderLocale.ZhCN, now, ZoneOffset.ofHoursMinutes(5, 30)))
    }

    @Test
    fun withoutTimeOnlyDateAndLocationRemain() {
        assertEquals(
            "（当前情况：2026-11-17 星期二；位置：日本 东京）",
            createCurrentState(ReminderLocale.ZhCN, now, ZoneId.of("Asia/Tokyo"), "日本,东京", includeTime = false),
        )
        assertNull(createCurrentState(ReminderLocale.ZhCN, now, ZoneOffset.UTC, " ", includeTime = false))
    }

    private fun user(id: String, text: String) = Message.User(id, 0, content = text)

    private fun reply(id: String, state: String?) = Message.Assistant(
        id, 0, status = ReplyStatus.Done, stopReason = StopReason.EndTurn, blocks = listOf(ReplyBlock.Text("好")), currentState = state,
    )

    private fun MessageParam.texts() = (this as MessageParam.User).content.map { (it as UserContentParam.Text).text }

    @Test
    fun requestReplaysEachRoundsSnapshotBeforeItsText() {
        val history = listOf(user("u1", "第一问"), reply("a1", "（当前情况：甲）"), user("u2", "第二问"), reply("a2", null))
        val messages = toRequestMessages(history, user("u3", "第三问"), currentState = "（当前情况：丙）")
        assertEquals(listOf("（当前情况：甲）", "第一问"), messages[0].texts())
        assertEquals(listOf("第二问"), messages[2].texts())
        assertEquals(listOf("（当前情况：丙）", "第三问"), messages[4].texts())
    }

    @Test
    fun snapshotDoesNotChangeStoredJsonOrSummaryFingerprintWhenAbsent() {
        val plain = reply("a1", null)
        assertFalse("currentState" in DbJson.encode(plain))
        assertEquals("（当前情况：甲）", (DbJson.decode(DbJson.encode(reply("a1", "（当前情况：甲）"))) as Message.Assistant).currentState)
        // 有无快照，被总结内容的指纹相同
        assertEquals(
            contextFingerprint(listOf(user("u1", "问"), plain)),
            contextFingerprint(listOf(user("u1", "问"), reply("a1", "（当前情况：甲）"))),
        )
        val summary = ContextSummary(contextFingerprint(listOf(user("u1", "问"), plain)), ChatModel.Flash, "摘要")
        assertEquals(summary, compactionContext(listOf(user("u1", "问"), reply("a1", "（当前情况：甲）").copy(contextSummary = summary)), ChatModel.Flash).summary)
    }

    @Test
    fun engineSendsStoresAndReplaysTheSnapshot() = runTest {
        val api = FakeChatApi()
        val store = FakeSessionStore()
        val settings = FakeSettings()
        settings.setShareTime(true)
        settings.setLocation(LocationPreference(LocationMode.Custom, "日本,东京"))
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        var time = now
        var ids = 0
        val engine = ChatEngine(
            api, store, FakeAttachmentStore(tmp.newFolder()), settings, scope,
            resolveLocation = { preference, _ -> preference.custom },
            clock = { time }, idGenerator = { "id${ids++}" }, zone = { ZoneId.of("Asia/Tokyo") }, systemLocale = { Locale.CHINA },
        )
        try {
            val options = SendOptions(thinking = false, search = false, model = ChatModel.Flash)
            val id = engine.send(null, "第一问", emptyList(), options)
            advanceUntilIdle()
            val first = "（当前情况：2026-11-17 星期二 14:30，UTC+09:00 Asia/Tokyo；位置：日本 东京）"
            assertEquals(listOf(first, "第一问"), api.requests.single().messages.last().texts())
            assertEquals(first, (visibleMessages(store.data.value.getValue(id)).last() as Message.Assistant).currentState)

            time += 3_600_000
            settings.setShareTime(false)
            settings.setLocation(LocationPreference())
            engine.send(id, "第二问", emptyList(), options)
            advanceUntilIdle()
            val users = api.requests.last().messages.filterIsInstance<MessageParam.User>()
            assertEquals(listOf(first, "第一问"), users[0].texts())
            // 不发送时刻、也没有位置时没有这一段
            assertEquals(listOf("第二问"), users[1].texts())
        } finally {
            scope.cancel()
        }
    }
}
