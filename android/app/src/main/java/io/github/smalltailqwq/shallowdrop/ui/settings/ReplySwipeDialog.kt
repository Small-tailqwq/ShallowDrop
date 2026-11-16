package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeDirection
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.ui.motion.AlertDialog
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton
import kotlin.math.roundToInt

@Composable
internal fun swipeDirectionLabel(direction: ReplySwipeDirection): String = stringResource(
    if (direction == ReplySwipeDirection.LeftToRight) R.string.reply_swipe_left_to_right else R.string.reply_swipe_right_to_left)

@Composable
internal fun ReplySwipeDialog(initial: ReplySwipeSettings, onSave: (ReplySwipeSettings) -> Unit, onDismiss: () -> Unit) {
    var enabled by rememberSaveable(initial) { mutableStateOf(initial.enabled) }
    var direction by rememberSaveable(initial) { mutableStateOf(initial.direction) }
    var deadZone by rememberSaveable(initial) { mutableStateOf(initial.edgeDeadZoneDp) }
    var distance by rememberSaveable(initial) { mutableStateOf(initial.triggerDistanceDp) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reply_swipe_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(enabled, role = Role.Switch, onValueChange = { enabled = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reply_swipe_enable), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = null)
                }
                Column(Modifier.alpha(if (enabled) 1f else 0.5f)) {
                    Column(Modifier.selectableGroup()) {
                        ReplySwipeDirection.entries.forEach { option ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(direction == option, enabled = enabled, role = Role.RadioButton, onClick = { direction = option }),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = direction == option, enabled = enabled, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(swipeDirectionLabel(option))
                            }
                        }
                    }
                    SwipeDistanceControl(stringResource(R.string.reply_swipe_dead_zone), deadZone, 0f..96f, 23, enabled) { deadZone = it }
                    Text(stringResource(R.string.reply_swipe_dead_zone_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SwipeDistanceControl(stringResource(R.string.reply_swipe_distance), distance, 48f..160f, 27, enabled) { distance = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ReplySwipeSettings(enabled, direction, deadZone, distance)) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SwipeDistanceControl(title: String, value: Int, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.reply_swipe_dp, value), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.toFloat(), onValueChange = { onChange(it.roundToInt()) }, enabled = enabled, valueRange = range, steps = steps,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title })
    }
}
