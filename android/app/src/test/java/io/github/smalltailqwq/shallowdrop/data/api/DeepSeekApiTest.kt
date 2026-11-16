package io.github.smalltailqwq.shallowdrop.data.api

import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ContentBlockDelta
import io.github.smalltailqwq.shallowdrop.core.model.CreateMessageRequest
import io.github.smalltailqwq.shallowdrop.core.model.FilePurpose
import io.github.smalltailqwq.shallowdrop.core.model.FileSource
import io.github.smalltailqwq.shallowdrop.core.model.MessageParam
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.core.model.RequestTool
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.StopReason
import io.github.smalltailqwq.shallowdrop.core.model.UserContentParam
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DeepSeekApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DeepSeekApi
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = DeepSeekApi(client, server.url("/").toString().trimEnd('/'), Json)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun crossOriginRedirectDoesNotReceiveApiKey() = runBlocking {
        MockWebServer().use { target ->
            target.start()
            target.enqueue(MockResponse.Builder().code(204).build())
            val destination = target.url("/capture").newBuilder().host("127.0.0.1").build()
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", destination).build())
            val error = expectApiError { api.deleteFile("sk-local-redirect-test", "file-test") }
            assertEquals(ReplyErrorKind.Http, error.kind)
            assertEquals(302, error.status)
            assertFalse(error.message.contains("sk-local-redirect-test"))
            assertEquals("sk-local-redirect-test", server.takeRequest().headers["x-api-key"])
            assertNull(target.takeRequest(200, TimeUnit.MILLISECONDS))
        }
    }

    private fun sse(body: String, chunk: Int? = null): MockResponse {
        val builder = MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream; charset=utf-8")
        return (if (chunk == null) builder.body(body) else builder.chunkedBody(body, chunk)).build()
    }

    private fun frame(event: String, data: String) = "event: $event\ndata: $data\n\n"

    private val simpleRequest = CreateMessageRequest(
        model = ChatModel.Flash,
        system = "sys",
        messages = listOf(MessageParam.User(listOf(UserContentParam.Text("hi")))),
        thinking = false,
        tools = emptyList(),
    )

    private suspend fun collect(request: CreateMessageRequest = simpleRequest) =
        withTimeout(10_000) { api.streamMessage("sk-test", request).toList() }

    @Test fun contextLimitIsRecognizedForHttpAndStreamErrors() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"This model's maximum context length is 1048576 tokens. However, you requested 1048600 tokens."}}""").build())
        assertEquals(ApiErrorCodes.CONTEXT_FULL, expectApiError { collect() }.code)
        server.enqueue(sse(frame("error", """{"type":"error","error":{"message":"context_length_exceeded"}}""")))
        assertEquals(ApiErrorCodes.CONTEXT_FULL, expectApiError { collect() }.code)
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"max_tokens must be positive"}}""").build())
        assertEquals(ApiErrorCodes.OTHER, expectApiError { collect() }.code)
    }

    private inline fun expectApiError(block: () -> Unit): ApiException {
        try {
            block()
        } catch (e: ApiException) {
            return e
        }
        fail("expected ApiException")
        throw AssertionError()
    }

    private val fullStream = buildString {
        append(frame("message_start", """{"type":"message_start","message":{"id":"uuid-1","model":"deepseek-flash","usage":{"input_tokens":5,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0}}}"""))
        append(frame("ping", """{"type":"ping"}"""))
        append(": keep-alive\n\n")
        append(frame("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}"""))
        append(frame("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"想一想"}}"""))
        append(frame("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"uuid-1"}}"""))
        append(frame("content_block_stop", """{"type":"content_block_stop","index":0}"""))
        append(frame("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"srvtoolu_s_1_ab","name":"deepseek_web_search","input":{"action":"search","queries":["天气"]},"caller":{"type":"direct"},"kind":"search"}}"""))
        append(frame("content_block_stop", """{"type":"content_block_stop","index":1}"""))
        append(frame("content_block_start", """{"type":"content_block_start","index":2,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_s_1_ab","kind":"search","content":[{"type":"web_search_result","title":"T","url":"https://a.example","snippet":null,"site_icon":null,"site_name":"A","encrypted_content":"x","page_age":null,"extra":1},{"type":"web_search_result","url":"https://bad.example"}]}}"""))
        append(frame("content_block_stop", """{"type":"content_block_stop","index":2}"""))
        append(frame("content_block_start", """{"type":"content_block_start","index":3,"content_block":{"type":"web_fetch_tool_result","tool_use_id":"srvtoolu_rl_2_cd","kind":"read_link","signature":"enc","content":{"type":"web_fetch_result","url":"https://b.example","content":{"type":"document","source":{"type":"text","media_type":"text/plain","data":""},"title":"B"},"published_at":"2024-01-01"}}}"""))
        append(frame("content_block_stop", """{"type":"content_block_stop","index":3}"""))
        append(frame("content_block_start", """{"type":"content_block_start","index":4,"content_block":{"type":"text","text":""}}"""))
        append(frame("content_block_delta", """{"type":"content_block_delta","index":4,"delta":{"type":"text_delta","text":"晴"}}"""))
        append(frame("content_block_delta", """{"type":"content_block_delta","index":4,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://a.example","title":null,"cited_text":"晴","encrypted_index":""}}}"""))
        append(frame("content_block_start", """{"type":"content_block_start","index":5,"content_block":{"type":"unknown_block"}}"""))
        append(frame("content_block_start", """{"type":"content_block_start","index":5,"content_block":{"type":"tool_use","id":"t","name":"x","input":{}}}"""))
        append(frame("content_block_stop", """{"type":"content_block_stop","index":4}"""))
        append(frame("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null,"title":"天气"},"usage":{"input_tokens":5,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":9}}"""))
        append(frame("message_stop", """{"type":"message_stop"}"""))
        // message_stop 之后的内容不再产出
        append(frame("message_stop", """{"type":"message_stop"}"""))
    }

    @Test
    fun fullStreamParsed() = runBlocking {
        server.enqueue(sse(fullStream))
        val events = collect()

        val start = events[0] as MessageStreamEvent.MessageStart
        assertEquals("uuid-1", start.id)
        assertEquals(5L, start.usage?.inputTokens)

        val thinking = events[1] as MessageStreamEvent.ContentBlockStart
        assertEquals(ReplyBlock.Thinking("", signature = ""), thinking.block)
        assertEquals(ContentBlockDelta.ThinkingDelta("想一想"), (events[2] as MessageStreamEvent.ContentBlockDeltaEvent).delta)
        assertEquals(ContentBlockDelta.SignatureDelta("uuid-1"), (events[3] as MessageStreamEvent.ContentBlockDeltaEvent).delta)
        assertEquals(MessageStreamEvent.ContentBlockStop(0), events[4])

        val tool = (events[5] as MessageStreamEvent.ContentBlockStart).block as ReplyBlock.ServerToolUse
        assertEquals("srvtoolu_s_1_ab", tool.id)
        assertEquals(ServerToolInput.Search(queries = listOf("天气")), tool.input)
        assertEquals(ServerToolKind.Search, tool.kind)

        val search = (events[7] as MessageStreamEvent.ContentBlockStart).block as ReplyBlock.WebSearchToolResult
        assertEquals(1, search.results.size)
        assertEquals("", search.results[0].snippet)
        assertEquals("A", search.results[0].siteName)
        assertNull(search.errorCode)

        val fetch = (events[9] as MessageStreamEvent.ContentBlockStart).block as ReplyBlock.WebFetchToolResult
        assertEquals("https://b.example", fetch.url)
        assertEquals("B", fetch.title)
        assertEquals("2024-01-01", fetch.publishedAt)
        assertEquals("enc", fetch.signature)
        assertEquals(ServerToolKind.ReadLink, fetch.kind)
        assertEquals("web_fetch_result", fetch.raw!!.jsonObject["type"]!!.jsonPrimitive.content)

        assertEquals(ReplyBlock.Text(""), (events[11] as MessageStreamEvent.ContentBlockStart).block)
        assertEquals(ContentBlockDelta.TextDelta("晴"), (events[12] as MessageStreamEvent.ContentBlockDeltaEvent).delta)
        val citation = (events[13] as MessageStreamEvent.ContentBlockDeltaEvent).delta as ContentBlockDelta.CitationsDelta
        assertEquals("https://a.example", citation.citation.url)
        assertNull(citation.citation.title)

        // 未知块丢弃；客户端 tool_use 产出 block = null
        val toolUse = events[14] as MessageStreamEvent.ContentBlockStart
        assertNull(toolUse.block)
        assertEquals("tool_use", toolUse.raw.jsonObject["type"]!!.jsonPrimitive.content)

        assertEquals(MessageStreamEvent.ContentBlockStop(4), events[15])
        val delta = events[16] as MessageStreamEvent.MessageDelta
        assertEquals(StopReason.EndTurn, delta.stopReason)
        assertEquals("天气", delta.title)
        assertEquals(9L, delta.usage?.outputTokens)
        assertEquals(MessageStreamEvent.MessageStop, events[17])
        assertEquals(18, events.size)
    }

    @Test
    fun chunkedHalfLinesParsed() = runBlocking {
        // 7 字节一块：多字节 UTF-8 字符与行都会被切开
        server.enqueue(sse(fullStream, chunk = 7))
        val chunked = collect()
        server.enqueue(sse(fullStream))
        assertEquals(collect(), chunked)
    }

    @Test
    fun crlfAndKeepAliveOnly() = runBlocking {
        val body = ": keep-alive\r\n\r\n" +
            "event: message_start\r\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"i\",\"model\":\"m\"}}\r\n\r\n" +
            ": keep-alive\r\n\r\n" +
            "data: {\"type\":\"message_stop\"}\r\n\r\n"
        server.enqueue(sse(body))
        val events = collect()
        assertEquals(listOf(MessageStreamEvent.MessageStart("i", "m", null), MessageStreamEvent.MessageStop), events)
    }

    @Test
    fun namedErrorFrame() = runBlocking {
        server.enqueue(sse(frame("message_start", """{"type":"message_start","message":{"id":"i","model":"m"}}""") + "event: error\ndata: generation timeout\n\n"))
        val error = expectApiError { collect() }
        assertEquals(ReplyErrorKind.Stream, error.kind)
        assertEquals("generation timeout", error.message)
        assertEquals(ApiErrorCodes.OTHER, error.code)
    }

    @Test
    fun unnamedErrorBodyFrame() = runBlocking {
        server.enqueue(sse("""data: {"error":{"message":"busy","type":"overloaded"}}""" + "\n\n"))
        val error = expectApiError { collect() }
        assertEquals(ReplyErrorKind.Stream, error.kind)
        assertEquals("busy", error.message)
    }

    @Test
    fun endBeforeMessageStopIsInterrupted() = runBlocking {
        server.enqueue(sse(frame("message_start", """{"type":"message_start","message":{"id":"i","model":"m"}}""") + "data: {\"type\":\"cont"))
        val error = expectApiError { collect() }
        assertEquals(ReplyErrorKind.Interrupted, error.kind)
        assertEquals("", error.message)
    }

    @Test
    fun http401ErrorBody() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(401)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":{"message":"Authentication Fails","type":"authentication_error"}}""")
                .build(),
        )
        val error = expectApiError { collect() }
        assertEquals(ReplyErrorKind.Http, error.kind)
        assertEquals(401, error.status)
        assertEquals("Authentication Fails", error.message)
        assertEquals(ApiErrorCodes.UNAUTHORIZED, error.code)
    }

    @Test
    fun http400FileErrorCode() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(400)
                .body("""{"error":{"message":"messages.0.content.0.source.file_id: invalid file_id file-api-x1"}}""")
                .build(),
        )
        val error = expectApiError { collect() }
        assertEquals(ApiErrorCodes.INVALID_FILE_ID, error.code)
        assertEquals(listOf("file-api-x1"), extractFileIds(error.message))
        assertFalse(isRetryableApiError(error))
    }

    @Test
    fun nonSseResponseIsProtocol() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body("<html>").build())
        val error = expectApiError { collect() }
        assertEquals(ReplyErrorKind.Protocol, error.kind)
        assertEquals("<html>", error.message)
        assertEquals(200, error.status)
    }

    @Test
    fun networkFailure() = runBlocking {
        val dead = MockWebServer().apply { start() }
        val origin = dead.url("/").toString().trimEnd('/')
        dead.close()
        val error = expectApiError { DeepSeekApi(client, origin).verifyApiKey("sk") }
        assertEquals(ReplyErrorKind.Network, error.kind)
        assertEquals(ApiErrorCodes.NETWORK, error.code)
        assertTrue(isRetryableApiError(error))
    }

    @Test
    fun cancelClosesCall() = runBlocking {
        val first = frame("message_start", """{"type":"message_start","message":{"id":"i","model":"m"}}""")
        // 首个事件之后极慢地下发，取消后不应等待剩余内容
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream")
                .body(first + ": " + "x".repeat(8000) + "\n\n")
                .throttleBody(1024, 30, TimeUnit.SECONDS)
                .build(),
        )
        val started = System.nanoTime()
        val event = withTimeout(5_000) { api.streamMessage("sk", simpleRequest).first() }
        assertEquals(MessageStreamEvent.MessageStart("i", "m", null), event)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test
    fun requestBodySnapshotAndHeaders() = runBlocking {
        server.enqueue(sse(frame("message_stop", """{"type":"message_stop"}""")))
        val request = CreateMessageRequest(
            model = ChatModel.V4Pro,
            system = "你是助手",
            messages = listOf(
                MessageParam.LatestReminder("今天是 2026-11-15"),
                MessageParam.User(listOf(UserContentParam.Text("你好"))),
                MessageParam.Assistant(
                    listOf(Json.parseToJsonElement("""{"type":"text","text":"嗨"}""") as JsonObject),
                ),
                MessageParam.User(
                    listOf(
                        UserContentParam.Image(FileSource(fileId = "file-api-img")),
                        UserContentParam.Document(FileSource(fileId = "file-api-doc")),
                        UserContentParam.Text("看看"),
                    ),
                ),
            ),
            thinking = true,
            tools = listOf(RequestTool.ReadUrl, RequestTool.WebSearch),
            title = true,
        )
        collect(request)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/anthropic/v1/messages", recorded.url.encodedPath)
        assertEquals("sk-test", recorded.headers["x-api-key"])
        assertEquals("files-api-2025-04-14", recorded.headers["anthropic-beta"])
        assertEquals("text/event-stream", recorded.headers["Accept"])
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))
        val expected = """
            {"model":"deepseek-v4-pro","system":"你是助手","messages":[
            {"role":"latest_reminder","content":"今天是 2026-11-15"},
            {"role":"user","content":"你好"},
            {"role":"assistant","content":[{"type":"text","text":"嗨"}]},
            {"role":"user","content":[
            {"type":"image","source":{"type":"file","file_id":"file-api-img"}},
            {"type":"document","source":{"type":"file","file_id":"file-api-doc"}},
            {"type":"text","text":"看看"}]}],
            "thinking":{"type":"enabled"},"safety_check":{"level":"high"},
            "tools":[{"type":"deepseek_read_url","name":"deepseek_read_url"},{"type":"deepseek_web_search","name":"deepseek_web_search"}],
            "title":{"enabled":true},"stream":true}
        """.trimIndent().replace("\n", "")
        assertEquals(expected, recorded.body!!.utf8())
    }

    @Test
    fun summaryRequestSpecifiesOutputLimit() = runBlocking {
        server.enqueue(sse(frame("message_stop", """{"type":"message_stop"}""")))
        collect(simpleRequest.copy(maxTokens = 8192))
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("8192", body["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun plainRequestOmitsBetaAndTitle() = runBlocking {
        server.enqueue(sse(frame("message_stop", """{"type":"message_stop"}""")))
        collect()
        val recorded = server.takeRequest()
        assertNull(recorded.headers["anthropic-beta"])
        assertEquals(
            """{"model":"deepseek-flash","system":"sys","messages":[{"role":"user","content":"hi"}],"thinking":{"type":"disabled"},"safety_check":{"level":"high"},"tools":[],"stream":true}""",
            recorded.body!!.utf8(),
        )
    }

    @Test
    fun verifyApiKeyUsesBearer() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"object":"list","data":[]}""").build())
        api.verifyApiKey("sk-1")
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/models", recorded.url.encodedPath)
        assertEquals("Bearer sk-1", recorded.headers["Authorization"])
        assertNull(recorded.headers["x-api-key"])
    }

    @Test
    fun uploadMultipartPurposeBeforeFile() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"id":"file-api-1","type":"file","size_bytes":3,"created_at":"2026-01-01T00:00:00Z","filename":"a.pdf","mime_type":"application/pdf","extra":true}""")
                .build(),
        )
        val file = api.uploadFile("sk", "a.pdf", "application/pdf", FilePurpose.RichLayoutRecognition) { "abc".byteInputStream() }
        assertEquals("file-api-1", file.id)
        assertEquals(3L, file.sizeBytes)
        val recorded = server.takeRequest()
        assertEquals("/anthropic/v1/files", recorded.url.encodedPath)
        assertEquals("sk", recorded.headers["x-api-key"])
        assertEquals("files-api-2025-04-14", recorded.headers["anthropic-beta"])
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        assertTrue(recorded.bodySize > 0 && recorded.headers["Content-Length"] != null)
        val body = recorded.body!!.utf8()
        val purpose = body.indexOf("name=\"purpose\"")
        val filePart = body.indexOf("name=\"file\"; filename=\"a.pdf\"")
        assertTrue(purpose in 0 until filePart)
        assertTrue(body.contains("rich_layout_recognition"))
        assertTrue(body.contains("Content-Type: application/pdf"))
        assertTrue(body.contains("\r\n\r\nabc\r\n"))
    }

    @Test
    fun uploadImageOmitsPurpose() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"file-api-2"}""").build())
        api.uploadFile("sk", "a.png", "image/png", null) { byteArrayOf(1, 2).inputStream() }
        val body = server.takeRequest().body!!.utf8()
        assertFalse(body.contains("name=\"purpose\""))
        assertTrue(body.contains("name=\"file\"; filename=\"a.png\""))
    }

    @Test
    fun uploadQuotaErrorInfo() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(400)
                .body("""{"error":{"message":"User file count limit exceeded: current 100, limit 100"}}""")
                .build(),
        )
        val error = expectApiError { api.uploadFile("sk", "a.txt", "text/plain", FilePurpose.UserData) { "x".byteInputStream() } }
        assertEquals(ApiErrorCodes.UPLOAD_FILE_COUNT_LIMIT, error.code)
        assertEquals(UploadErrorInfo(ApiErrorCodes.UPLOAD_FILE_COUNT_LIMIT, "100"), uploadErrorInfo(error))
    }

    @Test
    fun deleteFileEncodesId() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        api.deleteFile("sk", "file-api/1")
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/anthropic/v1/files/file-api%2F1", recorded.url.encodedPath)
        assertEquals("files-api-2025-04-14", recorded.headers["anthropic-beta"])
    }

    @Test
    fun classifyStatusMatchesWeb() {
        assertEquals(ApiErrorCodes.UNAUTHORIZED, classifyStatus(401))
        assertEquals(ApiErrorCodes.INSUFFICIENT_BALANCE, classifyStatus(402))
        assertEquals(ApiErrorCodes.TOO_LARGE, classifyStatus(413))
        assertEquals(ApiErrorCodes.RATE_LIMITED, classifyStatus(429))
        assertEquals(ApiErrorCodes.SERVER_BUSY, classifyStatus(503))
        assertEquals(ApiErrorCodes.OTHER, classifyStatus(400))
        assertEquals(ApiErrorCodes.OTHER, classifyStatus(null))
    }
}
