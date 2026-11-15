package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source

/**
 * 消息正文的 Markdown 渲染入口。markdown 中引用处为 `[citation:N]`，sources 按编号排列。
 * 集成时把 [PlainMarkdownStub] 替换为 `ui.markdown.MarkdownText(markdown, citations, modifier)`；
 * 文字样式通过 LocalTextStyle / LocalContentColor 传入（思考块为小号灰字）。
 */
@Composable
fun MessageMarkdown(markdown: String, sources: List<Source>, modifier: Modifier = Modifier) {
    val citations = remember(sources) {
        sources.map { TextCitation(url = it.url, title = it.title.takeIf { title -> title != it.url }) }
    }
    PlainMarkdownStub(markdown, citations, modifier)
}

private val CITATION = Regex("""\[citation:(\d+)]""")
private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

/**
 * 临时渲染：段落、标题、列表、代码块和行内粗体 / 代码，引用显示为上标编号。
 * 仅供开发期查看界面，W3 的 MarkdownText 就绪后删除。
 */
@Composable
fun PlainMarkdownStub(markdown: String, citations: List<TextCitation>, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { splitBlocks(markdown) }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val citationColor = MaterialTheme.colorScheme.primary
    SelectionContainer(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) {
                when (block) {
                    is MdBlock.Code -> Text(
                        block.code,
                        style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = LocalTextStyle.current.fontSize * 0.88f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(codeBackground, RoundedCornerShape(10.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        softWrap = false,
                    )
                    is MdBlock.Heading -> Text(
                        inline(block.text, citationColor, citations.size),
                        style = LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold, fontSize = LocalTextStyle.current.fontSize * (if (block.level <= 2) 1.2f else 1.08f)),
                    )
                    is MdBlock.Paragraph -> Text(inline(block.text, citationColor, citations.size))
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock

    data class Heading(val level: Int, val text: String) : MdBlock

    data class Code(val code: String) : MdBlock
}

private fun splitBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trimEnd())
        paragraph.clear()
    }
    val lines = markdown.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                // 流式时代码块可能尚未闭合
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += MdBlock.Code(code.toString())
            }
            line.startsWith("#") -> {
                flush()
                val level = line.takeWhile { it == '#' }.length
                blocks += MdBlock.Heading(level, line.drop(level).trim())
            }
            line.isBlank() -> flush()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                val trimmed = line.trimStart()
                paragraph.append(
                    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) "•  " + trimmed.drop(2) else line,
                )
            }
        }
        i++
    }
    flush()
    return blocks
}

private fun inline(text: String, citationColor: androidx.compose.ui.graphics.Color, citationCount: Int): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        val withCitations = CITATION.replace(text) { match ->
            val n = match.groupValues[1].toIntOrNull() ?: 0
            if (n in 1..citationCount) "\u0000$n\u0001" else ""
        }
        for (match in INLINE.findAll(withCitations)) {
            appendCitations(withCitations.substring(last, match.range.first), citationColor)
            val bold = match.groups[1]?.value
            if (bold != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendCitations(bold, citationColor) }
            } else {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
            }
            last = match.range.last + 1
        }
        appendCitations(withCitations.substring(last), citationColor)
    }

private fun AnnotatedString.Builder.appendCitations(text: String, color: androidx.compose.ui.graphics.Color) {
    var rest = text
    while (true) {
        val start = rest.indexOf('\u0000')
        if (start < 0) break
        val end = rest.indexOf('\u0001', start)
        if (end < 0) break
        append(rest.substring(0, start))
        withStyle(SpanStyle(color = color, fontSize = 0.7.em, baselineShift = BaselineShift.Superscript)) {
            append(" ")
            append(rest.substring(start + 1, end))
        }
        rest = rest.substring(end + 1)
    }
    append(rest)
}
