package io.github.smalltailqwq.shallowdrop.data.history

import io.github.smalltailqwq.shallowdrop.core.contract.HistoryException
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyError
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.Session
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 纯 Kotlin：schema 校验与片段转换。 */
class ArchiveConvertTest {
    private fun local(session: JsonObject) = ToLocal.toLocalSession(ArchiveSchema.readSessionFile(session))

    private fun expectCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (error: HistoryException) {
            assertEquals(code, error.code)
        }
    }

    private fun Session.byId(id: String) = messages.single { it.id == id }

    @Test
    fun timeParsing() {
        assertEquals(
            ZonedDateTime.of(2026, 11, 11, 2, 33, 23, 270_000_000, ZoneOffset.UTC).toInstant().toEpochMilli(),
            ArchiveTime.parseMillis("2026-11-11T02:33:23.270Z"),
        )
        // 微秒、无小数秒都接受，精度截断到毫秒
        assertEquals(ArchiveTime.parseMillis("2026-11-11T02:33:23.270Z"), ArchiveTime.parseMillis("2026-11-11T02:33:23.270999Z"))
        assertEquals(ArchiveTime.parseMillis("2026-11-11T02:33:23.000Z"), ArchiveTime.parseMillis("2026-11-11T02:33:23Z"))
        assertNull(ArchiveTime.parseMillis("2026-11-11T02:33:23+08:00"))
        assertNull(ArchiveTime.parseMillis("2026-02-30T00:00:00Z"))
        assertNull(ArchiveTime.parseMillis("2026-11-11 02:33:23Z"))
        assertEquals("2026-11-11T02:33:23.270Z", ArchiveTime.format(ArchiveTime.parseMillis("2026-11-11T02:33:23.270Z")!!))
        assertEquals("2026-01-02T00:00:00.000Z", ArchiveTime.normalizeOrNull("2026-01-02"))
        assertEquals("2026-01-01T16:00:00.000Z", ArchiveTime.normalizeOrNull("2026-01-02T00:00:00+08:00"))
        assertNull(ArchiveTime.normalizeOrNull("昨天"))
    }

    @Test
    fun exportFileName() {
        assertEquals(
            "deepseek_data-2026-03-04.zip",
            suggestedExportFileName(ZonedDateTime.of(2026, 3, 4, 23, 0, 0, 0, ZoneOffset.ofHours(8))),
        )
    }

    @Test
    fun linearSession() {
        val result = local(
            Fx.session(
                "s1",
                null,
                2,
                listOf(Fx.user(1, null, "问"), Fx.assistant(2, 1, Fx.think("想", 3.5), Fx.response("答"))),
            ),
        )
        val session = result.session
        assertEquals("", session.title)
        assertEquals("1", session.rootMessageId)
        val user = session.byId("1") as Message.User
        assertEquals("问", user.content)
        assertEquals("2", user.selectedChildId)
        val reply = session.byId("2") as Message.Assistant
        assertEquals("1", reply.parentId)
        assertNull(reply.selectedChildId)
        assertFalse(reply.prefix)
        assertNull(reply.model)
        assertEquals(ReplyStatus.Done, reply.status)
        assertEquals(listOf(ReplyBlock.Thinking("想", elapsedSeconds = 3.5), ReplyBlock.Text("答")), reply.blocks)
        assertEquals(ArchiveTime.parseMillis(Fx.at(2)), reply.createdAt)
    }

    @Test
    fun branchesFollowCurrentMessageAndDefaultToLargestChild() {
        // 1 → (2, 4)；2 → 3；4 → (5, 7)；5 → 6；current = 3
        val messages = listOf(
            Fx.user(1, null, "q1"),
            Fx.assistant(2, 1, Fx.response("a1")),
            Fx.user(3, 2, "q2"),
            Fx.assistant(4, 1, Fx.response("a1'")),
            Fx.user(5, 4, "q2'"),
            Fx.assistant(6, 5, Fx.response("a2'")),
            Fx.user(7, 4, "q2''"),
        )
        val current = local(Fx.session("s", "t", 3, messages)).session
        assertEquals("2", current.byId("1").selectedChildId)
        assertEquals("3", current.byId("2").selectedChildId)
        // 当前消息之后不展开
        assertNull(current.byId("3").selectedChildId)
        // 非当前分支默认选中 id 最大的子消息
        assertEquals("7", current.byId("4").selectedChildId)

        val other = local(Fx.session("s", "t", 6, messages)).session
        assertEquals("4", other.byId("1").selectedChildId)
        assertEquals("5", other.byId("4").selectedChildId)
        assertEquals("6", other.byId("5").selectedChildId)
        assertEquals("3", other.byId("2").selectedChildId)
    }

    @Test
    fun badTreesFail() {
        val cases = listOf(
            // 根是回答
            Fx.session("s", null, 1, listOf(Fx.assistant(1, 0, Fx.response("x")).let { strip(it, "parent_id") })),
            // 父消息缺失
            Fx.session("s", null, 2, listOf(Fx.user(1, null, "q"), Fx.assistant(2, 9, Fx.response("x")))),
            // 角色不交替
            Fx.session("s", null, 2, listOf(Fx.user(1, null, "q"), Fx.user(2, 1, "q"))),
            // 父 id 必须更小
            Fx.session("s", null, 2, listOf(Fx.assistant(1, 2, Fx.response("x")), Fx.user(2, null, "q"))),
            // 重复 id
            Fx.session("s", null, 1, listOf(Fx.user(1, null, "q"), Fx.user(1, null, "q"))),
            // current 不存在
            Fx.session("s", null, 5, listOf(Fx.user(1, null, "q"))),
        )
        for (case in cases) expectCode(HistoryErrorCodes.INVALID_BRANCHES) { local(case) }
        // 空会话合法
        assertNull(local(Fx.session("s", null, 0, emptyList())).session.rootMessageId)
    }

    /** 把字段设为 null */
    private fun strip(obj: JsonObject, key: String) = JsonObject(obj + (key to kotlinx.serialization.json.JsonNull))

    @Test
    fun statusMapping() {
        val expected = mapOf(
            "FINISHED" to ReplyStatus.Done,
            "CONTENT_FILTER" to ReplyStatus.Done,
            "FAILED" to ReplyStatus.Error,
            "CONTEXT_LENGTH_EXCEEDED" to ReplyStatus.Error,
            "TIMEOUT" to ReplyStatus.Error,
            "INCOMPLETE" to ReplyStatus.Stopped,
            "WIP" to ReplyStatus.Stopped,
        )
        for ((status, reply) in expected) {
            val session = local(
                Fx.session("s", null, 2, listOf(Fx.user(1, null, "q"), Fx.assistant(2, 1, Fx.response("a"), status = status))),
            ).session
            assertEquals(status, reply, (session.byId("2") as Message.Assistant).status)
        }
    }

    @Test
    fun refusalDropsResponseAndThinking() {
        val session = local(
            Fx.session(
                "s",
                null,
                2,
                listOf(
                    Fx.user(1, null, "q"),
                    Fx.assistant(2, 1, Fx.think("t", 1.0), Fx.response("bad"), Fx.template("拒绝"), status = "CONTENT_FILTER"),
                ),
            ),
        ).session
        val reply = session.byId("2") as Message.Assistant
        assertEquals(StopReason.Refusal, reply.stopReason)
        assertEquals(listOf(ReplyBlock.Text("拒绝", isTemplate = true)), reply.blocks)
    }

    @Test
    fun toolFragments() {
        val reply = local(
            Fx.session(
                "s",
                null,
                2,
                listOf(
                    Fx.user(1, null, "q"),
                    Fx.assistant(
                        2,
                        1,
                        Fx.toolSearch(
                            listOf("k1", "k2"),
                            listOf(
                                Fx.searchResult("https://a.example/x", "A", Fx.at(5), "https://a.example/i.png"),
                                Fx.searchResult("javascript:alert(1)", "bad"),
                                Fx.searchResult("https://b.example", "B", null, "data:image/png;base64,xx"),
                            ),
                        ),
                        Fx.toolOpen("https://a.example/x", "页面", publishedAt = Fx.at(6)),
                        Fx.toolOpen(null, null),
                        Fx.toolFind("词", status = "NO_RESULT"),
                        Fx.toolSearch(listOf("k3"), emptyList(), status = "WIP"),
                        Fx.toolSearch(listOf("k4"), emptyList(), status = "FAILED"),
                        Fx.tip("tip"),
                        Fx.response("答 [citation:1]"),
                    ),
                ),
            ),
        ).session.byId("2") as Message.Assistant
        val blocks = reply.blocks
        // 有打开 / 查找网页时，搜索归为边想边搜
        val search = blocks[0] as ReplyBlock.ServerToolUse
        assertEquals("history-2-0", search.id)
        assertEquals(ServerToolKind.DeepSearch, search.kind)
        assertEquals(ServerToolInput.Search("k1", listOf("k1", "k2")), search.input)
        val results = blocks[1] as ReplyBlock.WebSearchToolResult
        assertEquals(listOf("https://a.example/x", "https://b.example"), results.results.map { it.url })
        assertEquals("https://a.example/i.png", results.results[0].siteIcon)
        assertNull(results.results[1].siteIcon)
        assertEquals(Fx.at(5), results.results[0].pageAge)
        val open = blocks[2] as ReplyBlock.ServerToolUse
        assertEquals(ServerToolInput.OpenPage("https://a.example/x"), open.input)
        val page = blocks[3] as ReplyBlock.WebFetchToolResult
        assertEquals("页面", page.title)
        assertEquals(Fx.at(6), page.publishedAt)
        assertNull(page.errorCode)
        assertEquals("web_fetch_result", (page.raw!!.jsonObject["type"] as JsonPrimitive).content)
        // 没有链接的 TOOL_OPEN 不展示；查找无结果为失败
        val find = blocks[4] as ReplyBlock.ServerToolUse
        assertEquals(ServerToolInput.FindInPage("", "词"), find.input)
        assertEquals("unavailable", (blocks[5] as ReplyBlock.WebFetchToolResult).errorCode)
        // WIP 搜索只有调用，没有结果
        assertTrue(blocks[6] is ReplyBlock.ServerToolUse)
        assertTrue(blocks[7] is ReplyBlock.ServerToolUse)
        assertEquals("unavailable", (blocks[8] as ReplyBlock.WebSearchToolResult).errorCode)
        // TIP 丢弃
        assertEquals(ReplyBlock.Text("答 [citation:1]"), blocks[9])
        assertEquals(10, blocks.size)
    }

    @Test
    fun readLinkKeepsSignature() {
        val reply = local(
            Fx.session(
                "s",
                null,
                2,
                listOf(
                    Fx.user(1, null, "q"),
                    Fx.assistant(
                        2,
                        1,
                        Fx.readLink(
                            Fx.readLinkResult("https://r.example", "标题", "sig-a"),
                            Fx.readLinkResult(null, null, "sig-only"),
                            Fx.readLinkResult("ftp://x", null, " "),
                        ),
                    ),
                ),
            ),
        ).session.byId("2") as Message.Assistant
        assertEquals(4, reply.blocks.size)
        val use = reply.blocks[0] as ReplyBlock.ServerToolUse
        assertEquals("history-2-0-0", use.id)
        assertEquals("deepseek_read_url", use.name)
        assertEquals(ServerToolKind.ReadLink, use.kind)
        val first = reply.blocks[1] as ReplyBlock.WebFetchToolResult
        assertEquals("sig-a", first.signature)
        assertEquals("https://r.example", first.url)
        assertEquals("标题", first.title)
        assertEquals(ServerToolInput.ReadLink(""), (reply.blocks[2] as ReplyBlock.ServerToolUse).input)
        assertEquals("sig-only", (reply.blocks[3] as ReplyBlock.WebFetchToolResult).signature)
    }

    @Test
    fun schemaDropsBadFragmentsButRejectsBadContainers() {
        val badFragment = buildJsonObject { put("type", "UNKNOWN_KIND") }
        val badField = buildJsonObject {
            put("type", "THINK")
            put("content", "x")
        } // elapsed_secs 缺省（可空字段必须显式）
        val session = local(
            Fx.session(
                "s",
                null,
                2,
                listOf(Fx.user(1, null, "q"), Fx.assistant(2, 1, badFragment, badField, Fx.response("ok"))),
            ),
        ).session
        assertEquals(listOf(ReplyBlock.Text("ok")), (session.byId("2") as Message.Assistant).blocks)

        // 可选字段缺省
        val noStatus = Fx.toolSearch(listOf("q"), emptyList(), status = null)
        val reply = local(Fx.session("s", null, 2, listOf(Fx.user(1, null, "q"), Fx.assistant(2, 1, noStatus))))
            .session.byId("2") as Message.Assistant
        assertTrue(reply.blocks[1] is ReplyBlock.WebSearchToolResult)

        // 容器 / 元数据非法即失败
        val good = Fx.session("s", null, 1, listOf(Fx.user(1, null, "q")))
        val broken = listOf(
            JsonObject(good + ("messages" to JsonPrimitive("x"))),
            JsonObject(good + ("inserted_at" to JsonPrimitive("2026-11-11"))),
            JsonObject(good - "title"),
            JsonObject(good + ("id" to JsonPrimitive("../evil"))),
            JsonObject(good + ("current_message_id" to JsonPrimitive(-1))),
            JsonObject(good + ("current_message_id" to JsonPrimitive(1.5))),
            JsonObject(good + ("messages" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(emptyMap()))))),
        )
        for (item in broken) expectCode(HistoryErrorCodes.INCOMPLETE) { ArchiveSchema.readSessionFile(item) }
        // 整数允许写成 1.0
        ArchiveSchema.readSessionFile(JsonObject(good + ("current_message_id" to JsonPrimitive(1.0))))
    }

    @Test
    fun manifestVersions() {
        ArchiveSchema.checkManifest(Fx.manifest(3))
        expectCode(HistoryErrorCodes.NEWER_VERSION) { ArchiveSchema.checkManifest(Fx.manifest(4)) }
        expectCode(HistoryErrorCodes.INVALID_VERSION) { ArchiveSchema.checkManifest(Fx.manifest(2)) }
        expectCode(HistoryErrorCodes.INVALID_VERSION) { ArchiveSchema.checkManifest(Fx.manifest("3")) }
        expectCode(HistoryErrorCodes.INCOMPLETE) { ArchiveSchema.checkManifest(JsonPrimitive(3)) }
    }

    @Test
    fun imagesMustStayInSessionDir() {
        val outside = Fx.files(Fx.fileEntry("f1", "a.png", 3, true, "chat_sessions/other_files/f1_a.png"))
        expectCode(HistoryErrorCodes.INVALID_FILES) {
            local(Fx.session("s", null, 1, listOf(Fx.user(1, null, "q", outside))))
        }
        val clash = listOf(
            Fx.user(1, null, "q", Fx.files(Fx.fileEntry("f1", "a.png", 3, true, "chat_sessions/s_files/x.png"))),
            Fx.assistant(2, 1, Fx.response("a")),
            Fx.user(3, 2, "q", Fx.files(Fx.fileEntry("f2", "a.png", 3, true, "chat_sessions/s_files/x.png"))),
        )
        expectCode(HistoryErrorCodes.INVALID_FILES) { local(Fx.session("s", null, 3, clash)) }
        // 非图片不检查路径
        val doc = Fx.files(Fx.fileEntry("d1", "a.pdf", 3, false, "elsewhere"))
        val user = local(Fx.session("s", null, 1, listOf(Fx.user(1, null, "q", doc)))).session.byId("1") as Message.User
        assertEquals("a.pdf", user.files.single().name)
        assertFalse(user.files.single().isImage)
    }

    @Test
    fun exportWritesNoticesAndStripsCitations() {
        val toArchive = ToArchive(DefaultReplyNotice { true })
        val session = Session(
            id = "s",
            title = "",
            createdAt = 0,
            updatedAt = 1,
            rootMessageId = "u",
            messages = listOf(
                Message.User(id = "u", createdAt = 0, selectedChildId = "a", content = "q"),
                Message.Assistant(
                    id = "a",
                    createdAt = 1,
                    parentId = "u",
                    selectedChildId = "a2",
                    status = ReplyStatus.Error,
                    error = ReplyError(ReplyErrorKind.Http, "boom", status = 429),
                    blocks = listOf(ReplyBlock.Text("部分")),
                ),
                Message.Assistant(
                    id = "b",
                    createdAt = 2,
                    parentId = "u",
                    status = ReplyStatus.Done,
                    stopReason = StopReason.Refusal,
                ),
                Message.Assistant(
                    id = "c",
                    createdAt = 3,
                    parentId = "u",
                    status = ReplyStatus.Done,
                    blocks = listOf(
                        ReplyBlock.Thinking("t", startedAt = 1000, endedAt = 3500),
                        ReplyBlock.ServerToolUse("x", "deepseek_web_search", ServerToolInput.Search("q", listOf("q")), ServerToolKind.Search),
                        ReplyBlock.Text("答[citation:2]"),
                        ReplyBlock.Text("续"),
                    ),
                ),
            ),
        )
        val file = toArchive.toSessionFile(session) { null }
        assertNull(file.title)
        assertEquals(2L, file.currentMessageId)
        val byId = file.messages.associateBy { it.id }
        assertEquals(ChatMessageStatus.FAILED, byId[2]!!.status)
        assertEquals(
            listOf(Fragment.Response("部分"), Fragment.TemplateResponse("请求过于频繁，请稍后再试")),
            byId[2]!!.fragments,
        )
        assertEquals(ChatMessageStatus.CONTENT_FILTER, byId[3]!!.status)
        assertEquals(listOf(Fragment.TemplateResponse("你好，这个问题我暂时无法回答，让我们换个话题再聊聊吧。")), byId[3]!!.fragments)
        val fragments = byId[4]!!.fragments
        assertEquals(Fragment.Think("t", 2.5), fragments[0])
        assertEquals(ChatMessageStatus.FINISHED, byId[4]!!.status)
        assertEquals(SearchStatus.WIP, (fragments[1] as Fragment.ToolSearch).status)
        assertEquals(Fragment.Response("答续"), fragments[2])
        // 用户开关取首个回答
        assertEquals(listOf(Fragment.Request("q")), byId[1]!!.fragments)
    }
}
