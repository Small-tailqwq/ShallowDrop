package io.github.smalltailqwq.shallowdrop.data.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class ApiBalance(
    @SerialName("is_available") val available: Boolean,
    @SerialName("balance_infos") val balances: List<BalanceAmount>,
)

@Serializable
data class BalanceAmount(
    val currency: String,
    @SerialName("total_balance") val total: String,
    @SerialName("granted_balance") val granted: String,
    @SerialName("topped_up_balance") val toppedUp: String,
)

class BalanceApiException(val status: Int) : IOException("HTTP $status")

/** 官方余额请求沿用系统证书链与主机名校验，禁止跟随重定向携带 Key。 */
class DeepSeekBalanceApi(client: OkHttpClient = defaultOkHttpClient()) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun query(apiKey: String): ApiBalance = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.deepseek.com/user/balance")
            .header("Authorization", "Bearer $apiKey")
            .build()
        val call = client.newCall(request)
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val balance = response.use {
                            if (!it.isSuccessful) throw BalanceApiException(it.code)
                            json.decodeFromString<ApiBalance>(it.body.string())
                        }
                        if (cont.isActive) cont.resume(balance)
                    } catch (error: Exception) {
                        if (cont.isActive) cont.resumeWithException(error)
                    }
                }
            })
        }
    }
}
