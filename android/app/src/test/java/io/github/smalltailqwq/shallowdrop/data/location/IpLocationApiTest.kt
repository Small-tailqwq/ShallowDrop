package io.github.smalltailqwq.shallowdrop.data.location

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class IpLocationApiTest {
    @Test fun resolvesOutgoingIpWithoutSendingCredentialsAndRejectsRedirects() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"success":true,"country":"中国","region":"浙江省","city":"杭州市"}""").build())
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/other")).build())
            val api = IpLocationApi(OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("ipwho.is", chain.request().url.host)
                assertTrue(chain.request().url.isHttps)
                val url = chain.request().url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }.build())
            assertEquals("中国 浙江省 杭州市", api.locate(Locale.CHINA))
            val request = server.takeRequest()
            assertNull(request.headers["Authorization"])
            assertTrue(request.url.toString().contains("lang=zh-CN"))
            assertEquals(0L, request.bodySize)
            try { api.locate(Locale.US); fail("Expected redirect rejection") } catch (_: IOException) { }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun failedOrEmptyLookupsAreNotLocations() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"success":false,"message":"limited"}""").build())
            server.enqueue(MockResponse.Builder().body("""{"success":true}""").build())
            val api = IpLocationApi(OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
            }.build())
            repeat(2) {
                try { api.locate(Locale.US); fail("Expected unavailable location") } catch (_: IOException) { }
            }
        }
    }
}
