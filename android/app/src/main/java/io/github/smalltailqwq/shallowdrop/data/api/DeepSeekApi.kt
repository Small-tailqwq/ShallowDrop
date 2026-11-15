package io.github.smalltailqwq.shallowdrop.data.api

import io.github.smalltailqwq.shallowdrop.BuildConfig
import io.github.smalltailqwq.shallowdrop.core.contract.ChatApi
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.CreateMessageRequest
import io.github.smalltailqwq.shallowdrop.core.model.FileObject
import io.github.smalltailqwq.shallowdrop.core.model.FilePurpose
import io.github.smalltailqwq.shallowdrop.core.model.MessageStreamEvent
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/** 引用 Files API 上传的图片、调用 Anthropic 兼容 Files API 时必须携带 */
private const val FILES_BETA = "files-api-2025-04-14"

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val OCTET_STREAM = "application/octet-stream".toMediaType()

/**
 * 默认 OkHttp 客户端。流式回复中思考或搜索可能长时间没有正文，
 * 读超时放宽到 5 分钟（服务端有保活注释，正常不会触发）；不设整体 callTimeout，由取消协程中止。
 */
fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .build()

/**
 * DeepSeek API（Anthropic 兼容），移植自 src/services/api/client.ts。
 * 失败抛 ApiException（code 见 ApiErrorCodes）；取消协程会关闭底层 Call。
 *
 * @param apiOrigin 不带结尾斜杠，如 https://api.deepseek.com
 * @param json 用于协议结构的编解码；内部会在其基础上开启 ignoreUnknownKeys、coerceInputValues、encodeDefaults
 */
