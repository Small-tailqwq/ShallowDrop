package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize
import io.github.smalltailqwq.shallowdrop.ui.motion.AlertDialog
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton
import io.github.smalltailqwq.shallowdrop.ui.theme.ChatTextTheme
import io.github.smalltailqwq.shallowdrop.ui.theme.LocalExtraColors
import kotlin.math.roundToInt

@Composable
internal fun fontSizeLabel(size: ChatFontSize): String = stringResource(when (size) {
    ChatFontSize.Small -> R.string.font_size_small
    ChatFontSize.Standard -> R.string.font_size_standard
    ChatFontSize.Large -> R.string.font_size_large
    ChatFontSize.ExtraLarge -> R.string.font_size_extra_large
})

@Composable
internal fun FontSizeDialog(initial: ChatFontSize, onSave: (ChatFontSize) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable(initial) { mutableStateOf(initial) }
    val title = stringResource(R.string.chat_font_size)
    val label = fontSizeLabel(selected)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.background) {
                    ChatTextTheme(selected) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Surface(
                                modifier = Modifier.align(Alignment.End),
                                shape = RoundedCornerShape(16.dp),
                                color = LocalExtraColors.current.userBubble,
                            ) {
                                Text(stringResource(R.string.font_size_preview_question),
                                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                            Text(stringResource(R.string.font_size_preview_reply), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Slider(
                    value = selected.ordinal.toFloat(),
                    onValueChange = { selected = ChatFontSize.entries[it.roundToInt()] },
                    valueRange = 0f..ChatFontSize.entries.lastIndex.toFloat(),
                    steps = ChatFontSize.entries.size - 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics {
                        contentDescription = title
                        stateDescription = label
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ChatFontSize.entries.forEach { size ->
                        Text(fontSizeLabel(size), style = MaterialTheme.typography.labelMedium,
                            color = if (selected == size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
