package io.github.smalltailqwq.shallowdrop.ui.components

import io.github.smalltailqwq.shallowdrop.ui.motion.pressFeedback
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R

/**
 * chat-demo（MIT）图标，资源为 `res/drawable/ic_*.xml`（由 `src/components/base/icons.tsx` 转换，来源见 docs/android/icons.md）。
 *
 * - [flip]：水平翻转。chat-demo 只有向右的箭头，向左的箭头翻转使用（BranchSwitcher.tsx 同样如此）。
 * - [autoMirror]：RTL 布局下再翻转一次，用于“前进 / 返回”这类有方向含义的箭头。
 */
@Composable
fun DsIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    flip: Boolean = false,
    autoMirror: Boolean = false,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mirrored = flip xor (autoMirror && rtl)
    Icon(
        painterResource(id),
        contentDescription = contentDescription,
        tint = tint,
        modifier = if (mirrored) modifier.graphicsLayer { scaleX = -1f } else modifier,
    )
}

/** 二级页面左上角的圆形返回按钮。 */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = androidx.compose.runtime.remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = press,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(40.dp).pressFeedback(press),
    ) {
        Box(contentAlignment = Alignment.Center) {
            DsIcon(
                R.drawable.ic_chevron_right_outline_14,
                contentDescription = stringResource(R.string.back),
                flip = true,
                autoMirror = true,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private val IMAGE_EXTS = setOf("JPG", "JPEG", "PNG", "GIF", "WEBP", "BMP", "SVG", "HEIC", "TIF", "TIFF")
private val TABLE_EXTS = setOf("XLS", "XLSX", "CSV")
private val PPT_EXTS = setOf("PPT", "PPTX")

/** 按扩展名选文件图标，对照 FileIcon.tsx。 */
@DrawableRes
fun fileIconRes(fileName: String): Int {
    val ext = fileName.substringAfterLast('.', "").uppercase()
    return when {
        ext in IMAGE_EXTS -> R.drawable.ic_file_image
        ext == "PDF" -> R.drawable.ic_file_pdf
        ext in TABLE_EXTS -> R.drawable.ic_file_table
        ext in PPT_EXTS -> R.drawable.ic_file_ppt
        else -> R.drawable.ic_file_other
    }
}

/** 彩色文件图标（自带配色，不着色）。 */
@Composable
fun FileIcon(fileName: String, modifier: Modifier = Modifier) {
    Icon(painterResource(fileIconRes(fileName)), contentDescription = null, tint = Color.Unspecified, modifier = modifier)
}
