package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.offset
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeDirection
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 提示圆的直径 */
private val HINT_SIZE = 32.dp

/**
 * 横滑重新生成：内容带橡皮筋阻尼跟手，滑出方向的空位里浮出刷新提示并随进度填满；
 * 首次越过触发距离时轻震一次。松手时越过阈值立即重新生成，新内容淡入落回；否则带过冲回弹。
 * [showGuide] 用于设置页演示，把边缘死区、起手点和触发距离画出来。
 */
@Composable
internal fun RegeneratableReply(
    enabled: Boolean,
    settings: ReplySwipeSettings,
    onRegenerate: () -> Unit,
    showGuide: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!enabled || !settings.enabled) {
        content()
        return
    }
    val density = LocalDensity.current
    val threshold = with(density) { settings.triggerDistanceDp.dp.toPx() }
    val deadZone = with(density) { settings.edgeDeadZoneDp.dp.toPx() }
    val sign = if (settings.direction == ReplySwipeDirection.LeftToRight) 1f else -1f
    val regenerate by rememberUpdatedState(onRegenerate)
    val haptic = LocalHapticFeedback.current

    // 手指相对起手点沿滑动方向走过的距离；内容位移由它经阻尼换算
    var distance by remember(settings) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    // 触发后的落回过程
    var settling by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(Offset.Zero) }
    var width by remember { mutableFloatStateOf(0f) }
    val offset = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    // 在死区内起手时闪一下死区，演示里看得出这一下被让给了边缘手势
    val deadZoneFlash = remember { Animatable(0f) }
    val hintArmed by animateFloatAsState(if (armed) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "swipe-armed")

    val colors = MaterialTheme.colorScheme
    val guideColor = colors.primary
    val trackColor = colors.outlineVariant

    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(settings, threshold, deadZone) {
                val latch = ThresholdLatch()
                coroutineScope {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        // 在边缘起手时不消费事件，让抽屉和内容自己的手势处理
                        if (ReplySwipePhysics.inDeadZone(first.position.x, size.width.toFloat(), deadZone, sign)) {
                            if (showGuide) launch {
                                deadZoneFlash.snapTo(1f)
                                deadZoneFlash.animateTo(0f, tween(450))
                            }
                            return@awaitEachGesture
                        }
                        val velocity = VelocityTracker()
                        latch.reset()
                        down = first.position
                        distance = 0f
                        fun move(amount: Float) {
                            distance = (distance + amount * sign).coerceAtLeast(0f)
                            if (latch.update(distance, threshold)) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            armed = latch.armed
                            val target = sign * ReplySwipePhysics.visualOffset(distance, threshold)
                            launch { offset.snapTo(target) }
                        }
                        val start = awaitHorizontalTouchSlopOrCancellation(first.id) { change, over ->
                            if (over * sign > 0) {
                                change.consume()
                                dragging = true
                                velocity.addPosition(change.uptimeMillis, change.position)
                                move(over)
                            }
                        } ?: return@awaitEachGesture
                        val completed = horizontalDrag(start.id) { change ->
                            velocity.addPosition(change.uptimeMillis, change.position)
                            move(change.positionChange().x)
                            change.consume()
                        }
                        val trigger = completed && distance >= threshold
                        val releaseVelocity = velocity.calculateVelocity().x
                        dragging = false
                        armed = false
                        distance = 0f
                        // 松手即触发，不等动画：对话页里旧回复会立刻换成新的生成
                        if (trigger) regenerate()
                        settling = trigger
                        launch {
                            if (trigger) {
                                // 新内容从松手位置淡入并落回原位，不再显示刷新提示
                                alpha.snapTo(0.15f)
                                launch { alpha.animateTo(1f, tween(220)) }
                                offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                                settling = false
                            } else {
                                // 未达阈值：带一点过冲回弹，松手速度越快回得越利落
                                offset.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                                    initialVelocity = releaseVelocity * 0.35f,
                                )
                            }
                        }
                    }
                }
            }
            // 演示用的标线画在内容下面，不挡正文
            .drawBehind {
                if (!showGuide) return@drawBehind
                drawDeadZone(deadZone, sign, guideColor, 0.1f + 0.2f * deadZoneFlash.value)
                if (dragging) drawThresholdGuide(down, sign, threshold, distance, armed, guideColor, trackColor)
            },
    ) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            translationX = offset.value
            this.alpha = alpha.value
        }) { content() }
        // 刷新提示：浮在滑出后空出的那一侧，与手指同高
        val progress = ReplySwipePhysics.progress(distanceOrOffset(dragging, distance, offset.value, threshold), threshold)
        if (progress > 0f && offset.value * sign > 0f && !settling) {
            val hintPx = with(density) { HINT_SIZE.toPx() }
            val gapCenter = if (sign < 0) width + offset.value / 2f else offset.value / 2f
            val x = gapCenter.coerceIn(hintPx / 2f, (width - hintPx / 2f).coerceAtLeast(hintPx / 2f)) - hintPx / 2f
            val y = down.y - hintPx / 2f
            SwipeHint(
                progress = progress,
                armed = hintArmed,
                modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .graphicsLayer {
                        val appear = ((progress - 0.15f) / 0.35f).coerceIn(0f, 1f)
                        this.alpha = appear
                        val scale = 0.6f + 0.4f * appear
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

/** 拖动中按真实距离显示进度；松手回弹时按剩余位移倒推，提示随内容一起收回 */
private fun distanceOrOffset(dragging: Boolean, distance: Float, offset: Float, threshold: Float): Float {
    if (dragging) return distance
    val limit = threshold * ReplySwipePhysics.LIMIT_RATIO
    val visual = abs(offset).coerceAtMost(limit * 0.999f)
    // rubberBand 的反函数：d = limit·v / (limit − v)
    return limit * visual / (limit - visual)
}

@Composable
private fun SwipeHint(progress: Float, armed: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val ring = colors.primary
    val track = colors.outlineVariant
    val fill = colors.primary.copy(alpha = armed)
    val iconTint = androidx.compose.ui.graphics.lerp(colors.onSurfaceVariant, colors.onPrimary, armed)
    Box(
        modifier.size(HINT_SIZE).drawBehind {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(colors.surface)
            drawCircle(fill)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(ring, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        DsIcon(
            R.drawable.ic_refresh_outline_16,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f * progress },
        )
    }
}

/** 边缘死区：起始侧的一条淡色区域，边界画虚线 */
private fun DrawScope.drawDeadZone(deadZone: Float, sign: Float, color: Color, alpha: Float) {
    if (deadZone <= 0f) return
    val left = if (sign > 0) 0f else size.width - deadZone
    drawRect(color.copy(alpha = alpha), Offset(left, 0f), Size(deadZone, size.height))
    val edge = if (sign > 0) deadZone else size.width - deadZone
    drawLine(
        color.copy(alpha = 0.5f), Offset(edge, 0f), Offset(edge, size.height), 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
    )
}

/** 拖动中：起手点到触发距离的进度轨道，以及贯穿整段回复的阈值线 */
private fun DrawScope.drawThresholdGuide(
    down: Offset, sign: Float, threshold: Float, distance: Float, armed: Boolean, color: Color, track: Color,
) {
    val end = down.x + sign * threshold
    val finger = down.x + sign * distance.coerceAtMost(threshold)
    val lineColor = if (armed) color else track
    drawLine(
        lineColor, Offset(end, 0f), Offset(end, size.height), 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
    )
    val stroke = 4.dp.toPx()
    drawLine(track, Offset(down.x, down.y), Offset(end, down.y), stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(down.x, down.y), Offset(finger, down.y), stroke, cap = StrokeCap.Round)
    drawCircle(color, 5.dp.toPx(), Offset(down.x, down.y))
    val tick = 12.dp.toPx()
    drawLine(lineColor, Offset(end, down.y - tick), Offset(end, down.y + tick), 2.dp.toPx(), cap = StrokeCap.Round)
}
