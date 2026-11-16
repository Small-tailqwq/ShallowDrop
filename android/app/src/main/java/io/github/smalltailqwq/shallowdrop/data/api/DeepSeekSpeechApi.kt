package io.github.smalltailqwq.shallowdrop.data.api

import io.github.smalltailqwq.shallowdrop.BuildConfig
import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** 语音接口返回非 2xx（status 为 HTTP 状态码）或响应不合协议（status 为 null） */
class SpeechApiException(val status: Int?, message: String) : IOException(message)

/**
 * DeepSeek 语音接口，移植自 src/services/api/speech.ts：
 * - ASR：POST /v1/tools/asr，multipart 上传 WAV（单声道 16-bit PCM）或 Ogg Opus，一次返回全文；
 *   时长不超过 120 秒、大小不超过 8 MiB。不支持流式识别，客户端需自行判断一句话何时结束。
 * - TTS：POST /v1/tools/tts，返回 24kHz、单声道、16-bit 小端的裸 PCM 字节流（产出首帧后即返回 200）；
 *   filter_markdown 让服务端过滤代码块、表格、引用标记与长公式。
 * 鉴权都用 Authorization: Bearer。取消协程即中止请求。
 */
class DeepSeekSpeechApi(
    private val client: OkHttpClient,
    apiOrigin: String = BuildConfig.API_ORIGIN,
) {
    private val toolsBaseUrl = "${apiOrigin.trimEnd('/')}/v1/tools"

    /** 识别一段 WAV，返回全文（可能为空串） */
    suspend fun transcribe(apiKey: String, wav: ByteArray): String {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "recording.wav", wav.toRequestBody(WAV))
            .build()
        val request = Request.Builder()
            .url("$toolsBaseUrl/asr")
            .header("Authorization", "Bearer $apiKey")
            .post(form)
            .build()
        val raw = send(request).use { response -> withContext(Dispatchers.IO) { response.body.string() } }
        val text = runCatching { Json.parseToJsonElement(raw).jsonObject["text"] as? JsonPrimitive }.getOrNull()
        if (text == null || !text.isString) throw SpeechApiException(null, raw)
        return text.contentOrNull.orEmpty()
    }

    /** 开始合成，返回已收到首帧的响应；调用方读取 body 的 PCM 流并负责关闭 */
    suspend fun synthesize(apiKey: String, text: String, voice: TtsVoice): Response {
        val body = buildJsonObject {
            put("text", text)
            put("voice_id", voice.id)
            put("format", "pcm")
            put("filter_markdown", true)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("$toolsBaseUrl/tts")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        return send(request)
    }

    private suspend fun send(request: Request): Response {
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            val message = response.use { runCatching { it.body.string() }.getOrDefault("") }
            throw SpeechApiException(response.code, message.ifEmpty { "HTTP ${response.code}" })
        }
        return response
    }

    companion object {
        /** TTS 输出采样率 */
        const val TTS_SAMPLE_RATE = 24_000

        /** ASR 接受的 WAV 采样率（与 Web 录音一致） */
        const val ASR_SAMPLE_RATE = 16_000

        private val WAV = "audio/wav".toMediaType()
        private val JSON = "application/json".toMediaType()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        },
    )
}
