package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeDirection
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings

@Composable
internal fun RegeneratableReply(enabled: Boolean, settings: ReplySwipeSettings, onRegenerate: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled || !settings.enabled) {
        content()
        return
    }
    var distance by remember(settings) { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { settings.triggerDistanceDp.dp.toPx() }
    val deadZone = with(LocalDensity.current) { settings.edgeDeadZoneDp.dp.toPx() }
    val sign = if (settings.direction == ReplySwipeDirection.LeftToRight) 1f else -1f
    val regenerate by rememberUpdatedState(onRegenerate)
    val haptic = LocalHapticFeedback.current
    val offset by animateFloatAsState(distance * sign * 0.18f, spring(), label = "reply-swipe")
    Box(
        Modifier.fillMaxWidth()
            .pointerInput(settings, threshold, deadZone) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 在边缘起手时不消费事件，让抽屉和内容自己的手势处理。
                    if ((sign > 0 && down.position.x < deadZone) ||
                        (sign < 0 && down.position.x > size.width - deadZone)) return@awaitEachGesture
                    fun move(amount: Float) {
                        val previous = distance
                        distance = (distance + amount * sign).coerceIn(0f, threshold * 1.5f)
                        if (previous < threshold && distance >= threshold) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    distance = 0f
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        if (over * sign > 0) {
                            change.consume()
                            move(over)
                        }
                    } ?: return@awaitEachGesture
                    val completed = horizontalDrag(start.id) { change ->
                        move(change.positionChange().x)
                        change.consume()
                    }
                    val trigger = completed && distance >= threshold
                    distance = 0f
                    if (trigger) regenerate()
                }
            }
            .graphicsLayer { translationX = offset },
    ) { content() }
}
