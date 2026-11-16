package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds.Companion.MIN_GAP_PERCENT
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds.Companion.MIN_PERCENT
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds.Companion.MAX_WAIT_PERCENT
import io.github.smalltailqwq.shallowdrop.ui.motion.AlertDialog
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton

@Composable
internal fun CompactionDialog(
    initial: CompactionThresholds,
    initiallyEnabled: Boolean = true,
    saving: Boolean = false,
    onSave: (CompactionThresholds, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(initiallyEnabled) }
    var background by rememberSaveable { mutableIntStateOf(initial.backgroundPercent) }
    var foreground by rememberSaveable { mutableIntStateOf(initial.foregroundPercent) }
    var backgroundText by rememberSaveable { mutableStateOf(background.toString()) }
    var foregroundText by rememberSaveable { mutableStateOf(foreground.toString()) }
    val focus = LocalFocusManager.current
    val startColor = MaterialTheme.colorScheme.primary
    val waitColor = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFF91D5A8) else Color(0xFF26734D)

    fun commitBackground() {
        background = (backgroundText.toIntOrNull() ?: background).coerceIn(MIN_PERCENT, foreground - MIN_GAP_PERCENT)
        backgroundText = background.toString()
    }
    fun commitForeground() {
        foreground = (foregroundText.toIntOrNull() ?: foreground).coerceIn(background + MIN_GAP_PERCENT, MAX_WAIT_PERCENT)
        foregroundText = foreground.toString()
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.compaction_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("compaction_enabled")
                    .toggleable(enabled, enabled = !saving, role = Role.Switch, onValueChange = {
                        focus.clearFocus()
                        enabled = it
                    }), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.compaction_enable), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = null, enabled = !saving)
                }
                if (!enabled) Text(stringResource(R.string.compaction_disabled_help),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.alpha(if (enabled) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactionPercentField(
                        label = stringResource(R.string.compaction_background), tag = "compaction_background", color = startColor,
                        value = backgroundText, enabled = enabled && !saving,
                        onValueChange = { text ->
                            backgroundText = text
                            text.toIntOrNull()?.takeIf { it in MIN_PERCENT..foreground - MIN_GAP_PERCENT }?.let { background = it }
                        },
                        onCommit = ::commitBackground,
                        imeAction = ImeAction.Next,
                        keyboardActions = KeyboardActions(onNext = { commitBackground(); focus.moveFocus(FocusDirection.Next) }),
                    )
                    CompactionPercentField(
                        label = stringResource(R.string.compaction_foreground), tag = "compaction_foreground", color = waitColor,
                        value = foregroundText, enabled = enabled && !saving,
                        onValueChange = { text ->
                            foregroundText = text
                            text.toIntOrNull()?.takeIf { it in background + MIN_GAP_PERCENT..MAX_WAIT_PERCENT }?.let { foreground = it }
                        },
                        onCommit = ::commitForeground,
                        imeAction = ImeAction.Done,
                        keyboardActions = KeyboardActions(onDone = { commitForeground(); focus.clearFocus() }),
                    )
                    CompactionScale(
                        thresholds = CompactionThresholds(background, foreground),
                        startColor = startColor, waitColor = waitColor, enabled = enabled && !saving,
                        onInteractionStart = { focus.clearFocus() },
                        onChange = {
                            background = it.backgroundPercent
                            foreground = it.foregroundPercent
                            backgroundText = background.toString()
                            foregroundText = foreground.toString()
                        },
                    )
                    Text(stringResource(R.string.compaction_limits, MIN_GAP_PERCENT, MAX_WAIT_PERCENT),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                commitBackground()
                commitForeground()
                focus.clearFocus()
                onSave(CompactionThresholds(background, foreground), enabled)
            }, enabled = !saving) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CompactionPercentField(
    label: String, tag: String, color: Color, value: String, enabled: Boolean,
    onValueChange: (String) -> Unit, onCommit: () -> Unit,
    imeAction: ImeAction, keyboardActions: KeyboardActions,
) {
    var focused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= 3 && it.all { char -> char in '0'..'9' }) onValueChange(it) },
            modifier = Modifier.width(88.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.35f))
                .testTag("${tag}_value").semantics { contentDescription = label }
                .onFocusChanged {
                    // 不逐键截断：输入 75 时，第一位 7 可能暂时低于等待阈值的下界。
                    if (focused && !it.isFocused) onCommit()
                    focused = it.isFocused
                },
            enabled = enabled, singleLine = true, shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
            suffix = { Text("%") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
            keyboardActions = keyboardActions,
        )
    }
}