class DeepSeekApi(
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val apiOrigin: String = BuildConfig.API_ORIGIN,
    json: Json = Json,
) : ChatApi {
    private val json = Json(from = json) {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val events = MessageEventReader(this.json)

    /** Anthropic 兼容接口的前缀 */
    private val anthropicBaseUrl = "${apiOrigin.trimEnd('/')}/anthropic/v1"

    /** 用 GET /models 校验 API Key（不产生费用） */
    override suspend fun verifyApiKey(apiKey: String) {
        val request = Request.Builder()
            .url("${apiOrigin.trimEnd('/')}/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        send(request, ErrorScope.Default).close()
    }

    /**
     * POST /anthropic/v1/files 上传附件（不设过期时间，会话里的历史附件一直可用）。
     * purpose 为 null 时不传该字段（图片）；purpose 必须排在 file 之前。
     * content 每次调用都应返回新的流（OkHttp 重试时可能多次写出请求体）。
     */
    override suspend fun uploadFile(
        apiKey: String,
        name: String,
        mimeType: String,
        purpose: FilePurpose?,
        content: () -> InputStream,
    ): FileObject {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (purpose != null) form.addFormDataPart("purpose", purpose.value)
        form.addFormDataPart("file", name, StreamRequestBody(mimeType.toMediaTypeOrNull() ?: OCTET_STREAM, content))
        val request = Request.Builder()
            .url("$anthropicBaseUrl/files")
            .anthropicHeaders(apiKey, beta = true)
            .post(form.build())
            .build()
        val text = send(request, ErrorScope.Upload).use { response ->
            withContext(Dispatchers.IO) { readBody(response) }
        }
        return try {
            json.decodeFromString(FileObject.serializer(), text)
        } catch (e: SerializationException) {
            throw apiException(ReplyErrorKind.Protocol, e.message ?: text)
        } catch (e: IllegalArgumentException) {
            throw apiException(ReplyErrorKind.Protocol, e.message ?: text)
        }
    }

    /** DELETE /anthropic/v1/files/{file_id} */
    override suspend fun deleteFile(apiKey: String, fileId: String) {
        val url = "$anthropicBaseUrl/files".toHttpUrl().newBuilder().addPathSegment(fileId).build()
        val request = Request.Builder()
            .url(url)
            .anthropicHeaders(apiKey, beta = true)
            .delete()
            .build()
        send(request, ErrorScope.Default).close()
    }

    /**
     * POST /anthropic/v1/messages（stream: true），逐个产出流式事件，收到 message_stop 时结束。
     * ping、保活注释与无法解析的内容不产出；message_stop 之前断流抛 Interrupted。
     * 收集方取消时关闭 Call，阻塞中的读取随之中止。
     */
    override fun streamMessage(apiKey: String, request: CreateMessageRequest): Flow<MessageStreamEvent> {
        val body = request.toJson(json).toString().toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url("$anthropicBaseUrl/messages")
            .anthropicHeaders(apiKey, beta = request.referencesFiles())
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        return channelFlow {
            val call = client.newCall(httpRequest)
            val reader = launch(Dispatchers.IO) {
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    throw networkFailure(call, e)
                }
                response.use {
                    if (!response.isSuccessful) throw readErrorResponse(response, ReplyErrorKind.Http, response.message, ErrorScope.Messages)
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!contentType.contains("text/event-stream")) {
                        throw readErrorResponse(response, ReplyErrorKind.Protocol, "", ErrorScope.Messages)
                    }
                    var stopped = false
                    try {
                        readServerSentEvents(response.body.charStream()) { sse ->
                            val event = toStreamEvent(sse)
                            if (event != null) {
                                send(event)
                                if (event is MessageStreamEvent.MessageStop) stopped = true
                            }
                            !stopped
                        }
                    } catch (e: IOException) {
                        throw networkFailure(call, e)
                    }
                    // 服务端没有提供错误内容时保持为空，不推测断流原因
                    if (!stopped) throw apiException(ReplyErrorKind.Interrupted, "")
                }
            }
            try {
                reader.join()
            } finally {
                call.cancel()
            }
        }
    }

    /** 单个 SSE 事件 → 流式事件；错误帧抛 Stream，无法解析的跳过 */
    private fun toStreamEvent(sse: ServerSentEvent): MessageStreamEvent? {
        val payload: JsonElement? = try {
            json.parseToJsonElement(sse.data)
        } catch (_: SerializationException) {
            if (sse.event != "error") return null
            null
        }
        errorBodyMessage(payload)?.let { throw apiException(ReplyErrorKind.Stream, it) }
        if (sse.event == "error") throw apiException(ReplyErrorKind.Stream, sse.data)
        return events.read(payload)
    }

    /** 发出请求并检查状态码；非 2xx 时读取错误体抛 Http */
    private suspend fun send(request: Request, scope: ErrorScope): Response {
        val call = client.newCall(request)
        val response = call.await()
        if (!response.isSuccessful) {
            response.use {
                throw withContext(Dispatchers.IO) { readErrorResponse(response, ReplyErrorKind.Http, response.message, scope) }
            }
        }
        return response
    }

    /** 优先取错误体中的 error.message，非 JSON 响应体直接展示原文，空响应体用 fallback */
    private fun readErrorResponse(response: Response, kind: ReplyErrorKind, fallback: String, scope: ErrorScope): ApiException {
        val text = try {
            readBody(response)
        } catch (_: IOException) {
            ""
        }
        val parsed = try {
            errorBodyMessage(json.parseToJsonElement(text))
        } catch (_: SerializationException) {
            null
        }
        return apiException(kind, parsed ?: text.ifEmpty { fallback }, response.code, scope)
    }

    private fun readBody(response: Response): String = response.body.string()

    private fun Request.Builder.anthropicHeaders(apiKey: String, beta: Boolean): Request.Builder = apply {
        // DeepSeek 兼容接口的鉴权方式，也可使用 Authorization: Bearer
        header("x-api-key", apiKey)
        // Files API 和通过 file_id 引用图片时必传；纯文本聊天无需携带
        if (beta) header("anthropic-beta", FILES_BETA)
    }
}

/** { error: { message: string } } 形式的错误体，返回 message */
private fun errorBodyMessage(value: JsonElement?): String? {
    val error = (value as? JsonObject)?.get("error") as? JsonObject ?: return null
    val message = error["message"] as? JsonPrimitive ?: return null
    return message.takeIf { it.isString }?.content
}

/** 取消导致的 IOException 转为 CancellationException，其余为 Network */
private fun networkFailure(call: Call, e: IOException): Exception =
    if (call.isCanceled()) {
        CancellationException("Call canceled").apply { initCause(e) }
    } else {
        apiException(ReplyErrorKind.Network, e.message ?: e.toString())
    }

/** 异步执行 Call；协程取消时取消 Call，已到达的响应会被关闭 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWith(Result.failure(networkFailure(call, e)))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

/**
 * 从 content() 打开的流写出请求体。契约没有提供文件大小，先读一遍计数得到 Content-Length，
 * 避免分块传输（multipart 分块上传不是所有网关都接受）；计数结果缓存。
 */
private class StreamRequestBody(
    private val mediaType: MediaType,
    private val content: () -> InputStream,
) : RequestBody() {
    private val length by lazy {
        content().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                total += n
            }
            total
        }
    }

    override fun contentType() = mediaType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        content().source().use { sink.writeAll(it) }
    }
}
