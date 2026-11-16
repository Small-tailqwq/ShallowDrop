package io.github.smalltailqwq.shallowdrop.ui.chat.message

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.FileAttachment
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import io.github.smalltailqwq.shallowdrop.ui.components.FileIcon
import kotlinx.coroutines.launch

/** 复制到剪贴板；Android 13+ 系统自带提示，低版本弹 Toast。 */
@Composable
fun rememberCopyAction(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    return { text ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** 分支切换 `< 2 / 3 >`；只有一个版本时不显示。 */
@Composable
fun BranchSwitcher(index: Int, count: Int, enabled: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (count <= 1) return
    val label = stringResource(R.string.message_versions)
    Row(modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSelect(index - 1) }, enabled = enabled && index > 0, modifier = Modifier.size(32.dp)) {
            DsIcon(
                R.drawable.ic_chevron_right_outline_14,
                contentDescription = stringResource(R.string.previous_version),
                flip = true,
                autoMirror = true,
                modifier = Modifier.size(14.dp),
            )
        }
        Text("${index + 1} / $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { onSelect(index + 1) }, enabled = enabled && index < count - 1, modifier = Modifier.size(32.dp)) {
            DsIcon(
                R.drawable.ic_chevron_right_outline_14,
                contentDescription = stringResource(R.string.next_version),
                autoMirror = true,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 消息中的附件卡片：文件，以及没有本地原件的图片（有原件的图片见 AttachmentThumbnail） */
@Composable
fun FileChip(file: FileAttachment, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.widthIn(max = 240.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(file.name, Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val size = formatSize(file.size)
                if (size.isNotEmpty()) {
                    Text(size, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
