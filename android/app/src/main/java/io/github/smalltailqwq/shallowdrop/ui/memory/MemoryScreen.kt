package io.github.smalltailqwq.shallowdrop.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.MemoryChange
import io.github.smalltailqwq.shallowdrop.core.model.MemoryChangeType
import io.github.smalltailqwq.shallowdrop.core.model.MemoryEntry
import io.github.smalltailqwq.shallowdrop.core.model.MemoryOrigin
import io.github.smalltailqwq.shallowdrop.core.model.MemoryQuote
import io.github.smalltailqwq.shallowdrop.core.model.MemoryRecord
import io.github.smalltailqwq.shallowdrop.domain.memory.MEMORY_BUDGET
import io.github.smalltailqwq.shallowdrop.domain.memory.memorySize
import io.github.smalltailqwq.shallowdrop.ui.common.ConfirmDialog
import io.github.smalltailqwq.shallowdrop.ui.common.resolve
import io.github.smalltailqwq.shallowdrop.ui.components.AppBottomSheet
import io.github.smalltailqwq.shallowdrop.ui.components.BackButton
import io.github.smalltailqwq.shallowdrop.ui.components.FirstLineAligned
import io.github.smalltailqwq.shallowdrop.ui.components.opticalCenter
import io.github.smalltailqwq.shallowdrop.ui.markdown.MarkdownText
import io.github.smalltailqwq.shallowdrop.ui.motion.AlertDialog
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton
import io.github.smalltailqwq.shallowdrop.ui.motion.pressClickable
import io.github.smalltailqwq.shallowdrop.ui.theme.LocalExtraColors
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedCardColor
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedPageColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class MemoryTab { Summary, Raw }

private enum class MemoryDialog { Enable, Add, Clear }

