package io.github.smalltailqwq.shallowdrop.ui.chat

import io.github.smalltailqwq.shallowdrop.ui.motion.pressFeedback
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.annotation.DrawableRes
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.chat.message.formatSize
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import io.github.smalltailqwq.shallowdrop.ui.components.FileIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 可上传的非图片类型；具体扩展名校验由 AttachmentStore / domain 完成（对照 uploadExts.ts） */
private val DOCUMENT_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/*",
    "application/json",
    "application/xml",
    "application/octet-stream",
)

/** 输入栏：草稿附件、多行输入、深度思考 / 智能搜索胶囊、附件、发送 / 停止。对照 ChatInput.tsx。 */
@Composable
fun ChatInputBar(
    state: ChatUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleThinking: () -> Unit,
    onToggleSearch: () -> Unit,
    onAddAttachments: (List<Uri>, Boolean) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var attachMenu by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) onAddAttachments(uris, true)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAddAttachments(uris, false)
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            if (state.draft.attachments.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    items(state.draft.attachments, key = { it.key }) { item ->
                        DraftAttachmentCard(item, onRemove = { onRemoveAttachment(item.key) }, onRetry = { onRetryAttachment(item.key) })
                    }
                }
            }
            Box(Modifier.padding(horizontal = 16.dp).heightIn(min = 36.dp)) {
                val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                if (state.draft.text.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_input_placeholder),
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                BasicTextField(
                    value = state.draft.text,
                    onValueChange = onTextChange,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TogglePill(R.drawable.ic_think_outline_14, stringResource(R.string.thinking_switch), state.settings.thinking, onToggleThinking)
                    TogglePill(R.drawable.ic_globe_language_outline_14, stringResource(R.string.search_switch), state.settings.search, onToggleSearch)
                }
                Box {
                    IconButton(onClick = { attachMenu = true }, modifier = Modifier.size(40.dp)) {
                        DsIcon(R.drawable.ic_paperclip_outline_16, contentDescription = stringResource(R.string.upload_file), modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_image)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_file_image), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(24.dp)) },
                            onClick = {
                                attachMenu = false
                                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_file)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_file_other), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(24.dp)) },
                            onClick = {
                                attachMenu = false
                                filePicker.launch(DOCUMENT_TYPES)
                            },
                        )
                    }
                }
                Spacer(Modifier.width(2.dp))
                if (state.generating) {
                    RoundAction(R.drawable.ic_stop_fill_16, stringResource(R.string.stop), enabled = true, onClick = onStop)
                } else {
                    RoundAction(R.drawable.ic_send_outline_16, stringResource(R.string.send), enabled = state.canSend) {
                        focusManager.clearFocus()
                        onSend()
                    }
                }
            }
        }
    }
}

@Composable
private fun TogglePill(@DrawableRes icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    val press = androidx.compose.runtime.remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent, tween(120), label = "toggle-fill")
    val foreground by animateColorAsState(if (selected) colors.primary else colors.onSurface, tween(120), label = "toggle-label")
    Surface(
        modifier = Modifier.pressFeedback(press),
        onClick = onClick,
        interactionSource = press,
        shape = RoundedCornerShape(50),
        color = fill,
        contentColor = foreground,
        border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = 0.3f) else colors.outline.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            DsIcon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun RoundAction(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val press = androidx.compose.runtime.remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        interactionSource = press,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.size(36.dp).pressFeedback(press),
    ) {
        Box(contentAlignment = Alignment.Center) {
            DsIcon(icon, contentDescription = label, modifier = Modifier.size(16.dp))
        }
    }
}

/** 草稿附件卡片：图片显示缩略图，文件显示名称；导入中转圈，失败可重试。 */
@Composable
private fun DraftAttachmentCard(item: DraftAttachment, onRemove: () -> Unit, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val thumbnail = if (item.isImage) rememberThumbnail(item.uri) else null
                if (thumbnail != null) {
                    Image(
                        thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else if (item.isImage) {
                    Icon(painterResource(R.drawable.ic_file_image), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(28.dp))
                } else {
                    FileIcon(item.attachment?.name ?: item.uri.lastPathSegment.orEmpty(), Modifier.size(28.dp))
                }
                if (!item.ready && !item.failed) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            Column(Modifier.width(120.dp).padding(horizontal = 8.dp)) {
                Text(
                    item.attachment?.name ?: item.uri.lastPathSegment.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (item.failed) stringResource(R.string.upload_failed) else formatSize(item.attachment?.size ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (item.failed) {
                IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                    DsIcon(R.drawable.ic_refresh_outline_16, contentDescription = stringResource(R.string.upload_failed), modifier = Modifier.size(16.dp))
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                DsIcon(R.drawable.ic_close_fill_14, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 从 SAF Uri 解码小尺寸缩略图 */
@Composable
private fun rememberThumbnail(uri: Uri): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

private const val THUMB_PX = 160
private const val MAX_PICK = 10
