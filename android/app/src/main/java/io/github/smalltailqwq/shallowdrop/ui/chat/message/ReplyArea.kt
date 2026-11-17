package io.github.smalltailqwq.shallowdrop.ui.chat.message

import io.github.smalltailqwq.shallowdrop.ui.motion.MotionEaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import io.github.smalltailqwq.shallowdrop.ui.motion.pressClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
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
import androidx.compose.ui.platform.LocalDensity
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
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.siteNameOf
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
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

/** 思考区三态：收起、进行中的小窗（只露出末尾几行并自动滚动）、完全展开 */
private enum class AreaMode { Collapsed, Peek, Expanded }

/** 小窗高度 */
private val PeekHeight = 132.dp

/** 小窗顶部渐隐高度 */
private val PeekFade = 36.dp

/**
 * 思考与工具步骤时间线，对照 ReplyArea.tsx：
 * 开启深度思考时带可折叠标题（正在思考 / 正在搜索 / 已思考（用时 N 秒））；否则只有工具步骤。
 * 思考和搜索进行中、用户未操作时以小窗滚动展示，结束后自动收起；点击小窗或标题完全展开。
 */
@Composable
fun ReplyArea(
    message: Message.Assistant,
    view: ReplyView,
    streaming: Boolean,
    hasResponse: Boolean,
    onOpenSources: (List<Source>) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPages: (List<Source>) -> Unit = onOpenSources,
) {
    if (view.steps.isEmpty() && !(message.thinking && streaming)) return
    // null 表示用户未操作：跟随生成进度在小窗与收起之间切换
    var userOpen by rememberSaveable(message.id) { mutableStateOf<Boolean?>(null) }
    val titleActive = streaming && !hasResponse
    val now = rememberNow(titleActive)
    val thinking = message.thinking || view.steps.any { it is Step.Thinking }
    val title = if (thinking) areaTitle(view, streaming, now, hasResponse) else null
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val mode = when {
        title == null || userOpen == true -> AreaMode.Expanded
        userOpen == false -> AreaMode.Collapsed
        titleActive -> AreaMode.Peek
        else -> AreaMode.Collapsed
    }
    val chevronAngle by animateFloatAsState(if (mode == AreaMode.Expanded) 0f else -90f, tween(220, easing = MotionEaseOut), label = "thinking-chevron")
    val reveal = animateFloatAsState(if (mode == AreaMode.Collapsed) 0f else 1f, tween(320, easing = MotionEaseOut), label = "thinking-reveal")
    val expand = animateFloatAsState(if (mode == AreaMode.Expanded) 1f else 0f, tween(360, easing = MotionEaseOut), label = "thinking-expand")
    // 小窗内容增长时平滑上滚，而不是逐行跳动
    var contentHeight by remember { mutableIntStateOf(0) }
    val scrollHeight = animateFloatAsState(
        contentHeight.toFloat(),
        if (mode == AreaMode.Peek) spring(stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "thinking-scroll",
    )
    val peekPx = with(LocalDensity.current) { PeekHeight.toPx() }
    val fadePx = with(LocalDensity.current) { PeekFade.toPx() }

    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .pressClickable { userOpen = mode != AreaMode.Expanded }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    DsIcon(R.drawable.ic_think_outline_16, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    title.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.weight(1f, fill = false).shimmer(titleActive),
                )
                Spacer(Modifier.width(4.dp))
                // 对照 ReplyArea.tsx：展开时向下，收起时向右
                DsIcon(
                    R.drawable.ic_chevron_down_outline_14,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(14.dp).rotate(chevronAngle),
                )
            }
        }
        if (mode != AreaMode.Collapsed || reveal.value > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        val full = placeable.height.toFloat()
                        val e = expand.value
                        val scrolled = scrollHeight.value.coerceAtMost(full)
                        val peek = minOf(scrolled, peekPx)
                        val visible = (peek + (full - peek) * e) * reveal.value
                        // 小窗贴底展示最新内容；展开过程中逐渐回到顶部对齐
                        val offset = ((scrolled - visible).coerceAtLeast(0f) * (1f - e)).roundToInt()
                        layout(placeable.width, visible.roundToInt()) { placeable.placeRelative(0, -offset) }
                    }
                    .graphicsLayer {
                        alpha = reveal.value
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // 小窗内容溢出时顶部渐隐
                        val strength = if (scrollHeight.value > peekPx) 1f - expand.value else 0f
                        if (strength > 0f) {
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 1f - strength),
                                    1f to Color.Black,
                                    startY = 0f,
                                    endY = fadePx,
                                ),
                                size = Size(size.width, fadePx),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
            ) {
                Column(
                    Modifier
                        .padding(top = 4.dp)
                        .onSizeChanged { contentHeight = it.height },
                ) {
                    view.steps.forEachIndexed { index, step ->
                        StepItem(
                            step = step,
                            message = message,
                            sources = view.sources,
                            streaming = streaming,
                            isLast = index == view.steps.lastIndex,
                            onOpenSources = onOpenSources,
                            onOpenPages = onOpenPages,
                        )
                    }
                }
                // 小窗阶段整体作为展开入口，避免误触内部网页标签
                if (mode == AreaMode.Peek) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { userOpen = true },
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
    onOpenPages: (List<Source>) -> Unit,
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
                    MessageMarkdown(step.blocks.joinToString("") { it.thinking }, emptyList(), streaming = active)
                }
            }
        }
        is Step.Preamble -> TimelineRow(icon = null, active = false, isLast = isLast) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                MessageMarkdown(step.markdown, sources)
            }
        }
        is Step.Search -> SearchRow(step, message, streaming, isLast, onOpenSources)
        is Step.Open -> OpenRow(step, sources, isLast, onOpenPages)
        is Step.Find -> FindRow(step, isLast)
        is Step.ReadLink -> TimelineRow(
            R.drawable.ic_link_outline_16,
            active = true,
            isLast = isLast,
            onClick = if (step.links.isNotEmpty()) ({ onOpenPages(pageSources(step.links, sources)) }) else null,
        ) {
            Text(
                stringResource(R.string.reading_link),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.shimmer(true),
            )
        }
    }
}