/**
 * 记忆页：开关、摘要（注入对话的记忆条目）与原始（每次变动依据的用户原话和改动）两栏，底部可就记忆提问。
 * 点开一条记忆可修改、删除并查看它的原话；原话和来源记录可跳回原对话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    onOpenConversation: (sessionId: String, messageId: String?) -> Unit,
    viewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val memory by viewModel.memory.collectAsStateWithLifecycle()
    val updating by viewModel.updating.collectAsStateWithLifecycle()
    val ask by viewModel.ask.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(MemoryTab.Summary) }
    var dialog by rememberSaveable { mutableStateOf<MemoryDialog?>(null) }
    var openEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var asking by rememberSaveable { mutableStateOf(false) }
    val open = { quote: MemoryQuote -> quote.sessionId?.let { viewModel.openConversation(it, quote.messageId, onOpenConversation) }; Unit }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbar.showSnackbar(it.resolve(context)) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(onBack, Modifier.padding(start = 12.dp)) },
                actions = {
                    IconButton(onClick = { dialog = MemoryDialog.Add }) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.memory_add))
                    }
                    if (memory.entries.isNotEmpty() || memory.records.isNotEmpty()) {
                        IconButton(onClick = { dialog = MemoryDialog.Clear }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.memory_clear))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = groupedPageColor()),
            )
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = groupedCardColor(),
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(CircleShape)
                        .pressClickable(role = Role.Button) { asking = true }.testTag("memory_ask"),
                ) {
                    Text(
                        stringResource(R.string.memory_ask_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = groupedPageColor(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("switch") {
                    EnableCard(settings.memoryEnabled, updating) { on ->
                        if (on) dialog = MemoryDialog.Enable else viewModel.setEnabled(false)
                    }
                }
                // 切换栏滚动时停在顶部
                stickyHeader("tabs") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().background(groupedPageColor()).padding(vertical = 8.dp)) {
                        MemoryTab.entries.forEachIndexed { index, item ->
                            val count = if (item == MemoryTab.Summary) memory.entries.size else memory.records.size
                            val label = stringResource(if (item == MemoryTab.Summary) R.string.memory_tab_summary else R.string.memory_tab_raw)
                            SegmentedButton(
                                selected = tab == item,
                                onClick = { tab = item },
                                shape = SegmentedButtonDefaults.itemShape(index, MemoryTab.entries.size),
                                icon = {},
                            ) { Text(if (count > 0) stringResource(R.string.memory_tab_count, label, count) else label) }
                        }
                    }
                }
                when (tab) {
                    MemoryTab.Summary -> {
                        if (memory.entries.isEmpty()) item("empty") { EmptyHint(stringResource(R.string.memory_empty)) }
                        else item("usage") { UsageBar(memorySize(memory.entries), MEMORY_BUDGET) }
                        // 最近更新的在前
                        items(memory.entries.sortedByDescending { it.updatedAt }, key = { it.id }) { entry ->
                            EntryCard(entry, Modifier.animateItem()) { openEntry = entry.id }
                        }
                    }
                    MemoryTab.Raw -> {
                        if (memory.records.isEmpty()) item("empty") { EmptyHint(stringResource(R.string.memory_raw_empty)) }
                        items(memory.records.asReversed(), key = { it.id }) { record ->
                            RecordCard(record, Modifier.animateItem(), open)
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        MemoryDialog.Enable -> EnableDialog(
            onConfirm = {
                dialog = null
                viewModel.setEnabled(true)
            },
            onDismiss = { dialog = null },
        )
        MemoryDialog.Add -> AddDialog(onAdd = { dialog = null; viewModel.add(it) }, onDismiss = { dialog = null })
        MemoryDialog.Clear -> ConfirmDialog(
            title = stringResource(R.string.memory_clear_title),
            text = stringResource(R.string.memory_clear_text),
            confirmLabel = stringResource(R.string.memory_clear_confirm),
            danger = true,
            onConfirm = {
                dialog = null
                viewModel.clear()
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    memory.entries.find { it.id == openEntry }?.let { entry ->
        EntrySheet(
            entry = entry,
            onSave = { viewModel.edit(entry.id, it); openEntry = null },
            onDelete = { viewModel.remove(entry.id); openEntry = null },
            onOpenQuote = { openEntry = null; open(it) },
            onDismiss = { openEntry = null },
        )
    }

    if (asking) AskSheet(ask, onAsk = viewModel::ask, onDismiss = { asking = false })
}

@Composable
private fun EnableCard(enabled: Boolean, updating: Boolean, onToggle: (Boolean) -> Unit) {
    val updatingLabel = stringResource(R.string.memory_updating)
    Surface(shape = RoundedCornerShape(16.dp), color = groupedCardColor()) {
        Column {
            Row(
                Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = onToggle).testTag("memory_enabled")
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.opticalCenter(MaterialTheme.typography.bodyLarge))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.memory_experimental),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp))
                        .padding(start = 6.dp, end = 6.dp, top = 1.dp, bottom = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = null)
            }
            if (updating) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = updatingLabel })
        }
    }
}

/** 开启前的确认：实验性功能、API 开支增加、可能影响回答效果。 */
@Composable
private fun EnableDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_enable_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                NoticeRow(Icons.Outlined.Science, stringResource(R.string.memory_notice_experimental), null)
                NoticeRow(Icons.Outlined.Payments, stringResource(R.string.memory_notice_cost), stringResource(R.string.memory_notice_cost_detail))
                NoticeRow(Icons.Outlined.Tune, stringResource(R.string.memory_notice_quality), stringResource(R.string.memory_notice_quality_detail))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.memory_enable_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NoticeRow(icon: ImageVector, title: String, detail: String?) {
    Row {
        FirstLineAligned(MaterialTheme.typography.bodyLarge) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 记忆占用：全部条目的总字数与上限。 */
@Composable
private fun UsageBar(used: Int, budget: Int) {
    val label = stringResource(R.string.memory_usage, used, budget)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { (used.toFloat() / budget).coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
            color = if (used >= budget * 4 / 5) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        Spacer(Modifier.width(12.dp))
        Text("$used / $budget", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun formatDate(at: Long): String = remember(at) {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate())
}

@Composable
private fun EntryCard(entry: MemoryEntry, modifier: Modifier, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = groupedCardColor(), modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressClickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(entry.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            val date = formatDate(entry.updatedAt)
            val until = entry.until?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let {
                stringResource(R.string.memory_until, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it))
            }
            Text(
                listOfNotNull(
                    date, until,
                    if (entry.quotes.isEmpty()) null else pluralStringResource(R.plurals.memory_quotes, entry.quotes.size, entry.quotes.size),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 用户原话：左侧竖线的引用块，下方是对话标题与日期；来自对话的可点开原对话。 */
@Composable
private fun QuoteBlock(quote: MemoryQuote, showSession: Boolean, onOpen: (MemoryQuote) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(8.dp))
            .then(if (quote.sessionId != null) Modifier.pressClickable { onOpen(quote) } else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(quote.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            if (showSession) {
                val title = quote.sessionTitle.ifBlank { if (quote.sessionId != null) stringResource(R.string.untitled_chat) else stringResource(R.string.memory_origin_ask) }
                Text(
                    title + " · " + formatDate(quote.at),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (quote.sessionId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ChangeLine(change: MemoryChange) {
    val (icon: ImageVector, tint: Color, label: Int) = when (change.type) {
        MemoryChangeType.Add -> Triple(Icons.Outlined.Add, MaterialTheme.colorScheme.primary, R.string.memory_change_add)
        MemoryChangeType.Update -> Triple(Icons.Outlined.Edit, MaterialTheme.colorScheme.tertiary, R.string.memory_change_update)
        MemoryChangeType.Delete -> Triple(Icons.Outlined.Remove, MaterialTheme.colorScheme.error, R.string.memory_change_delete)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        FirstLineAligned(MaterialTheme.typography.bodyMedium) {
            Icon(icon, contentDescription = stringResource(label), tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            change.before?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)
            }
            change.after?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun RecordCard(record: MemoryRecord, modifier: Modifier, onOpen: (MemoryQuote) -> Unit) {
    val title = when (record.origin) {
        MemoryOrigin.Conversation -> record.sessionTitle.ifBlank { stringResource(R.string.untitled_chat) }
        MemoryOrigin.Manual -> stringResource(R.string.memory_origin_manual)
        MemoryOrigin.Ask -> stringResource(R.string.memory_origin_ask)
        MemoryOrigin.Tidy -> stringResource(R.string.memory_origin_tidy)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = groupedCardColor(), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alignByBaseline())
                Text(formatDate(record.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp).alignByBaseline())
            }
            if (record.quotes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                record.quotes.forEach { QuoteBlock(it, showSession = false, onOpen = onOpen) }
            }
            Spacer(Modifier.height(6.dp))
            record.changes.forEach { ChangeLine(it) }
        }
    }
}

@Composable
private fun AddDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_add)) },
        text = {
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus), minLines = 2, maxLines = 6)
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.memory_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EntrySheet(
    entry: MemoryEntry,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenQuote: (MemoryQuote) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(entry.id) { mutableStateOf(entry.text) }
    AppBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = true) {
        LazyColumn(
            Modifier.fillMaxWidth().imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            item {
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().testTag("memory_entry_text"), minLines = 2, maxLines = 8)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank() && text.trim() != entry.text) {
                        Text(stringResource(R.string.memory_save))
                    }
                }
            }
            if (entry.quotes.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.memory_tab_raw),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                }
                items(entry.quotes.asReversed()) { quote -> QuoteBlock(quote, showSession = true, onOpen = onOpenQuote) }
            }
        }
    }
}

/** 提问面板：问答记录在上，输入框在下；回答里要求的记忆改动列在回答下方。 */
@Composable
private fun AskSheet(state: AskState, onAsk: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val count = state.exchanges.size * 2 + if (state.pending != null) 2 else 0
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val send = {
        if (text.isNotBlank() && state.pending == null) {
            onAsk(text)
            text = ""
        }
    }
    AppBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.exchanges.forEach { exchange ->
                    item { QuestionBubble(exchange.question) }
                    item {
                        Column {
                            MarkdownText(exchange.answer)
                            if (exchange.changes.isNotEmpty()) Spacer(Modifier.height(8.dp))
                            exchange.changes.forEach { ChangeLine(it) }
                        }
                    }
                }
                state.pending?.let { question ->
                    item { QuestionBubble(question) }
                    item { CircularProgressIndicator(Modifier.padding(vertical = 4.dp).size(20.dp), strokeWidth = 2.dp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text(stringResource(R.string.memory_ask_hint)) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f).focusRequester(focus).testTag("memory_ask_input"),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = send, enabled = text.isNotBlank() && state.pending == null) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.send))
                }
            }
        }
    }
}

@Composable
private fun QuestionBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = LocalExtraColors.current.userBubble, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(start = 48.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}
