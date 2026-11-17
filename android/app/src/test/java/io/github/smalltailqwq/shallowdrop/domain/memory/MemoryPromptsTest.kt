package io.github.smalltailqwq.shallowdrop.domain.memory

import io.github.smalltailqwq.shallowdrop.core.model.MemoryChangeType
import io.github.smalltailqwq.shallowdrop.core.model.MemoryEntry
import io.github.smalltailqwq.shallowdrop.core.model.MemoryQuote
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptsTest {
    private fun entry(id: String, text: String, quotes: List<MemoryQuote> = emptyList()) = MemoryEntry(id, text, 0, 0, quotes)

    private val entries = listOf(entry("a", "用户住在苏州。"), entry("b", "用户对花生过敏。"), entry("c", "用户养了一只猫。"))

    private var ids = 0

    private fun apply(ops: List<MemoryOp>, current: List<MemoryEntry> = entries, guard: Boolean = true, shown: List<String> = current.map { it.id }) =
        applyMemoryOps(current, ops, now = 100, newId = { "n${ids++}" }, guardDeletes = guard, shown = shown)

    @Test
    fun parsesOpsInsideSurroundingText() {
        val ops = parseMemoryOps("""好的：```json
            {"ops":[{"op":"add","text":"用户叫林澈。","from":[1,2]},{"op":"update","id":"m1","text":"用户住在杭州。"},{"op":"delete","id":"m2","from":[3]},{"op":"noop"}]}
            ```""")
        assertEquals(
            listOf(MemoryOp.Add("用户叫林澈。", listOf(1, 2)), MemoryOp.Update("m1", "用户住在杭州。"), MemoryOp.Delete("m2", listOf(3))),
            ops,
        )
    }

    @Test
    fun emptyOpsAndInvalidOutput() {
        assertEquals(emptyList<MemoryOp>(), parseMemoryOps("""{"ops":[]}"""))
        assertNull(parseMemoryOps("没有需要变动的内容"))
        assertNull(parseMemoryOps("""{"ops":"none"}"""))
    }

    @Test
    fun appliesAddUpdateDelete() {
        val update = apply(listOf(MemoryOp.Add("用户叫林澈。"), MemoryOp.Update("m1", "用户住在杭州。"), MemoryOp.Delete("m2")))
        assertEquals(listOf("用户住在杭州。", "用户养了一只猫。", "用户叫林澈。"), update.entries.map { it.text })
        assertEquals(listOf(MemoryChangeType.Add, MemoryChangeType.Update, MemoryChangeType.Delete), update.changes.map { it.type })
        assertEquals("用户住在苏州。", update.changes[1].before)
        assertEquals(100, update.entries.first().updatedAt)
        assertEquals(0, update.entries[1].updatedAt)
    }

    @Test
    fun ignoresInvalidOps() {
        val update = apply(listOf(
            MemoryOp.Add(""),
            MemoryOp.Add("用户住在苏州。"),
            MemoryOp.Add("用户的密钥是 sk-abcdefghijklmnopqrstuvwxyz。"),
            MemoryOp.Add("长".repeat(MEMORY_TEXT_LIMIT + 1)),
            MemoryOp.Update("m9", "不存在的编号"),
            MemoryOp.Update("m1", "用户住在苏州。"),
            MemoryOp.Delete("x"),
        ))
        assertEquals(entries, update.entries)
        assertTrue(update.changes.isEmpty())
    }

    @Test
    fun massDeleteIsDroppedOnlyWhenGuarded() {
        val many = (1..8).map { entry("e$it", "记忆 $it") }
        val ops = (1..5).map { MemoryOp.Delete("m$it") } + MemoryOp.Add("新的一条")
        val guarded = apply(ops, many)
        assertEquals(9, guarded.entries.size)
        assertEquals(listOf(MemoryChangeType.Add), guarded.changes.map { it.type })
        assertEquals(4, apply(ops, many, guard = false).entries.size)
        // 少量删除不受限制
        assertEquals(1, apply(listOf(MemoryOp.Delete("m1"), MemoryOp.Delete("m2")), entries).entries.size)
    }

    @Test
    fun aliasesFollowTheShownOrderNotTheCurrentList() {
        // 请求期间用户删掉了 a：m1 不再存在，m2 仍指向 b
        val update = apply(listOf(MemoryOp.Update("m1", "改不到"), MemoryOp.Delete("m2")), entries.drop(1), shown = listOf("a", "b", "c"))
        assertEquals(listOf("c"), update.entries.map { it.id })
    }

    @Test
    fun quotesAccumulateUpToTheLimit() {
        val old = (1..MEMORY_QUOTE_LIMIT).map { MemoryQuote("旧 $it", it.toLong()) }
        val update = applyMemoryOps(
            listOf(entry("a", "旧", old)), listOf(MemoryOp.Update("m1", "新")), now = 9, newId = { "x" },
            quotes = { listOf(MemoryQuote("新的原话", 9)) },
        )
        assertEquals(MEMORY_QUOTE_LIMIT, update.entries.single().quotes.size)
        assertEquals("新的原话", update.entries.single().quotes.last().text)
        assertEquals("旧 2", update.entries.single().quotes.first().text)
    }

    @Test
    fun systemBlockAndInputs() {
        assertNull(memorySystemBlock(emptyList(), ZoneOffset.UTC))
        val block = memorySystemBlock(entries, ZoneOffset.UTC)!!
        assertTrue(block.startsWith("【用户记忆】\n- (1970-01-01) 用户住在苏州。\n"))
        assertTrue(block.endsWith(MEMORY_READ_RULES))

        val input = memoryExtractionInput(
            entries,
            listOf(MemoryTurn(true, "我搬到杭州了"), MemoryTurn(false, "好".repeat(1000)),
                MemoryTurn(true, "谢谢", state = "（当前情况：2026-03-20 星期五 09:00，UTC+08:00 Asia/Shanghai；位置：杭州）")),
            LocalDate.of(2026, 3, 20), ZoneOffset.UTC,
        )
        assertTrue(input.startsWith("今天是 2026-03-20。"))
        assertTrue("[m2] (1970-01-01) 用户对花生过敏。" in input)
        assertTrue("[用户 1] 我搬到杭州了\n[助手] ${"好".repeat(600)}\n[用户 2][2026-03-20 星期五 09:00，UTC+08:00 Asia/Shanghai；位置：杭州] 谢谢" in input)
    }

    @Test
    fun askSystemListsQuotesAndAnswerFallsBackToPlainText() {
        val system = memoryAskSystem(
            listOf(entry("a", "用户住在杭州。", listOf(MemoryQuote("我搬到\n杭州了", 0, "s", "u", "通勤")))),
            LocalDate.of(2026, 5, 20), ZoneOffset.UTC,
        )
        assertTrue("[m1] (更新于 1970-01-01) 用户住在杭州。\n    原话（1970-01-01，对话《通勤》）：我搬到 杭州了\n" in system)
        assertEquals("你住在杭州。" to listOf<MemoryOp>(MemoryOp.Delete("m1")), parseMemoryAnswer("""{"answer":"你住在杭州。","ops":[{"op":"delete","id":"m1"}]}"""))
        assertEquals("直接的回答" to emptyList<MemoryOp>(), parseMemoryAnswer(" 直接的回答 "))
    }

    @Test
    fun untilIsParsedKeptAndShownToTheExtractor() {
        val ops = parseMemoryOps(
            """{"ops":[{"op":"add","text":"用户 4 月去大阪出差。","until":"2026-04-17"},{"op":"add","text":"用户住在杭州。","until":"下周"},
                {"op":"update","id":"m1","text":"用户搬到了杭州。"},{"op":"merge","ids":["m1"," m2 "],"text":"合并"}]}""",
        )!!
        assertEquals(
            listOf(MemoryOp.Add("用户 4 月去大阪出差。", until = "2026-04-17"), MemoryOp.Add("用户住在杭州。"),
                MemoryOp.Update("m1", "用户搬到了杭州。"), MemoryOp.Merge(listOf("m1", "m2"), "合并")),
            ops,
        )
        val trip = entry("t", "用户去大阪出差。").copy(until = "2026-04-17")
        val update = apply(ops, listOf(trip))
        // 修改时没给到期日则沿用；合并操作在普通整理里不生效
        assertEquals(listOf("2026-04-17", "2026-04-17", null), update.entries.map { it.until })
        assertEquals(3, update.entries.size)
        assertTrue("[m1] (1970-01-01，until 2026-04-17) 用户去大阪出差。" in memoryExtractionInput(listOf(trip), emptyList(), LocalDate.of(2026, 4, 1), ZoneOffset.UTC))
        assertTrue("until" !in memorySystemBlock(listOf(trip), ZoneOffset.UTC)!!)
    }

    @Test
    fun expiryWaitsForTheGracePeriod() {
        val list = listOf(entry("a", "长期"), entry("b", "行程").copy(until = "2026-04-17"), entry("c", "坏日期").copy(until = "很快"))
        assertTrue(expiredMemories(list, LocalDate.of(2026, 5, 1)).isEmpty())
        assertEquals(listOf("b"), expiredMemories(list, LocalDate.of(2026, 5, 2)).map { it.id })
    }

    @Test
    fun writesBeyondTheBudgetAreDropped() {
        val full = (1..20).map { entry("e$it", "$it".padEnd(MEMORY_BUDGET / 20, '满')) }
        assertEquals(MEMORY_BUDGET, memorySize(full))
        val update = apply(listOf(MemoryOp.Add("新的一条"), MemoryOp.Update("m1", "长".repeat(MEMORY_TEXT_LIMIT)), MemoryOp.Update("m2", "短")), full)
        assertEquals(listOf(MemoryChangeType.Update), update.changes.map { it.type })
        assertEquals("短", update.entries[1].text)
        // 腾出空间后可以新增
        assertEquals(20, apply(listOf(MemoryOp.Delete("m1"), MemoryOp.Add("新的一条")), full).entries.size)
    }

    @Test
    fun tidyMergesAndShortensWithoutLosingSpecifics() {
        val list = listOf(
            entry("a", "用户工作中常用的开发板是 STM32H7。", listOf(MemoryQuote("板子", 1))).copy(createdAt = 5, updatedAt = 10),
            entry("b", "用户使用 J-Link 调试器。", listOf(MemoryQuote("调试器", 2))).copy(createdAt = 3, updatedAt = 20),
            entry("c", "用户说过自己平时每周都会去跑步三次，每次 5 公里。"),
            entry("d", "用户的手机是 Pixel 9。"),
            entry("e", "用户的车是比亚迪海豹。").copy(until = "2026-01-01"),
        )
        val update = applyMemoryTidy(list, listOf(
            MemoryOp.Merge(listOf("m1", "m2"), "用户常用 STM32H7 开发板和 J-Link 调试器。"),
            MemoryOp.Update("m3", "用户每周跑步三次，每次 5 公里。"),
            // 丢了型号里的数字
            MemoryOp.Update("m4", "用户用 Pixel。"),
            // 已经被合并过的条目不能再参与
            MemoryOp.Merge(listOf("m2", "m5"), "用户有车。"),
            // 没有变短
            MemoryOp.Update("m5", "用户的车是比亚迪的海豹。"),
            MemoryOp.Delete("m5"),
            MemoryOp.Add("凭空新增"),
        ), list)
        assertEquals(listOf("a", "c", "d", "e"), update.entries.map { it.id })
        val merged = update.entries.first()
        assertEquals("用户常用 STM32H7 开发板和 J-Link 调试器。", merged.text)
        assertEquals(listOf(3L, 20L), listOf(merged.createdAt, merged.updatedAt))
        assertEquals(listOf("板子", "调试器"), merged.quotes.map { it.text })
        assertEquals("用户每周跑步三次，每次 5 公里。", update.entries[1].text)
        assertEquals(0, update.entries[1].updatedAt)
        assertEquals(list.drop(3), update.entries.drop(2))
        assertEquals(listOf(MemoryChangeType.Update, MemoryChangeType.Delete, MemoryChangeType.Update), update.changes.map { it.type })
        assertEquals("用户使用 J-Link 调试器。", update.changes[1].before)

        // 请求期间被用户改过的条目不动
        val edited = list.map { if (it.id == "a") it.copy(text = "用户常用的开发板是 ESP32。") else it }
        assertTrue(applyMemoryTidy(edited, listOf(MemoryOp.Merge(listOf("m1", "m2"), "用户常用 STM32H7 和 J-Link。")), list).changes.isEmpty())
        // 都是短期安排时保留较晚的到期日；混有长期事实时不带到期日
        val trips = listOf(entry("x", "用户 4 月 10 日去大阪。").copy(until = "2026-04-10"), entry("y", "用户 4 月 17 日从大阪回来。").copy(until = "2026-04-17"))
        assertEquals("2026-04-17", applyMemoryTidy(trips, listOf(MemoryOp.Merge(listOf("m1", "m2"), "用户 4 月 10 日至 17 日去大阪。")), trips).entries.single().until)
        assertNull(applyMemoryTidy(list, listOf(MemoryOp.Merge(listOf("m4", "m5"), "用户有 Pixel 9 和比亚迪海豹。")), list).entries.last().until)
    }
}
