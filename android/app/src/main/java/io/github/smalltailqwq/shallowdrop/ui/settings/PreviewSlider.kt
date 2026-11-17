package io.github.smalltailqwq.shallowdrop.ui.settings

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

private val ThumbWidth = 4.dp
private val SliderThickness = 48.dp

@Stable
internal class PreviewSliderState(
    private val target: MutableFloatState,
    val range: ClosedFloatingPointRange<Float>,
    val intervals: Int,
    val interactions: MutableInteractionSource,
    private val animated: State<Float>,
    private val dragging: State<Boolean>,
    private val haptic: HapticFeedback,
) {
    private var lastTick = -40L
    val value: Float get() = if (dragging.value) target.floatValue else animated.value
    val step: Float get() = (range.endInclusive - range.start) / intervals
    val selectedValue: Float get() = snapped(target.floatValue)

    fun change(value: Float) {
        val previous = selectedValue
        target.floatValue = value.coerceIn(range)
        val next = selectedValue
        if (next == previous) return
        val now = SystemClock.uptimeMillis()
        val boundary = next == range.start || next == range.endInclusive
        if (boundary || now - lastTick >= 40) {
            haptic.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) {
                if (boundary) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick
            } else HapticFeedbackType.TextHandleMove)
            lastTick = now
        }
    }
    fun finish() { target.floatValue = selectedValue }
    fun select(value: Float) { change(snapped(value)) }
    fun valueOnLeave(initial: Float): Float = if (target.floatValue == initial) initial else selectedValue

    private fun snapped(value: Float): Float =
        (range.start + ((value - range.start) / step).roundToInt() * step).coerceIn(range)
}

@Composable
internal fun rememberPreviewSlider(initial: Float, range: ClosedFloatingPointRange<Float>, intervals: Int): PreviewSliderState {
    val target = rememberSaveable { mutableFloatStateOf(initial) }
    val interactions = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val dragging = interactions.collectIsDraggedAsState()
    val animated = animateFloatAsState(target.floatValue, if (dragging.value) snap() else tween(200), label = "previewSlider")
    return remember(target, range, intervals, interactions, animated, dragging, haptic) {
        PreviewSliderState(target, range, intervals, interactions, animated, dragging, haptic)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewSlider(
    state: PreviewSliderState,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val colors = SliderDefaults.colors()
    Slider(
        value = state.value,
        onValueChange = state::change,
        onValueChangeFinished = state::finish,
        valueRange = state.range,
        interactionSource = state.interactions,
        enabled = enabled,
        modifier = modifier.onPreviewKeyEvent { event ->
            val next = when (event.key) {
                Key.DirectionUp, Key.PageUp -> state.selectedValue + state.step
                Key.DirectionDown, Key.PageDown -> state.selectedValue - state.step
                Key.DirectionRight -> state.selectedValue + if (rtl) -state.step else state.step
                Key.DirectionLeft -> state.selectedValue + if (rtl) state.step else -state.step
                Key.MoveHome -> state.range.start
                Key.MoveEnd -> state.range.endInclusive
                else -> return@onPreviewKeyEvent false
            }
            if (!enabled) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown) state.select(next)
            true
        }.semantics {
            contentDescription = title
            description?.let { stateDescription = it }
            progressBarRangeInfo = ProgressBarRangeInfo(state.value, state.range, state.intervals - 1)
            setProgress { value ->
                if (!enabled) false else {
                    val before = state.selectedValue
                    state.select(value)
                    state.selectedValue != before
                }
            }
        },
        thumb = {
            // 固定手柄宽度，使按下与拖动时的轨道端点保持一致。
            SliderDefaults.Thumb(state.interactions, modifier = Modifier.width(ThumbWidth).testTag("preview-slider-thumb"),
                enabled = enabled, colors = colors)
        },
        track = { slider ->
            Box {
                SliderDefaults.Track(slider, enabled = enabled, colors = colors, drawStopIndicator = null)
                Canvas(Modifier.matchParentSize()) {
                    val fraction = (state.value - state.range.start) / (state.range.endInclusive - state.range.start)
                    for (index in 0..state.intervals) {
                        val position = index.toFloat() / state.intervals
                        val active = position <= fraction
                        val color = if (enabled) {
                            if (active) colors.activeTickColor else colors.inactiveTickColor
                        } else {
                            if (active) colors.disabledActiveTickColor else colors.disabledInactiveTickColor
                        }
                        drawCircle(color, radius = 2.dp.toPx(), center = Offset(size.width * if (rtl) 1 - position else position, size.height / 2))
                    }
                }
            }
        },
    )
}

@Composable
internal fun LabeledPreviewSlider(
    state: PreviewSliderState,
    title: String,
    labels: List<String>,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            if (vertical) {
                Layout(content = {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        PreviewSlider(state, title, Modifier.graphicsLayer { rotationZ = -90f },
                            description = labels[state.selectedValue.roundToInt()])
                    }
                }) { measurables, constraints ->
                    val length = constraints.maxHeight
                    val thickness = SliderThickness.roundToPx()
                    val slider = measurables.single().measure(Constraints.fixed(length, thickness))
                    layout(thickness, length) { slider.place((thickness - length) / 2, (length - thickness) / 2) }
                }
            } else {
                PreviewSlider(state, title, description = labels[state.selectedValue.roundToInt()])
            }
            labels.forEachIndexed { index, label ->
                val selected = state.selectedValue.roundToInt() == index
                Text(label, modifier = Modifier.selectable(selected, role = Role.RadioButton) { state.select(index.toFloat()) }
                    .padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
                    textAlign = if (vertical) TextAlign.Start else TextAlign.Center,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val thickness = SliderThickness.roundToPx()
        val gap = 12.dp.roundToPx()
        val labelWidth = if (vertical) width - thickness - gap else width / labels.size
        val texts = measurables.drop(1).map { it.measure(Constraints(maxWidth = labelWidth.coerceAtLeast(0))) }
        val thumbWidth = ThumbWidth.roundToPx()
        if (vertical) {
            val tallest = texts.maxOf { it.height }
            val height = constraints.constrainHeight(max(constraints.minHeight, max(tallest, thickness) * labels.size))
            val trackLength = height - tallest
            val slider = measurables.first().measure(Constraints.fixed(thickness, trackLength + thumbWidth))
            layout(width, height) {
                slider.placeRelative(0, (tallest - thumbWidth) / 2)
                texts.forEachIndexed { index, text ->
                    val center = tallest / 2f + trackLength * (1f - index.toFloat() / state.intervals)
                    text.placeRelative(thickness + gap, (center - text.height / 2f).roundToInt())
                }
            }
        } else {
            val widest = texts.maxOf { it.width }
            val trackLength = width - widest
            val slider = measurables.first().measure(Constraints.fixedWidth(trackLength + thumbWidth))
            layout(width, constraints.constrainHeight(slider.height + texts.maxOf { it.height })) {
                slider.placeRelative((widest - thumbWidth) / 2, 0)
                texts.forEachIndexed { index, text ->
                    val center = widest / 2f + trackLength * index.toFloat() / state.intervals
                    text.placeRelative((center - text.width / 2f).roundToInt(), slider.height)
                }
            }
        }
    }
}
