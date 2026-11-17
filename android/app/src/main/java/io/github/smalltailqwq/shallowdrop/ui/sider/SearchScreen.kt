package io.github.smalltailqwq.shallowdrop.ui.sider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.components.BackButton
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import io.github.smalltailqwq.shallowdrop.ui.motion.pressClickable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 独立的搜索页：顶部返回 + 搜索框，下方为命中的会话（标题与消息、文件名片段）。
 * 与对话页是两个页面，键盘弹起只缩小本页列表，不会带动对话输入栏。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onSelect: (SearchTarget) -> Unit,
    viewModel: SessionSearchViewModel = viewModel(factory = SessionSearchViewModel.Factory),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val found by viewModel.matches.collectAsStateWithLifecycle()
    val untitled = stringResource(R.string.untitled_chat)
    val current = found?.takeIf { it.query == query.trim() }
    val results by produceState<List<SearchResult>?>(null, sessions, current, untitled) {
        value = null
        if (current != null) {
            value = withContext(Dispatchers.Default) { buildSearchResults(sessions, current.matches, current.query, untitled) }
        }
    }
    SearchContent(query, viewModel::setQuery, if (current == null) null else results, current?.query.orEmpty(), untitled, onBack, onSelect)
}

@Composable
internal fun SearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    /** null：查询为空或尚未读到结果 */
    results: List<SearchResult>?,
    highlight: String,
    untitled: String,
    onBack: () -> Unit,
    onSelect: (SearchTarget) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = {
                focusManager.clearFocus()
                onBack()
            })
            Spacer(Modifier.width(8.dp))
            SearchField(query, onQueryChange, Modifier.weight(1f))
        }
        when {
            results == null && query.isNotBlank() -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            results == null -> Unit
            results.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.sider_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)) {
                items(results, key = { it.session.id }) { result ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        ResultItem(result, highlight, untitled, Modifier.widthIn(max = 760.dp)) {
                            focusManager.clearFocus()
                            onSelect(SearchTarget(result.session.id, result.messageId, highlight))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultItem(result: SearchResult, needle: String, untitled: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val mark = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .pressClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(38.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    highlighted(result.session.title.ifEmpty { untitled }, needle, mark),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                Text(resultDate(result.session.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            result.snippets.firstOrNull()?.let { snippet ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (snippet.isFileName) {
                        DsIcon(
                            R.drawable.ic_paperclip_outline_16,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        highlighted(snippet.text, needle, mark),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun resultDate(timestamp: Long): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (date) {
        today -> DateFormat.getTimeFormat(context).format(timestamp)
        today.minusDays(1) -> stringResource(R.string.group_yesterday)
        else -> SimpleDateFormat(
            DateFormat.getBestDateTimePattern(locale, if (date.year == today.year) "MMMd" else "yMMMd"), locale,
        ).format(timestamp)
    }
}

/** 把 text 中所有 needle（忽略大小写）标成强调色。 */
internal fun highlighted(text: String, needle: String, color: Color): AnnotatedString = buildAnnotatedString {
    if (needle.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var from = 0
    while (true) {
        val at = text.indexOf(needle, from, ignoreCase = true)
        if (at < 0) break
        append(text.substring(from, at))
        withStyle(SpanStyle(color = color)) { append(text.substring(at, at + needle.length)) }
        from = at + needle.length
    }
    append(text.substring(from))
}

/** 胶囊形搜索框，页面进入完成后聚焦，带清除按钮。 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val placeholder = stringResource(R.string.sider_search)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        // 导航转场期间仅处于 STARTED，避免键盘和页面同时移动。
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        focusRequester.requestFocus()
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier.focusRequester(focusRequester).semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsIcon(R.drawable.ic_search_outline_16, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                        DsIcon(R.drawable.ic_close_fill_14, contentDescription = stringResource(R.string.clear), modifier = Modifier.size(16.dp))
                    }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
            }
        },
    )
}
