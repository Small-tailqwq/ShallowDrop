package io.github.smalltailqwq.shallowdrop.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Markdown 正文渲染。
 *
 * - 引用：正文里的 `[citation:N]` 渲染为可点击角标，对应 `citations[N - 1]`（N 从 1 开始，
 *   与 Web 版 `buildReplyView` 的来源编号一致：调用方按编号顺序传入去重后的来源）；找不到对应来源的角标去掉。
 *   citations 为空时不处理角标，按原文显示（与 Web 版不传 sources 时一致）。
 * - streaming 为 true 时按渲染耗时节流重解析（对照 Web 版 useThrottleMarkdownRender），并做顶层块增量解析，
 *   未变化的块不重组；结束后整篇解析一次。
 * - 文字可选择；颜色与字体取自 MaterialTheme，正文颜色跟随 LocalContentColor。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    citations: List<TextCitation> = emptyList(),
    streaming: Boolean = false,
    onCitationClick: (TextCitation) -> Unit = {},
    onLinkClick: (String) -> Unit = {},
) {
    val document = rememberMarkdownDocument(markdown, streaming)
    val style = rememberMarkdownStyle()
    val linkHandler by rememberUpdatedState(onLinkClick)
    val citationHandler by rememberUpdatedState(onCitationClick)
    val context = remember(style, citations) {
        MarkdownContext(style, citations, { linkHandler }, { citationHandler })
    }
    if (document.blocks.isEmpty()) return
    SelectionContainer(modifier) {
        MarkdownBlocks(document.blocks, context, Modifier.fillMaxWidth())
    }
}

/** 两次更新的最长间隔 */
private const val MAX_WAIT_MS = 2000L

/** 上次渲染耗时的倍数，过了这段时间才允许下次更新 */
private const val TIME_CONSUMING_RATE = 2

/**
 * 流式时：每次更新记录开始时间，解析（后台线程）并等到用上新文档的那一帧后得到耗时，
 * 再等待 耗时 × TIME_CONSUMING_RATE（不超过 MAX_WAIT_MS）；期间的新内容合并为最新一份。
 * 非流式时同步解析（带缓存）。
 */
@Composable
private fun rememberMarkdownDocument(markdown: String, streaming: Boolean): MdDocument {
    val parser = remember { IncrementalMarkdownParser() }
    val latest = rememberUpdatedState(markdown)
    var throttled by remember { mutableStateOf<MdDocument?>(null) }
    if (!streaming) {
        // 丢弃上一轮流式的节流结果，避免再次流式时先闪出旧内容
        if (throttled != null) SideEffect { throttled = null }
        return remember(markdown) {
            if (parser.hasState) parser.finish(markdown) else MarkdownParser.parseCached(markdown)
        }
    }
    LaunchedEffect(parser) {
        snapshotFlow { latest.value }.conflate().collect { text ->
            val start = System.nanoTime()
            throttled = withContext(Dispatchers.Default) { parser.parse(text) }
            withFrameNanos { }
            val cost = (System.nanoTime() - start) / 1_000_000
            delay(minOf(MAX_WAIT_MS, cost * TIME_CONSUMING_RATE))
        }
    }
    // 第一帧还没有节流结果时同步解析一次
    return throttled ?: remember { parser.parse(markdown) }
}

// ------------------------------------------------------------------ 块

private val LocalListDepth = compositionLocalOf { 0 }

@Composable
internal fun MarkdownBlocks(
    blocks: List<MdBlock>,
    context: MarkdownContext,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEachIndexed { index, block ->
            MarkdownBlock(block, context, first = index == 0)
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, context: MarkdownContext, first: Boolean) {
    val style = context.style
    when (block) {
        is MdBlock.Paragraph -> MarkdownInlineText(block.inlines, context, style.body)
        is MdBlock.Heading -> MarkdownInlineText(
            inlines = block.inlines,
            context = context,
            textStyle = style.headings[block.level - 1],
            modifier = if (first || block.level > 3) Modifier else Modifier.padding(top = 8.dp),
        )
        is MdBlock.CodeBlock -> CodeBlockView(block, context)
        is MdBlock.MathBlock -> MathBlockView(block.tex, context)
        is MdBlock.BlockQuote -> {
            val bar = style.quoteBar
            MarkdownBlocks(
                blocks = block.blocks,
                context = context,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val width = 3.dp.toPx()
                        drawLine(bar, Offset(width / 2, 0f), Offset(width / 2, size.height), strokeWidth = width)
                    }
                    .padding(start = 15.dp),
            )
        }
        is MdBlock.ListBlock -> ListView(block, context)
        is MdBlock.ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = style.divider)
        is MdBlock.Table -> TableView(block, context)
    }
}

private val BULLETS = listOf("•", "◦", "▪")

@Composable
private fun ListView(list: MdBlock.ListBlock, context: MarkdownContext) {
    val style = context.style
    val depth = LocalListDepth.current
    val fontSize = style.body.fontSize.takeIf { it.isSpecified } ?: 16.sp
    val digits = if (list.ordered) (list.start + list.items.size - 1).toString().length else 1
    val markerWidth = with(LocalDensity.current) { (fontSize * (0.6f * digits + 0.9f)).toDp() }
    CompositionLocalProvider(LocalListDepth provides depth + 1) {
        Column(verticalArrangement = Arrangement.spacedBy(if (list.tight) 4.dp else 8.dp)) {
            list.items.forEachIndexed { index, item ->
                val marker = when {
                    item.checked == true -> "☑"
                    item.checked == false -> "☐"
                    list.ordered -> "${list.start + index}."
                    else -> BULLETS[depth % BULLETS.size]
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = marker,
                        style = style.body,
                        color = style.secondary,
                        modifier = Modifier.width(markerWidth),
                    )
                    MarkdownBlocks(
                        blocks = item.blocks,
                        context = context,
                        modifier = Modifier.weight(1f),
                        spacing = if (list.tight) 4.dp else 8.dp,
                    )
                }
            }
        }
    }
}
