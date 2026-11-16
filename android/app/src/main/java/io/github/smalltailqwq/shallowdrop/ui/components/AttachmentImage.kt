package io.github.smalltailqwq.shallowdrop.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 附件本地原件的查找（AttachmentStore.file）；预览与 Fake 环境下为空实现 */
val LocalAttachmentFile = staticCompositionLocalOf<(String) -> File?> { { null } }

/** 解码后的缩略图缓存，按「附件 ID + 目标边长」区分；原件不会改变，无需失效 */
private val thumbnails = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** 按目标边长降采样解码，避免把整张大图读进内存；解码失败返回 null */
private suspend fun decode(lookup: (String) -> File?, id: String, targetPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val key = "$id@$targetPx"
    thumbnails.get(key)?.let { return@withContext it }
    val file = lookup(id)?.takeIf { it.isFile } ?: return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@withContext null
    bitmap.asImageBitmap().also { thumbnails.put(key, it) }
}

/**
 * 消息里的图片缩略图（对照 MessageFile.tsx）：有本地原件时显示居中裁剪的方形缩略图，点按全屏查看；
 * 原件缺失或无法解码时交给 [fallback]（文件卡片）。
 */
@Composable
fun AttachmentThumbnail(file: FileAttachment, size: Dp, fallback: @Composable () -> Unit) {
    val lookup = LocalAttachmentFile.current
    val targetPx = with(LocalDensity.current) { size.roundToPx() }
    // null：加载中；Missing：没有可显示的原件
    val state by produceState<Any?>(initialValue = null, file.id, targetPx) {
        value = decode(lookup, file.id, targetPx) ?: Missing
    }
    var viewing by remember { mutableStateOf(false) }
    when (val image = state) {
        is ImageBitmap -> {
            Image(
                bitmap = image,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { viewing = true },
            )
            if (viewing) ImageViewer(file, lookup) { viewing = false }
        }
        Missing -> fallback()
        else -> Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(alpha = 0.15f)))
    }
}

private object Missing

/** 全屏查看原图（按屏幕尺寸降采样），点按任意位置关闭 */
@Composable
private fun ImageViewer(file: FileAttachment, lookup: (String) -> File?, onDismiss: () -> Unit) {
    val window = LocalWindowInfo.current.containerSize
    val targetPx = maxOf(window.width, window.height)
    val image by produceState<ImageBitmap?>(initialValue = null, file.id, targetPx) {
        value = decode(lookup, file.id, targetPx)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            image?.let {
                Image(bitmap = it, contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
