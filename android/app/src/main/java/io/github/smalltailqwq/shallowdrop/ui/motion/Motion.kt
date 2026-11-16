package io.github.smalltailqwq.shallowdrop.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/** 按压仅变换绘制层；Compose 的动画时钟遵循系统动画时长设置。 */
val MotionEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
const val PAGE_MOTION_MS = 220

@Composable
fun Modifier.pressFeedback(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.97f else 1f, tween(120, easing = MotionEaseOut), label = "press")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

fun Modifier.pressClickable(enabled: Boolean = true, role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    pressFeedback(source).clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = role, onClick = onClick)
}

@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.IconButton(onClick, modifier.pressFeedback(source), enabled, interactionSource = source, content = content)
}

@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.FilledIconButton(onClick, modifier.pressFeedback(source), enabled, shape = shape, colors = colors, interactionSource = source, content = content)
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.Button(onClick, modifier.pressFeedback(source), enabled, interactionSource = source, content = content)
}

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.TextButton(onClick, modifier.pressFeedback(source), enabled, interactionSource = source, content = content)
}

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.OutlinedButton(onClick, modifier.pressFeedback(source), enabled, interactionSource = source, content = content)
}
