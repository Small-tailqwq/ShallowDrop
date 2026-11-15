package io.github.smalltailqwq.shallowdrop.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import kotlin.math.pow

/**
 * 渲染上下文：样式、引用列表与回调。实例在 MarkdownText 内 remember，回调经 rememberUpdatedState 读取，
 * 回调变化不会让已构建的 AnnotatedString 失效。
 */
@Stable
internal class MarkdownContext(
    val style: MarkdownStyle,
    /** `[citation:N]` 对应 citations[N - 1]；为空时角标原样显示为文字 */
    val citations: List<TextCitation>,
    private val linkHandler: () -> (String) -> Unit,
    private val citationHandler: () -> (TextCitation) -> Unit,
) {
    fun openLink(url: String) = linkHandler()(url)

    fun openCitation(citation: TextCitation) = citationHandler()(citation)
}

internal class InlineText(val text: AnnotatedString, val inlineContent: Map<String, InlineTextContent>)

private const val CITATION_PREFIX = "cite:"

/** 行内内容 → AnnotatedString；引用角标为可点击的内嵌组件 */
internal fun buildInlineText(inlines: List<MdInline>, context: MarkdownContext): InlineText {
    val citationIndexes = LinkedHashSet<Int>()
    val text = buildAnnotatedString { appendInlines(inlines, context, citationIndexes) }
    val content = citationIndexes.associate { index ->
        val citation = context.citations[index - 1]
        val digits = index.toString().length
        "$CITATION_PREFIX$index" to InlineTextContent(
            Placeholder(
                width = (0.55 * digits + 0.7).em,
                height = 1.15.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            CitationBadge(index, context.style) { context.openCitation(citation) }
        }
    }
    return InlineText(text, content)
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MdInline>,
    context: MarkdownContext,
    citations: MutableSet<Int>,
) {
    for (inline in inlines) appendInline(inline, context, citations)
}

private fun AnnotatedString.Builder.appendInline(
    inline: MdInline,
    context: MarkdownContext,
    citations: MutableSet<Int>,
) {
    val style = context.style
    when (inline) {
        is MdInline.Text -> append(inline.text)
        is MdInline.LineBreak -> append('\n')
        is MdInline.Code -> withStyle(style.inlineCode) { append(" ${inline.code} ") }
        is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(inline.children, context, citations) }
        is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInlines(inline.children, context, citations) }
        is MdInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlines(inline.children, context, citations)
        }
        is MdInline.Link -> {
            val url = inline.url
            if (url == null) {
                appendInlines(inline.children, context, citations)
            } else {
                val link = LinkAnnotation.Clickable(
                    tag = url,
                    styles = TextLinkStyles(style = style.link),
                    linkInteractionListener = { context.openLink(url) },
                )
                withLink(link) { appendInlines(inline.children, context, citations) }
            }
        }
        is MdInline.Math -> appendMath(TexToUnicode.convert(inline.tex), style.math)
        is MdInline.Citation -> when {
            context.citations.isEmpty() -> append("[citation:${inline.index}]")
            inline.index in 1..context.citations.size -> {
                citations += inline.index
                appendInlineContent("$CITATION_PREFIX${inline.index}", "[${inline.index}]")
            }
            // 找不到来源的角标直接去掉（与 Web 版一致）
            else -> Unit
        }
    }
}

/** 公式文字：衬线体，变量斜体；上下标逐层缩小并偏移基线 */
internal fun AnnotatedString.Builder.appendMath(spans: List<MathSpan>, base: SpanStyle) {
    withStyle(base) {
        for (span in spans) {
            val level = span.scripts.length
            val scale = SCRIPT_SCALE.pow(level)
            // 期望的绝对偏移 = Σ 每层方向 × 系数 × 上一层字号；BaselineShift 以当前（已缩小）字号为单位
            var shift = 0.0
            span.scripts.forEachIndexed { i, c ->
                val parentScale = SCRIPT_SCALE.pow(i)
                shift += (if (c == '^') SUP_SHIFT else -SUB_SHIFT) * parentScale
            }
            val spanStyle = SpanStyle(
                fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontSize = if (level > 0) scale.em else androidx.compose.ui.unit.TextUnit.Unspecified,
                baselineShift = if (level > 0) BaselineShift((shift / scale).toFloat()) else null,
            )
            withStyle(spanStyle) { append(span.text) }
        }
    }
}

private const val SCRIPT_SCALE = 0.72
private const val SUP_SHIFT = 0.45
private const val SUB_SHIFT = 0.2

@Composable
private fun CitationBadge(index: Int, style: MarkdownStyle, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.citationBackground, RoundedCornerShape(percent = 40))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = index.toString(),
            style = style.citationText,
            color = style.citationContent,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** 段落、标题、表格单元格共用的行内文字 */
@Composable
internal fun MarkdownInlineText(
    inlines: List<MdInline>,
    context: MarkdownContext,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val built = remember(inlines, context) { buildInlineText(inlines, context) }
    Text(
        text = built.text,
        modifier = modifier,
        style = if (textAlign != null) textStyle.copy(textAlign = textAlign) else textStyle,
        inlineContent = built.inlineContent,
    )
}
