package io.github.smalltailqwq.shallowdrop.data.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DeepSeekBalanceApiTest {
    @Test fun queriesOfficialOriginAndPreservesDecimalBalances() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://api.deepseek.com/user/balance", chain.request().url.toString())
            assertEquals("Bearer sk-test", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"10.123456","granted_balance":"0.000001","topped_up_balance":"10.123455"}]}""".toResponseBody()).build()
        }.build()
        val balance = DeepSeekBalanceApi(client).query("sk-test")
        assertTrue(balance.available)
        assertEquals("10.123456", balance.balances.single().total)
    }

    @Test fun redirectsAreRejectedWithoutForwardingCredentials() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/other")).build())
            server.enqueue(MockResponse.Builder().code(401).build())
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/user/balance")).build())
            }.build()
            try {
                DeepSeekBalanceApi(client).query("sk-test")
                fail("Expected redirect refusal")
            } catch (error: BalanceApiException) {
                assertEquals(302, error.status)
            }
            assertEquals(1, server.requestCount)
        }
    }
}
