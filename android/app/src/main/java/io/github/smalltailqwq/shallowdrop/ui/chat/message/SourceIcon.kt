package io.github.smalltailqwq.shallowdrop.ui.chat.message

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

// 网页图标不使用带 API 凭据的客户端，且只缓存小尺寸的解码结果。
private val iconClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
private val icons = object : LruCache<String, ImageBitmap>(1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}
private const val MAX_ICON_BYTES = 1024L * 1024

internal suspend fun loadSourceIcon(url: String, targetPx: Int, client: OkHttpClient = iconClient): ImageBitmap? = coroutineScope {
    val address = url.toHttpUrlOrNull() ?: return@coroutineScope null
    val key = "$address@$targetPx"
    icons.get(key)?.let { return@coroutineScope it }
    val call = client.newCall(Request.Builder().url(address).build())
    val guard = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) {
            call.execute().use { response ->
                if (!response.isSuccessful || response.body.contentLength() > MAX_ICON_BYTES) return@withContext null
                val source = response.body.source()
                source.request(MAX_ICON_BYTES + 1)
                if (source.buffer.size > MAX_ICON_BYTES) return@withContext null
                val bytes = source.readByteArray()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > targetPx * 2) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()?.also { icons.put(key, it) }
            }
        }
    } catch (_: IOException) {
        ensureActive()
        null
    } finally {
        guard.cancel()
    }
}

@Composable
internal fun SourceIcon(url: String?) {
    val targetPx = with(LocalDensity.current) { 20.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, url, targetPx) {
        value = null
        if (url != null) value = loadSourceIcon(url, targetPx)
    }
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(2.dp))
        } else {
            DsIcon(R.drawable.ic_globe_language_outline_14, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}
