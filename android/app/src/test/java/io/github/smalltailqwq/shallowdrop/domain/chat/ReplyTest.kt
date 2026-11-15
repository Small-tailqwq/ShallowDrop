package io.github.smalltailqwq.shallowdrop.domain.chat

import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.core.model.FilePurpose
import io.github.smalltailqwq.shallowdrop.core.model.FileSource
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyError
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import io.github.smalltailqwq.shallowdrop.core.model.WebSearchResult
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTest {
    private var seq = 0

    private fun user(content: String, files: List<FileAttachment> = emptyList()) =
        Message.User(id = "u${seq++}", createdAt = 0, content = content, files = files)

    private fun reply(
        vararg blocks: ReplyBlock,
        status: ReplyStatus = ReplyStatus.Done,
        stopReason: StopReason? = StopReason.EndTurn,
        prefix: Boolean = true,
        reminder: String? = null,
        thinking: Boolean = false,
    ) = Message.Assistant(
        id = "a${seq++}",
        createdAt = 0,
        status = status,
        stopReason = stopReason,
        blocks = blocks.toList(),
        prefix = prefix,
        latestReminder = reminder,
        thinking = thinking,
    )

    private fun text(value: String) = ReplyBlock.Text(value)

    private fun readLinkUse(id: String) = ReplyBlock.ServerToolUse(
        id = id,
        name = "deepseek_read_url",
        input = ServerToolInput.ReadLink("https://a.example"),
        kind = ServerToolKind.ReadLink,
    )

    private fun readLinkResult(id: String, signature: String?) = ReplyBlock.WebFetchToolResult(
        toolUseId = id,
        url = "https://a.example",
        title = "A",
        kind = ServerToolKind.ReadLink,
        signature = signature,
    )

    private fun assistantParam(messages: List<MessageParam>, index: Int) =
        messages.filterIsInstance<MessageParam.Assistant>()[index].content

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun contextExcludesErrorTemplateRefusalAndEmptyReplies() {
        val history = listOf(
            user("q1"), reply(text("ok1")),
            user("q2"), reply(text("x"), status = ReplyStatus.Error),
            user("q3"), reply(ReplyBlock.Text("模板", isTemplate = true)),
            user("q4"), reply(text("拒绝"), stopReason = StopReason.Refusal),
            user("q5"), reply(ReplyBlock.Thinking("only thinking")),
            user("q6"), reply(text("stopped"), status = ReplyStatus.Stopped),
            user("q7"), reply(text("pending"), status = ReplyStatus.Pending),
        )
        val rounds = contextRounds(history)
        assertEquals(listOf("q1", "q6"), rounds.map { it.first.content })
        val messages = toRequestMessages(history, user("now"))
        assertEquals(5, messages.size)
        assertEquals(UserContentParam.Text("now"), (messages.last() as MessageParam.User).content.single())
    }

    @Test
    fun historyDropsThinkingAndSearchButKeepsSignedReadLink() {
        val history = listOf(
            user("q"),
            reply(
                ReplyBlock.Thinking("想", signature = "sig"),
                text("前"),
                readLinkUse("rl1"),
                readLinkResult("rl1", signature = "page-signature"),
                readLinkUse("rl2"),
                readLinkResult("rl2", signature = " "),
                ReplyBlock.ServerToolUse("s1", "deepseek_web_search", ServerToolInput.Search(queries = listOf("x")), ServerToolKind.Search),
                ReplyBlock.WebSearchToolResult("s1", listOf(WebSearchResult(title = "t", url = "u")), kind = ServerToolKind.Search),
                text("后"),
                ReplyBlock.Thinking("再想"),
                text("尾"),
            ),
        )
        val content = assistantParam(toRequestMessages(history, user("now")), 0)
        assertEquals(listOf("text", "server_tool_use", "web_fetch_tool_result", "text", "text"), content.map { it.str("type") })
        assertEquals("前", content[0].str("text"))
        // 紧跟回传的工具块的正文不加空行；被省略的思考或工具隔开的两段正文用空行连接
        assertEquals("后", content[3].str("text"))
        assertEquals("\n\n尾", content[4].str("text"))
        val use = content[1]
        assertEquals("rl1", use.str("id"))
        assertEquals("read_link", use.str("kind"))
        assertEquals("read_link", use["input"]!!.jsonObject.str("action"))
        val result = content[2]
        assertEquals("rl1", result.str("tool_use_id"))
        assertEquals("page-signature", result.str("signature"))
        assertEquals("web_fetch_result", result["content"]!!.jsonObject.str("type"))
    }

    @Test
    fun readLinkResultUsesRawContentWhenPresent() {
        val rawContent = buildJsonObject { put("type", JsonPrimitive("web_fetch_result")); put("url", JsonPrimitive("raw-url")) }
        val history = listOf(
            user("q"),
            reply(readLinkUse("rl"), readLinkResult("rl", "sig").copy(raw = rawContent), text("a")),
        )
        val content = assistantParam(toRequestMessages(history, user("now")), 0)
        assertEquals(rawContent, content[1]["content"])
    }

    @Test
    fun latestReminderInsertedOnlyWhenChanged() {
        val history = listOf(
            user("q1"), reply(text("a"), reminder = "r1"),
            user("q2"), reply(text("b"), reminder = "r1"),
            user("q3"), reply(text("c")),
            user("q4"), reply(text("d"), reminder = "r2"),
        )
        val messages = toRequestMessages(history, user("now"), latestReminder = "r2")
        val kinds = messages.map {
            when (it) {
                is MessageParam.LatestReminder -> "R:${it.content}"
                is MessageParam.User -> "U"
                is MessageParam.Assistant -> "A"
            }
        }
        assertEquals(listOf("R:r1", "U", "A", "U", "A", "U", "A", "R:r2", "U", "A", "U"), kinds)
    }

    @Test
    fun userParamPutsFilesFirstAndSkipsFilesWithoutServerId() {
        val files = listOf(
            FileAttachment(id = "1", serverId = "file-img", name = "a.png", size = 1, isImage = true),
            FileAttachment(id = "2", serverId = "file-doc", name = "b.pdf", size = 1, isImage = false),
            FileAttachment(id = "3", serverId = null, name = "c.pdf", size = 1, isImage = false),
        )
        val messages = toRequestMessages(emptyList(), user("看图", files))
        assertEquals(
            listOf(
                UserContentParam.Image(FileSource(fileId = "file-img")),
                UserContentParam.Document(FileSource(fileId = "file-doc")),
                UserContentParam.Text("看图"),
            ),
            (messages.single() as MessageParam.User).content,
        )
        assertTrue(hasFileBlocks(messages))
        assertFalse(hasFileBlocks(toRequestMessages(emptyList(), user("x"))))
    }

    @Test
    fun historyQuestionWithoutTextOrUsableFilesIsOmitted() {
        val orphan = FileAttachment(id = "1", name = "a.png", size = 1, isImage = true)
        val history = listOf(user("", listOf(orphan)), reply(text("看不到图")))
        assertEquals(1, toRequestMessages(history, user("now")).size)
    }

    @Test
    fun continuationAppendsPrefixWithCurrentThinkingOnly() {
        val prefix = reply(
            ReplyBlock.Thinking("  "),
            ReplyBlock.Thinking("思考", signature = "sig"),
            text("半句"),
            status = ReplyStatus.Stopped,
            thinking = true,
        )
        val messages = toRequestMessages(emptyList(), user("q"), prefix)
        val content = (messages.last() as MessageParam.Assistant).content
        assertEquals(listOf("thinking", "text"), content.map { it.str("type") })
        assertEquals("sig", content[0].str("signature"))
        // 思考关闭时续写不带思考
        val plain = toRequestMessages(emptyList(), user("q"), prefix, thinking = false)
        assertEquals(listOf("text"), (plain.last() as MessageParam.Assistant).content.map { it.str("type") })
        // 没有正文和有效思考时直接重发原问题
        val empty = toRequestMessages(emptyList(), user("q"), reply(text("  "), status = ReplyStatus.Stopped))
        assertTrue(empty.last() is MessageParam.User)
        // prefix=false（导入的回复）不作为续写前缀
        val imported = toRequestMessages(emptyList(), user("q"), reply(text("x"), status = ReplyStatus.Stopped, prefix = false))
        assertTrue(imported.last() is MessageParam.User)
    }

    @Test
    fun continueConditions() {
        assertTrue(canContinueReply(reply(text("a"), status = ReplyStatus.Stopped, stopReason = null)))
        assertTrue(canContinueReply(reply(text("a"), stopReason = StopReason.MaxTokens)))
        assertFalse(canContinueReply(reply(text("a"), stopReason = StopReason.EndTurn)))
        assertFalse(canContinueReply(reply(text("a"), status = ReplyStatus.Error, stopReason = StopReason.MaxTokens)))
        assertFalse(canContinueReply(reply(text("a"), status = ReplyStatus.Stopped, prefix = false)))
        assertFalse(canContinueReply(reply(text("a"), status = ReplyStatus.Stopped, stopReason = StopReason.ToolUse)))
        assertFalse(canContinueReply(reply(readLinkUse("x"), text("a"), status = ReplyStatus.Stopped)))
        assertFalse(canContinueReply(reply(ReplyBlock.Text("t", isTemplate = true), status = ReplyStatus.Stopped)))
    }

    @Test
    fun applyEventAccumulatesBlocksAndTimesThinking() {
        val indexes = HashMap<Int, Int>()
        var r = reply(status = ReplyStatus.Pending, stopReason = null)
        val events = listOf(
            100L to messageStart(),
            100L to thinkingStart(0),
            200L to thinkingDelta(0, "想"),
            300L to signatureDelta(0, "si"),
            300L to signatureDelta(0, "g"),
            1600L to blockStop(0),
            1700L to toolUseStart(1),
            1700L to textDelta(1, "丢弃"),
            1800L to textStart(2),
            1800L to textDelta(2, "第一段"),
            1800L to citationDelta(2, "https://a"),
            1800L to citationDelta(2, "https://b"),
            1800L to blockStop(2),
            1900L to textStart(3, "第二段"),
            1900L to messageDelta(StopReason.MaxTokens),
        )
        for ((now, event) in events) r = applyEvent(r, event, now, indexes)
        assertEquals(ReplyStatus.Streaming, r.status)
        assertFalse("客户端工具调用后不可作为前缀", r.prefix)
        assertEquals(3, r.blocks.size)
        val thinking = r.blocks[0] as ReplyBlock.Thinking
        assertEquals("想", thinking.thinking)
        assertEquals("sig", thinking.signature)
        assertEquals(100L, thinking.startedAt)
        assertEquals(1600L, thinking.endedAt)
        val first = r.blocks[1] as ReplyBlock.Text
        assertEquals("第一段", first.text)
        assertEquals(listOf("https://a", "https://b"), first.citations.map { it.url })
        assertEquals("第二段", (r.blocks[2] as ReplyBlock.Text).text)
        assertEquals(StopReason.MaxTokens, r.stopReason)
        assertEquals(2, thinkingSeconds(thinking.startedAt!!, thinking.endedAt!!))
    }

    @Test
    fun settleClosesThinkingAndDropsRefusedContent() {
        val open = reply(ReplyBlock.Thinking("x", startedAt = 10), text("y"), status = ReplyStatus.Streaming, stopReason = null)
        val settled = settleReply(open, ReplyStatus.Stopped, 500)
        assertEquals(500L, (settled.blocks[0] as ReplyBlock.Thinking).endedAt)
        val refused = reply(
            ReplyBlock.Thinking("x"),
            text("y"),
            readLinkUse("r"),
            status = ReplyStatus.Streaming,
            stopReason = StopReason.Refusal,
        )
        val done = settleReply(refused, ReplyStatus.Done, 0)
        assertEquals(listOf("r"), done.blocks.map { (it as ReplyBlock.ServerToolUse).id })
        val error = ReplyError(ReplyErrorKind.Network, "boom")
        assertEquals(error, settleReply(open, ReplyStatus.Error, 0, error).error)
    }

    @Test
    fun applyEventIgnoresDeltaForUnknownIndex() {
        val r = reply(status = ReplyStatus.Streaming, stopReason = null)
        val next = applyEvent(r, textDelta(9, "x"), 0, HashMap())
        assertEquals(r, next)
        assertEquals(r.copy(), applyEvent(r, MessageStreamEvent.MessageStop, 0, HashMap()))
    }

    @Test
    fun latestReminderFormat() {
        val zone = ZoneId.of("Asia/Shanghai")
        // 2026-11-15 00:30 上海时间 = 2026-11-14 16:30 UTC，日期按本地时区
        val now = LocalDate.of(2026, 11, 15).atTime(0, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-11-15,星期日,,Android,中文", createLatestReminder(ReminderLocale.ZhCN, now, zone))
        assertEquals("2026-11-14,Saturday,,Android,English", createLatestReminder(ReminderLocale.EnUS, now, ZoneOffset.UTC))
        assertEquals(ReminderLocale.ZhCN, ReminderLocale.resolve(AppLocale.System, Locale.TRADITIONAL_CHINESE))
        assertEquals(ReminderLocale.EnUS, ReminderLocale.resolve(AppLocale.System, Locale.JAPAN))
        assertEquals(ReminderLocale.EnUS, ReminderLocale.resolve(AppLocale.EnUS, Locale.CHINA))
    }

    @Test
    fun uploadPurposeRules() {
        assertNull(uploadPurpose("a.pdf", isImage = true))
        assertEquals(FilePurpose.RichLayoutRecognition, uploadPurpose("A.PDF", false))
        assertEquals(FilePurpose.RichLayoutRecognition, uploadPurpose("x.pptx", false))
        assertEquals(FilePurpose.UserData, uploadPurpose("x.csv", false))
        assertEquals(FilePurpose.UserData, uploadPurpose("noext", false))
        assertEquals("image/png", uploadMimeType("a.PNG", ""))
        assertEquals(listOf("file-api-a1", "file-api-b"), extractFileIds("bad file-api-a1, file-api-b and file-api-a1"))
    }
}
