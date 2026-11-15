package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.ui.theme.LocalExtraColors

/** 用户提问：附件 + 气泡；长按复制 / 编辑，编辑后作为兄弟节点重新发送。 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun UserMessage(
    turn: TurnView,
    busy: Boolean,
    onEdit: (String) -> Unit,
    onSwitchBranch: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = turn.message as Message.User
    var editing by rememberSaveable(message.id) { mutableStateOf(false) }
    var draft by rememberSaveable(message.id) { mutableStateOf(message.content) }
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val copy = rememberCopyAction()

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (message.files.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                message.files.forEach { FileChip(it) }
            }
        }
        if (editing) {
            val canSend = !busy && (draft.isNotBlank() || message.files.isNotEmpty())
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 8,
                    shape = RoundedCornerShape(16.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = {
                            onEdit(draft)
                            editing = false
                        },
                        enabled = canSend,
                    ) { Text(stringResource(R.string.send)) }
                }
            }
        } else if (message.content.isNotEmpty()) {
            Box {
                Surface(
                    color = LocalExtraColors.current.userBubble,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .padding(start = 48.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = true
                            },
                        ),
                ) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = {
                            menu = false
                            copy(message.content)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_message)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        enabled = !busy,
                        onClick = {
                            menu = false
                            draft = message.content
                            editing = true
                        },
                    )
                }
            }
        }
        if (!editing) {
            BranchSwitcher(turn.siblingIndex, turn.siblingCount, enabled = !busy, onSelect = onSwitchBranch)
        }
    }
}