/** 时间线行：左侧图标（或圆点）与贯穿的竖线，右侧内容。 */
@Composable
private fun TimelineRow(
    @DrawableRes icon: Int?,
    active: Boolean,
    isLast: Boolean,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val line = LocalExtraColors.current.thinkingLine
    val headerHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    val bottomGap = if (isLast) 4.dp else 14.dp
    // 点击反馈只包图标和内容；竖线与行间距位于独立绘制层。
    Box(Modifier.fillMaxWidth()) {
        if (!isLast) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    val segment = if (onClick != null) Modifier.height(bottomGap)
                        else Modifier.padding(top = headerHeight / 2 + 11.dp).fillMaxHeight()
                    Box(segment.width(1.dp).background(line))
                }
            }
        }
        Row(
            Modifier
                .padding(bottom = bottomGap)
                .then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).pressClickable(onClick = onClick).heightIn(min = 48.dp) else Modifier),
            verticalAlignment = if (onClick != null) Alignment.CenterVertically else Alignment.Top,
        ) {
            Box(Modifier.width(24.dp).height(headerHeight), contentAlignment = Alignment.Center) {
                if (icon != null) {
                    DsIcon(
                        icon,
                        contentDescription = null,
                        tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) MaterialTheme.colorScheme.primary else line))
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f, fill = false)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
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
        icon = if (failed) R.drawable.ic_search_warning_outline_16 else R.drawable.ic_search_outline_16,
        active = wip,
        isLast = isLast,
        failed = failed && !interrupted,
        onClick = if (clickable) ({ onOpenSources(step.results) }) else null,
    ) {
        val style = MaterialTheme.typography.bodyMedium
        when {
            wip && step.queries.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.tool_search_intro), style = style, color = muted, modifier = Modifier.alignByBaseline())
                Text(
                    step.queries[tick % step.queries.size],
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alignByBaseline().shimmer(true),
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

private fun pageSources(pages: List<PageItem>, sources: List<Source>): List<Source> {
    val byUrl = sources.associateBy { it.url }
    return pages.map { page ->
        val source = byUrl[page.url] ?: Source(
            url = page.url,
            title = page.title.ifEmpty { page.url },
            siteName = siteNameOf(page.url),
            publishedAt = null,
            snippet = null,
            siteIcon = null,
            index = 0,
        )
        source.copy(status = page.status)
    }
}

/** 边想边搜打开的网页：计数入口按需展开完整页面列表。 */
@Composable
private fun OpenRow(step: Step.Open, sources: List<Source>, isLast: Boolean, onOpenPages: (List<Source>) -> Unit) {
    val allFailed = step.pages.isNotEmpty() && step.pages.all { it.status == ToolStatus.Failed }
    val wip = step.pages.any { it.status == ToolStatus.Wip }
    val count = step.pages.size
    TimelineRow(
        if (allFailed) R.drawable.ic_browse_warning_outline_16 else R.drawable.ic_browse_outline_16,
        active = wip,
        isLast = isLast,
        failed = allFailed,
        onClick = if (count > 0) ({ onOpenPages(pageSources(step.pages, sources)) }) else null,
    ) {
        Text(
            if (allFailed) stringResource(R.string.tool_open_fail) else pluralStringResource(R.plurals.tool_open_intro, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.shimmer(wip),
        )
    }
}

/** 页内查找：“查找关键词”+ 关键词标签 */
@Composable
private fun FindRow(step: Step.Find, isLast: Boolean) {
    val allFailed = step.patterns.all { it.status == ToolStatus.Failed }
    TimelineRow(if (allFailed) R.drawable.ic_browse_warning_outline_16 else R.drawable.ic_browse_outline_16, active = false, isLast = isLast, failed = allFailed) {
        if (allFailed) {
            Text(stringResource(R.string.tool_find_fail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tool_find_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipFlow {
                    step.patterns.forEach { FindChip(it) }
                }
            }
        }
    }
}

@Composable
private fun FindChip(item: FindItem) {
    val failed = item.status == ToolStatus.Failed
    Surface(modifier = Modifier.widthIn(max = 240.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.status != ToolStatus.Wip) {
                DsIcon(
                    if (failed) R.drawable.ic_close_fill_14 else R.drawable.ic_checked_outline_14,
                    contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp).size(12.dp),
                )
            }
            Text(item.pattern, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
}
