package io.github.smalltailqwq.shallowdrop.domain.memory

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.smalltailqwq.shallowdrop.App
import io.github.smalltailqwq.shallowdrop.core.model.*
import io.github.smalltailqwq.shallowdrop.domain.chat.ChatEngine
import io.github.smalltailqwq.shallowdrop.domain.chat.ReminderLocale
import io.github.smalltailqwq.shallowdrop.domain.chat.applyEvent
import io.github.smalltailqwq.shallowdrop.domain.chat.createCurrentState
import io.github.smalltailqwq.shallowdrop.domain.chat.createLatestReminder
import io.github.smalltailqwq.shallowdrop.domain.chat.replyText
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 记忆与当前情况（时间、时区、定位）、本次对话交互的场景观察：记忆里的常住地与定位不一致、行程过期、
 * 刚说过的短期安排能否进入下一个窗口、需要时刻和时区才能回答的问题等。每个场景按正式流程走：
 * 新窗口只带记忆，每条提问带 latest_reminder 和「当前情况」，需要时在窗口结束后整理记忆。
 * 不设通过标准，只保存回复供人工阅读；结果见 docs/android/memory-scenarios-2026-11-17.md。
 *
 * 显式传入 liveMemory=true 才调用设备中配置的 API Key，固定使用 deepseek-flash，并产生 API 用量。
 * 不读写用户的会话、设置和记忆；结果写到应用私有目录 files/memory-scenario/。
 */
class MemoryScenarioTest {
    /** 用户所在的地方：定位文本（空串表示未开启定位）和设备时区。 */
    private class Place(val location: String, val zone: String)

    /** 一个窗口：当地日期与时刻、所在地、用户逐轮的话；extract 表示结束后整理记忆。 */
    private class Window(val date: String, val place: Place, val turns: List<String>, val extract: Boolean = false, val time: String = "14:30") {
        val zone: ZoneId get() = ZoneId.of(place.zone)
        val millis: Long get() = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()
    }

    private class Scenario(val name: String, val seeds: List<Pair<String, String>>, val windows: List<Window>)

    @Test fun scenarios() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveMemory") == "true")
        val runs = arguments.getString("trials")?.toInt() ?: 5
        val context = instrumentation.targetContext
        val container = (context.applicationContext as App).container
        val settings = container.settingsRepository
        withTimeout(5000) { settings.loaded.first { it } }
        check(settings.settings.value.hasApiKey) { "请先在模拟器 App 中配置 API Key" }
        val apiKey = settings.apiKey().orEmpty()
        val output = File(context.filesDir, "memory-scenario/scenario-${System.currentTimeMillis()}").apply { mkdirs() }

        suspend fun ask(system: String, turns: List<String>, maxTokens: Int?, reminder: String?, state: String?): String {
            val messages = buildList {
                turns.forEachIndexed { index, text ->
                    if (index == turns.lastIndex && reminder != null) add(MessageParam.LatestReminder(reminder))
                    add(if (index % 2 == 0) MessageParam.User(listOfNotNull(state?.let { UserContentParam.Text(it) }, UserContentParam.Text(text)))
                        else MessageParam.Assistant(listOf(buildJsonObject { put("type", "text"); put("text", text) })))
                }
            }
            var failure: Throwable? = null
            repeat(3) { attempt ->
                try {
                    var reply = Message.Assistant("scenario", 0, status = ReplyStatus.Pending)
                    val indexes = HashMap<Int, Int>()
                    container.chatApi.streamMessage(apiKey, CreateMessageRequest(ChatModel.Flash, system, messages, false, emptyList(), maxTokens = maxTokens))
                        .collect { reply = applyEvent(reply, it, System.currentTimeMillis(), indexes) }
                    return replyText(reply).also { check(it.isNotBlank()) { "空回答" } }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failure = error
                    delay(2000L * (attempt + 1))
                }
            }
            throw IllegalStateException("请求失败", failure)
        }

        suspend fun run(scenario: Scenario, number: Int): JsonObject {
            var entries = scenario.seeds.mapIndexed { index, (date, text) -> MemoryEntry("seed$index", text, noon(date), noon(date)) }
            var nextId = 0
            val log = StringBuilder()
            val replies = mutableListOf<JsonElement>()
            fun store() = entries.joinToString("\n") { "(${memoryDate(it.updatedAt, ZoneOffset.UTC)}) ${it.text}" }
            log.append("===== 初始记忆 =====\n").append(store()).append("\n")
            scenario.windows.forEachIndexed { index, window ->
                val system = listOfNotNull(ChatEngine.SYSTEM_PROMPT, memorySystemBlock(entries, window.zone)).joinToString("\n\n")
                val reminder = createLatestReminder(ReminderLocale.ZhCN, window.millis, window.zone, window.place.location)
                val state = createCurrentState(ReminderLocale.ZhCN, window.millis, window.zone, window.place.location)
                log.append("\n===== 窗口 ${index + 1} =====\n").append(state).append("\n\n")
                val transcript = mutableListOf<String>()
                for (turn in window.turns) {
                    transcript += turn
                    transcript += ask(system, transcript.toList(), null, reminder, state)
                    log.append("[用户] ").append(turn).append("\n\n[助手] ").append(transcript.last()).append("\n\n")
                }
                replies += JsonPrimitive(transcript.last())
                if (window.extract) {
                    val turns = transcript.mapIndexed { i, text -> MemoryTurn(i % 2 == 0, text, state = state.takeIf { i % 2 == 0 }) }
                    val raw = ask(MEMORY_EXTRACT_PROMPT, listOf(memoryExtractionInput(entries, turns, LocalDate.parse(window.date), window.zone)),
                        MEMORY_MAX_OUTPUT_TOKENS, null, null)
                    entries = applyMemoryOps(entries, parseMemoryOps(raw).orEmpty(), window.millis, { "new${nextId++}" }).entries
                    log.append("----- 整理输出 -----\n").append(raw).append("\n----- 记忆 -----\n").append(store()).append("\n")
                }
            }
            output.resolve(scenario.name).apply { mkdirs() }.resolve("run-$number.txt").writeText(log.toString())
            return buildJsonObject {
                put("scenario", scenario.name); put("run", number)
                put("replies", JsonArray(replies)); put("store", store())
            }
        }

