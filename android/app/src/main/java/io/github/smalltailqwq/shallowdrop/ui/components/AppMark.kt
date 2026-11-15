package io.github.smalltailqwq.shallowdrop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/** 应用标志：原创水滴形，与启动图标同形。 */
@Composable
fun AppMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val radius = w * 0.32f
        val cx = w / 2
        val cy = h - radius - h * 0.06f
        val path = Path().apply {
            moveTo(cx, h * 0.04f)
            cubicTo(cx, h * 0.04f, cx - radius, cy - radius * 0.9f, cx - radius, cy)
            arcTo(Rect(cx - radius, cy - radius, cx + radius, cy + radius), 180f, -180f, false)
            cubicTo(cx + radius, cy - radius * 0.9f, cx, h * 0.04f, cx, h * 0.04f)
            close()
        }
        drawPath(path, color)
    }
}
