package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.AreaTitle
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.FindItem
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.PageItem
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.ReplyView
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Step
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.ToolStatus
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.areaTitle
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.isDeepSearch
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.isImageSearch
import io.github.smalltailqwq.shallowdrop.ui.theme.LocalExtraColors
import kotlinx.coroutines.delay

/** 进行中每隔 interval 毫秒返回新的当前时间，用于思考用时 */
@Composable
fun rememberNow(active: Boolean, interval: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        now = System.currentTimeMillis()
        if (active) {
            while (true) {
                delay(interval)
                now = System.currentTimeMillis()
            }
        }
    }
    return now
}

@Composable
private fun AreaTitle.text(): String = when (this) {
    is AreaTitle.Duration -> pluralStringResource(R.plurals.think_duration, seconds.toInt(), seconds.toInt())
    AreaTitle.ThinkingLabel -> stringResource(R.string.thinking_switch)
    AreaTitle.Stopped -> stringResource(R.string.think_stopped)
    AreaTitle.ImageSearching -> stringResource(R.string.image_searching)
    AreaTitle.Searching -> stringResource(R.string.deep_search_searching)
    AreaTitle.Browsing -> stringResource(R.string.browsing_web)
    AreaTitle.Reading -> stringResource(R.string.reading_link)
    AreaTitle.Thinking -> stringResource(R.string.thinking_in_progress)
}

/** 进行中的文字呼吸效果 */
@Composable
private fun Modifier.shimmer(active: Boolean): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    return alpha(alpha)
}

/**
 * 思考与工具步骤时间线，对照 ReplyArea.tsx：
 * 开启深度思考时带可折叠标题（正在思考 / 正在搜索 / 已思考（用时 N 秒））；否则只有工具步骤。
 */