        withTimeout(1_800_000) {
            val results = SCENARIOS.flatMap { scenario -> (1..runs).map { async(Dispatchers.Default) { run(scenario, it) } } }.awaitAll()
            output.resolve("result.json").writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(results)))
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "完成：${output.name}\n") })
        }
    }

    private companion object {
        fun noon(date: String) = LocalDate.parse(date).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        val TOKYO = Place("日本,东京", "Asia/Tokyo")
        val SEATTLE = Place("美国,西雅图", "America/Los_Angeles")

        /** 未开启定位：只有设备时区 */
        val SEATTLE_NO_LOCATION = Place("", "America/Los_Angeles")
        val TOKYO_NO_LOCATION = Place("", "Asia/Tokyo")
        val BOSTON_NO_LOCATION = Place("", "America/New_York")
        const val NEARBY = "这附近有什么好玩的？"

        val HOME = listOf(
            "2026-03-02" to "用户住在美国西雅图。",
            "2026-03-02" to "用户是软件工程师。",
            "2026-05-11" to "用户喜欢徒步和精酿啤酒。",
            "2026-06-20" to "用户对虾蟹过敏。",
        )

        // 2026-11-17 是星期二
        val SCENARIOS = listOf(
            // 记忆里的常住地与定位不一致
            Scenario("location_conflict", HOME, listOf(Window("2026-11-17", TOKYO, listOf(NEARBY)))),
            Scenario("location_conflict_with_trip", HOME + ("2026-11-10" to "用户 2026 年 11 月 14 日至 11 月 21 日在日本旅行。"),
                listOf(Window("2026-11-17", TOKYO, listOf(NEARBY)))),
            Scenario("location_conflict_food", HOME, listOf(Window("2026-11-17", TOKYO, listOf("晚饭吃什么好？推荐两家。"), time = "18:10"))),
            // 没有定位：时区与常住地一致，或不一致
            Scenario("no_location", HOME, listOf(Window("2026-11-17", SEATTLE_NO_LOCATION, listOf(NEARBY)))),
            Scenario("timezone_only", HOME, listOf(Window("2026-11-17", TOKYO_NO_LOCATION, listOf(NEARBY)))),
            // 行程早已结束
            Scenario("expired_trip", HOME + ("2026-04-02" to "用户 2026 年 4 月 10 日至 4 月 17 日在大阪出差。"),
                listOf(Window("2026-11-17", SEATTLE, listOf("这周末附近有什么好玩的？")))),
            // 需要时刻和时区才能回答
            Scenario("call_home", HOME + ("2026-07-01" to "用户的父母住在西雅图。"),
                listOf(Window("2026-11-17", TOKYO, listOf("现在给我爸妈打电话合适吗？")))),
            Scenario("deadline", HOME + ("2026-11-16" to "用户的项目报告 2026 年 11 月 18 日上午 10 点截止。"),
                listOf(Window("2026-11-17", SEATTLE, listOf("报告我还剩多少时间？"), time = "22:00"))),
            Scenario("late_night", HOME, listOf(Window("2026-11-17", SEATTLE, listOf("有点饿，吃点什么好？"), time = "23:45"))),
            // 短期安排：说的是「明天」，第二天和一周后各问一次
            Scenario("appointment_tomorrow", HOME, listOf(
                Window("2026-11-17", SEATTLE, listOf("我明天下午三点约了牙医洗牙，有什么要提前准备的吗？"), extract = true),
                Window("2026-11-18", SEATTLE, listOf("我今天有什么安排吗？"), time = "09:00"),
                Window("2026-11-25", SEATTLE, listOf("我今天有什么安排吗？"), time = "09:00"),
            )),
            // 短期行程：出差期间与回家之后。用户只说「这边」「回家」，地点要靠当前情况和记忆补全
            Scenario("trip_this_week", HOME, listOf(
                Window("2026-11-17", TOKYO, listOf("我这周在这边出差，周五回家。帮我规划一下周三晚上的自由时间。"), extract = true),
                Window("2026-11-19", TOKYO, listOf("附近有什么好吃的拉面？"), time = "19:00"),
                Window("2026-11-24", SEATTLE, listOf(NEARBY)),
            )),
            // 本次对话里的新信息优先于记忆，并在下一个窗口生效
            Scenario("moved_in_conversation", HOME, listOf(
                Window("2026-11-17", BOSTON_NO_LOCATION, listOf("我上周搬到波士顿了。", "推荐附近适合跑步的公园。"), extract = true),
                Window("2026-11-18", BOSTON_NO_LOCATION, listOf(NEARBY)),
            )),
            // 近期在做的事让含糊的请求有了指向；考试结束后不应再默认
            Scenario("exam_prep", HOME, listOf(
                Window("2026-11-17", SEATTLE, listOf("接下来几周我在准备日语 N2 考试，12 月 6 日考。今天先帮我列个复习重点。"), extract = true),
                Window("2026-11-20", SEATTLE, listOf("给我出三道练习题。")),
                Window("2026-12-10", SEATTLE, listOf("给我出三道练习题。")),
            )),
        )
    }
}
