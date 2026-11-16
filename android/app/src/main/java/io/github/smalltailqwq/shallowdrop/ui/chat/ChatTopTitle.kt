package io.github.smalltailqwq.shallowdrop.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel

/**
 * 顶栏标题区：会话标题（新对话时没有）+ 当前模型。点击展开模型列表，选择后对之后的提问、重新生成生效。
 * 模型每次发送都会用到，放在这里随时可见、两步可换，不必进设置页。
 */
@Composable
fun ChatTopTitle(
    title: String,
    model: ChatModel,
    onSelectModel: (ChatModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.model) + ": " + model.id
    Box(modifier) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.DropdownList) { expanded = true }
                .semantics(mergeDescendants = true) { contentDescription = description }
                .heightIn(min = 48.dp)
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            if (title.isNotEmpty()) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.id,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // 新对话时模型是标题区唯一内容，用正文字号；有标题时作为第二行小字
                    style = if (title.isEmpty()) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                    color = if (title.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp).size(if (title.isEmpty()) 20.dp else 16.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ChatModel.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.id) },
                    trailingIcon = {
                        if (option == model) {
                            Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.model_selected), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        expanded = false
                        if (option != model) onSelectModel(option)
                    },
                )
            }
        }
    }
}
