package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import io.github.smalltailqwq.shallowdrop.ui.motion.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton

@Composable
fun RechargeNotice(onAgree: () -> Unit, onDismiss: () -> Unit, saving: Boolean = false) {
    val scroll = rememberScrollState()
    var readToEnd by remember { mutableStateOf(false) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue != Int.MAX_VALUE && scroll.value >= scroll.maxValue }
            .collect { if (it) readToEnd = true }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.recharge_notice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.recharge_notice_destination), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.recharge_notice_account))
                    Text(stringResource(R.string.recharge_notice_recipient))
                    Text(stringResource(R.string.recharge_notice_service))
                }
                if (!readToEnd) Text(stringResource(R.string.recharge_read_to_end), style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().toggleable(agreed, enabled = readToEnd && !saving, role = Role.Checkbox, onValueChange = { agreed = it }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(agreed, onCheckedChange = null, enabled = readToEnd && !saving)
                    Text(stringResource(R.string.recharge_agreement), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree, enabled = readToEnd && agreed && !saving) { Text(stringResource(R.string.recharge_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}