@Composable
fun ReplyArea(
    message: Message.Assistant,
    view: ReplyView,
    streaming: Boolean,
    hasResponse: Boolean,
    onOpenSources: (List<Source>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (view.steps.isEmpty() && !(message.thinking && streaming)) return
    var open by rememberSaveable(message.id) { mutableStateOf(true) }
    val titleActive = streaming && !hasResponse
    val now = rememberNow(titleActive)
    val thinking = message.thinking || view.steps.any { it is Step.Thinking }
    val title = if (thinking) areaTitle(view, streaming, now, hasResponse) else null
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { open = !open }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    title.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.shimmer(titleActive),
                )
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = open || title == null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(top = 4.dp)) {
                view.steps.forEachIndexed { index, step ->
                    StepItem(
                        step = step,
                        message = message,
                        sources = view.sources,
                        streaming = streaming,
                        isLast = index == view.steps.lastIndex,
                        onOpenSources = onOpenSources,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepItem(
    step: Step,
    message: Message.Assistant,
    sources: List<Source>,
    streaming: Boolean,
    isLast: Boolean,
    onOpenSources: (List<Source>) -> Unit,
) {
    when (step) {
        is Step.Thinking -> {
            val lastBlock = step.blocks.lastOrNull()
            val active = streaming && lastBlock?.startedAt != null && lastBlock.endedAt == null
            TimelineRow(icon = null, active = active, isLast = isLast) {
                CompositionLocalProvider(
                    LocalContentColor provides LocalExtraColors.current.thinkingText,
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                ) {
                    MessageMarkdown(step.blocks.joinToString("") { it.thinking }, emptyList())
                }
            }
        }
        is Step.Preamble -> TimelineRow(icon = null, active = false, isLast = isLast) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                MessageMarkdown(step.markdown, sources)
            }
        }
        is Step.Search -> SearchRow(step, message, streaming, isLast, onOpenSources)
        is Step.Open -> OpenRow(step, sources, isLast, onOpenSources)
        is Step.Find -> FindRow(step, isLast)
        is Step.ReadLink -> TimelineRow(Icons.Outlined.Link, active = false, isLast = isLast) {
            ChipFlow {
                Text(stringResource(R.string.reading_link), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.shimmer(true))
                step.links.forEach { PageChip(it) }
            }
        }
    }
}

/** 时间线行：左侧图标（或圆点）与贯穿的竖线，右侧内容。 */
@Composable
private fun TimelineRow(
    icon: ImageVector?,
    active: Boolean,
    isLast: Boolean,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val line = LocalExtraColors.current.thinkingLine
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (!isLast) {
                Box(
                    Modifier
                        .padding(top = 22.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(line),
                )
            }
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp).size(16.dp),
                )
            } else {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (active) MaterialTheme.colorScheme.primary else line),
                )
            }
        }
        Box(Modifier.weight(1f).padding(start = 6.dp, bottom = if (isLast) 4.dp else 14.dp)) {
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** 搜索：普通搜索“已阅读 N 个网页”，边想边搜“搜索到 N 个网页”；点开来源面板 */
@Composable
private fun SearchRow(
    step: Step.Search,
    message: Message.Assistant,
    streaming: Boolean,
    isLast: Boolean,
    onOpenSources: (List<Source>) -> Unit,
) {
    val wip = step.status == ToolStatus.Wip
    val failed = step.status == ToolStatus.Failed
    val interrupted = failed && !streaming && message.status == ReplyStatus.Stopped
    val count = step.results.size
    val clickable = !wip && count > 0
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(wip, step.queries.size) {
        if (wip && step.queries.size > 1) {
            while (true) {
                delay(2000)
                tick++
            }
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    TimelineRow(
        icon = if (failed) Icons.Outlined.SearchOff else Icons.Outlined.Search,
        active = wip,
        isLast = isLast,
        failed = failed && !interrupted,
        onClick = if (clickable) ({ onOpenSources(step.results) }) else null,
    ) {
        val style = MaterialTheme.typography.bodyMedium
        when {
            wip && step.queries.isNotEmpty() -> Row {
                Text(stringResource(R.string.tool_search_intro) + " ", style = style, color = muted)
                Text(
                    step.queries[tick % step.queries.size],
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.shimmer(true),
                )
            }
            wip -> Text(
                stringResource(if (step.kind.isImageSearch()) R.string.image_searching else R.string.searching),
                style = style,
                color = muted,
                modifier = Modifier.shimmer(true),
            )
            interrupted -> Text(stringResource(R.string.search_stopped), style = style, color = muted)
            failed -> Text(stringResource(R.string.tool_search_fail), style = style, color = muted)
            count == 0 -> Text(
                stringResource(if (step.kind.isDeepSearch()) R.string.tool_search_no_result else R.string.message_search_no_result),
                style = style,
                color = muted,
            )
            else -> Text(
                pluralStringResource(
                    if (step.kind.isDeepSearch()) R.plurals.tool_search_results else R.plurals.message_search_results,
                    count,
                    count,
                ),
                style = style,
                color = muted,
            )
        }
    }
}

@Composable
private fun PageChip(page: PageItem) {
    val uriHandler = LocalUriHandler.current
    val failed = page.status == ToolStatus.Failed
    Surface(
        onClick = { if (page.url.isNotEmpty()) uriHandler.openUri(page.url) },
        enabled = !failed && page.url.isNotEmpty(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                page.title,
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(12.dp),
            )
        }
    }
}

/** 边想边搜打开的网页：“浏览 N 个页面”+ 最多 5 个页面 */
@Composable
private fun OpenRow(step: Step.Open, sources: List<Source>, isLast: Boolean, onOpenSources: (List<Source>) -> Unit) {
    val allFailed = step.pages.all { it.status == ToolStatus.Failed }
    val count = step.pages.size
    TimelineRow(if (allFailed) Icons.Outlined.WarningAmber else Icons.Outlined.Public, active = false, isLast = isLast, failed = allFailed) {
        if (allFailed) {
            Text(stringResource(R.string.tool_open_fail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ChipFlow {
                Text(
                    pluralStringResource(R.plurals.tool_open_intro, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                step.pages.take(5).forEach { PageChip(it) }
                if (count > 5) {
                    Surface(
                        onClick = { onOpenSources(sources.filter { source -> step.pages.any { it.url == source.url } }) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            stringResource(R.string.tool_open_view_all),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 页内查找：“查找关键词”+ 关键词标签 */
@Composable
private fun FindRow(step: Step.Find, isLast: Boolean) {
    val allFailed = step.patterns.all { it.status == ToolStatus.Failed }
    TimelineRow(if (allFailed) Icons.Outlined.WarningAmber else Icons.Outlined.Public, active = false, isLast = isLast, failed = allFailed) {
        if (allFailed) {
            Text(stringResource(R.string.tool_find_fail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ChipFlow {
                Text(stringResource(R.string.tool_find_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                step.patterns.forEach { FindChip(it) }
            }
        }
    }
}

@Composable
private fun FindChip(item: FindItem) {
    val failed = item.status == ToolStatus.Failed
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.status != ToolStatus.Wip) {
                Icon(
                    if (failed) Icons.Outlined.Close else Icons.Outlined.Check,
                    contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp).size(12.dp),
                )
            }
            Text(item.pattern, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
