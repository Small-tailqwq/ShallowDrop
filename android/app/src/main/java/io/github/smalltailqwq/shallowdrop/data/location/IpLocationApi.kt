package io.github.smalltailqwq.shallowdrop.data.location

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** IP 定位只发送查询请求，不携带 DeepSeek 凭据或聊天内容。 */
class IpLocationApi(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun locate(locale: Locale): String {
        val language = if (locale.language == "zh") "zh-CN" else "en"
        val call = client.newCall(Request.Builder()
            .url("https://ipwho.is/?lang=$language&fields=success,country,region,city")
            .build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val place = response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            val data = json.decodeFromString<IpPlace>(it.body.string())
                            if (!data.success) throw IOException("IP location unavailable")
                            listOf(data.country, data.region, data.city).filter { name -> name.isNotBlank() }.distinct().joinToString(" ")
                                .ifBlank { throw IOException("IP location unavailable") }
                        }
                        if (continuation.isActive) continuation.resume(place)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    @Serializable
    private data class IpPlace(val success: Boolean, val country: String = "", val region: String = "", val city: String = "")
}
