package io.github.smalltailqwq.shallowdrop.ui.chat.message

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SourceIconTest {
    @Test fun leavingThePanelCancelsAnIconWhileItsBodyIsLoading() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("delayed image").throttleBody(1, 10, TimeUnit.SECONDS).build())
            server.start()
            val reading = CompletableDeferred<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { reading.complete(call) }
            }).build()
            val job = launch { loadSourceIcon(server.url("/slow").toString(), 20, client) }
            val call = withTimeout(2000) { reading.await() }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    @Test fun fetchesDownsampledIconAndReusesItWithoutCredentials() = runBlocking {
        MockWebServer().use { server ->
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            server.start()
            val url = server.url("/icon.png").toString()
            val icon = loadSourceIcon(url, 20)
            assertNotNull(icon)
            assertTrue(icon!!.width <= 40 && icon.height <= 40)
            assertEquals(icon, loadSourceIcon(url, 20))
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Cookie"])
        }
    }

    @Test fun missingInvalidAndOversizedIconsLeaveThePlaceholderAvailable() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(MockResponse.Builder().body("not an image").build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(1024 * 1024 + 1))).build())
            server.start()
            val client = OkHttpClient()
            for (path in listOf("/missing", "/invalid", "/oversized")) {
                assertNull(loadSourceIcon(server.url(path).toString(), 20, client))
            }
            assertNull(loadSourceIcon("file:///private/icon", 20, client))
            assertEquals(3, server.requestCount)
        }
    }
}
