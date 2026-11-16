package io.github.smalltailqwq.shallowdrop.ui.settings

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds.Companion.MAX_WAIT_PERCENT
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds.Companion.MIN_GAP_PERCENT
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds.Companion.MIN_PERCENT
import io.github.smalltailqwq.shallowdrop.ui.motion.MotionEaseOut
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun CompactionScale(
    thresholds: CompactionThresholds,
    startColor: Color,
    waitColor: Color,
    enabled: Boolean,
    onInteractionStart: () -> Unit = {},
    onChange: (CompactionThresholds) -> Unit,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptic = LocalHapticFeedback.current
    var width by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<Boolean?>(null) }
    var background by remember { mutableFloatStateOf(thresholds.backgroundPercent.toFloat()) }
    var foreground by remember { mutableFloatStateOf(thresholds.foregroundPercent.toFloat()) }
    var lastTick by remember { mutableLongStateOf(-40L) }
    LaunchedEffect(thresholds, dragging) {
        if (dragging != true) background = thresholds.backgroundPercent.toFloat()
        if (dragging != false) foreground = thresholds.foregroundPercent.toFloat()
    }
    val animatedStart by animateFloatAsState(background, if (dragging == true) snap() else tween(180, easing = MotionEaseOut), label = "compactionStart")
    val animatedWait by animateFloatAsState(foreground, if (dragging == false) snap() else tween(180, easing = MotionEaseOut), label = "compactionWait")
    val shownWait = if (dragging == false) foreground else animatedWait
    val shownStart = (if (dragging == true) background else animatedStart).coerceAtMost(shownWait - MIN_GAP_PERCENT)
    val inset = with(density) { 24.dp.toPx() }
    val trackWidth = (width - inset * 2).coerceAtLeast(1f)
    fun x(percent: Float) = inset + trackWidth * (if (rtl) 1 - percent / 100f else percent / 100f)
    fun update(start: Boolean, value: Float): Boolean {
        val range = if (start) MIN_PERCENT..thresholds.foregroundPercent - MIN_GAP_PERCENT
            else thresholds.backgroundPercent + MIN_GAP_PERCENT..MAX_WAIT_PERCENT
        val bounded = value.coerceIn(range.first.toFloat(), range.last.toFloat())
        if (start) background = bounded else foreground = bounded
        val previous = if (start) thresholds.backgroundPercent else thresholds.foregroundPercent
        val next = bounded.roundToInt()
        if (next == previous) return false
        onChange(if (start) thresholds.copy(backgroundPercent = next) else thresholds.copy(foregroundPercent = next))
        val now = SystemClock.uptimeMillis()
        val boundary = next == range.first || next == range.last
        if (boundary || now - lastTick >= 40) {
            haptic.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) {
                if (boundary) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick
            } else HapticFeedbackType.TextHandleMove)
            lastTick = now
        }
        return true
    }

    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val textMeasurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = muted, fontFeatureSettings = "tnum")
    val labels = (0..100 step 20).map { textMeasurer.measure("$it", tickStyle) }
    Box(Modifier.fillMaxWidth().height(176.dp).testTag("compaction_scale").onSizeChanged { width = it.width }
        .pointerInput(enabled, thresholds, width, rtl) {
            if (enabled) detectTapGestures { point ->
                // 轨道选择就近指针；指针本身的点击不改变数值。
                if (point.y >= 100.dp.toPx() && point.y <= 136.dp.toPx()) {
                    onInteractionStart()
                    val percent = ((point.x - inset) / trackWidth * 100).let { if (rtl) 100 - it else it }
                    update(abs(percent - background) <= abs(percent - foreground), percent.roundToInt().toFloat())
                }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val y = 116.dp.toPx()
            val height = 14.dp.toPx()
            drawRoundRect(trackColor, Offset(inset, y - height / 2), Size(trackWidth, height), CornerRadius(height / 2))
            fun band(from: Float, to: Float, color: Color) {
                val a = x(from); val b = x(to)
                drawRect(color, Offset(minOf(a, b), y - height / 2), Size(abs(b - a), height))
            }
            clipPath(Path().apply { addRoundRect(RoundRect(Rect(inset, y - height / 2, size.width - inset, y + height / 2), CornerRadius(height / 2))) }) {
                band(0f, shownStart, startColor.copy(alpha = .22f))
                band(shownStart, shownWait, waitColor.copy(alpha = .22f))
            }
            val cap = x(MAX_WAIT_PERCENT.toFloat())
            clipRect(minOf(cap, x(100f)), y - height / 2, maxOf(cap, x(100f)), y + height / 2) {
                var stripe = minOf(cap, x(100f)) - height
                while (stripe < maxOf(cap, x(100f)) + height) {
                    drawLine(muted.copy(alpha = .18f), Offset(stripe, y + height / 2), Offset(stripe + height, y - height / 2), 1.dp.toPx())
                    stripe += 7.dp.toPx()
                }
            }
            for (percent in 0..100 step 5) {
                val major = percent % 20 == 0
                drawLine(muted.copy(alpha = if (percent <= MAX_WAIT_PERCENT) .45f else .2f),
                    Offset(x(percent.toFloat()), y + 12.dp.toPx()), Offset(x(percent.toFloat()), y + (if (major) 19.dp else 15.dp).toPx()), 1.dp.toPx())
            }
            labels.forEachIndexed { index, label ->
                drawText(label, topLeft = Offset(x(index * 20f) - label.size.width / 2, y + 23.dp.toPx()))
            }
            for ((percent, top, color) in listOf(Triple(shownStart, 48f, startColor), Triple(shownWait, 0f, waitColor))) {
                val position = x(percent)
                drawLine(color.copy(alpha = .6f), Offset(position, (top + 44).dp.toPx()), Offset(position, y), 1.5.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(position, y - height / 2), Offset(position, y + height / 2), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        for (start in listOf(true, false)) {
            val label = stringResource(if (start) R.string.compaction_background else R.string.compaction_foreground)
            val tag = if (start) "compaction_background" else "compaction_foreground"
            val range = if (start) MIN_PERCENT..thresholds.foregroundPercent - MIN_GAP_PERCENT
                else thresholds.backgroundPercent + MIN_GAP_PERCENT..MAX_WAIT_PERCENT
            val value = if (start) thresholds.backgroundPercent else thresholds.foregroundPercent
            val shown = if (start) shownStart else shownWait
            val color = if (start) startColor else waitColor
            val active = dragging == start
            val grow by animateFloatAsState(if (active) 1.12f else 1f, tween(120, easing = MotionEaseOut), label = "compactionPin")
            Canvas(Modifier.absoluteOffset { IntOffset((x(shown) - inset).roundToInt(), with(density) { (if (start) 48.dp else 0.dp).roundToPx() }) }
                .size(48.dp).testTag("${tag}_slider")
                .semantics {
                    contentDescription = label
                    stateDescription = "$value%"
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, range.first.toFloat()..range.last.toFloat(), (range.last - range.first - 1).coerceAtLeast(0))
                    if (!enabled) disabled()
                    setProgress { enabled && update(start, it.roundToInt().toFloat()) }
                }
                .onKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown) false else {
                        val next = when (event.key) {
                            Key.DirectionRight -> value + if (rtl) -1 else 1
                            Key.DirectionLeft -> value + if (rtl) 1 else -1
                            Key.DirectionUp -> value + 1
                            Key.DirectionDown -> value - 1
                            Key.MoveHome -> range.first
                            Key.MoveEnd -> range.last
                            else -> return@onKeyEvent false
                        }
                        update(start, next.toFloat()); true
                    }
                }.focusable(enabled)
                .draggable(
                    state = rememberDraggableState { delta ->
                        update(start, (if (start) background else foreground) + delta / trackWidth * 100 * (if (rtl) -1 else 1))
                    }, orientation = Orientation.Horizontal, enabled = enabled,
                    onDragStarted = { onInteractionStart(); dragging = start },
                    onDragStopped = {
                        if (start) background = background.roundToInt().toFloat() else foreground = foreground.roundToInt().toFloat()
                        dragging = null
                    },
                )) {
                scale(grow, pivot = Offset(center.x, 44.dp.toPx())) {
                    if (active) drawCircle(color.copy(alpha = .12f), 23.dp.toPx(), Offset(center.x, 20.dp.toPx()))
                    val path = Path().apply {
                        moveTo(24.dp.toPx(), 44.dp.toPx())
                        cubicTo(19.dp.toPx(), 35.dp.toPx(), 9.dp.toPx(), 29.dp.toPx(), 9.dp.toPx(), 20.dp.toPx())
                        cubicTo(9.dp.toPx(), 0f, 39.dp.toPx(), 0f, 39.dp.toPx(), 20.dp.toPx())
                        cubicTo(39.dp.toPx(), 29.dp.toPx(), 29.dp.toPx(), 35.dp.toPx(), 24.dp.toPx(), 44.dp.toPx())
                        close()
                    }
                    drawPath(path, color.copy(alpha = if (enabled) 1f else .38f))
                    drawCircle(surface, 4.dp.toPx(), Offset(center.x, 20.dp.toPx()))
                }
            }
        }
    }
}
