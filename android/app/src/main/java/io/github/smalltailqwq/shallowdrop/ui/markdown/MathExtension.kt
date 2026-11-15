package io.github.smalltailqwq.shallowdrop.ui.markdown

import org.commonmark.node.Block
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.parser.Parser
import org.commonmark.parser.SourceLine
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

/**
 * LLM 风格公式的 commonmark 扩展，对照 micromark-extension-llm-math 3.1.0（Web 版使用 singleDollarTextMath）：
 *
 * 块级（flow，可打断段落）：
 * - `$$`（≥2 个）起始行：其后不能再出现 `$`，否则按行内处理；内容直到只含 ≥ 同样数量 `$` 的行，未闭合则延续到容器末尾。
 *   与 Web 版的差异：起始行 `$$` 之后的文字（micromark 视为 meta 并丢弃）这里并入公式内容。
 * - `\[` 起始行：内容直到某行以 `\]`（后面只有空白）结尾；同一行内即可闭合（`\[ x \]`）。
 *   与 Web 版的差异：若同一行里 `\]` 之后还有文字（如 `\[1\] 参考`），不作为块级公式，交给行内解析。
 *
 * 行内（text）：
 * - `\( … \)`、`\[ … \]`：前一个字符不能是 `\`；内容里 `\\` 成对跳过；找不到闭合则不是公式。
 * - `$ … $`、`$$ … $$`：闭合序列长度必须与起始相同，长度不同的 `$` 串属于内容；前一个字符不能是 `$`。
 *   单个 `$` 额外防货币误判（Web 版没有这一层）：起始 `$` 后不能是空白，遇到的第一个单 `$` 即闭合候选，
 *   它前面不能是空白、后面不能紧跟数字，否则整个起始 `$` 按普通文字处理。
 *   例：`$5 和 $10`、`$5-$10`、`价格$5，或者$6` 都不会被当成公式。
 *
 * 公式内容不做 Markdown 转义处理，保留原文（去掉首尾空白）。
 */
internal class MathExtension private constructor() : Parser.ParserExtension {
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.customBlockParserFactory(MathBlockParserFactory())
        parserBuilder.customInlineContentParserFactory(DollarMathParser.Factory)
        parserBuilder.customInlineContentParserFactory(TexMathParser.Factory)
    }

    companion object {
        fun create(): MathExtension = MathExtension()
    }
}

/** 块级公式节点 */
internal class MathBlockNode : CustomBlock() {
    var tex: String = ""
}

/** 行内公式节点；display 表示使用了 `$$` 或 `\[` 定界 */
internal class MathInlineNode(val tex: String, val display: Boolean) : CustomNode()

// ---------------------------------------------------------------- 块级

private val TEX_BLOCK_CLOSE = Regex("""\\]\s*$""")

private class MathBlockParserFactory : AbstractBlockParserFactory() {
    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
        if (state.indent >= 4) return BlockStart.none()
        val line = state.line.content
        val start = state.nextNonSpaceIndex
        if (start >= line.length) return BlockStart.none()
        val indent = state.indent
        return when (line[start]) {
            '$' -> {
                var n = 0
                while (start + n < line.length && line[start + n] == '$') n++
                if (n < 2) return BlockStart.none()
                val rest = line.subSequence(start + n, line.length).toString()
                if ('$' in rest) return BlockStart.none()
                val parser = MathBlockParser(dollar = true, fenceLength = n, fenceIndent = indent)
                if (rest.isNotBlank()) parser.append(rest)
                BlockStart.of(parser).atIndex(line.length)
            }

            '\\' -> {
                if (start + 1 >= line.length || line[start + 1] != '[') return BlockStart.none()
                val rest = line.subSequence(start + 2, line.length).toString()
                val close = TEX_BLOCK_CLOSE.find(rest)
                // `\]` 出现在行中而不在行尾：更像行内 `\[…\]`，不开块
                if (close == null && rest.contains("\\]")) return BlockStart.none()
                val parser = MathBlockParser(dollar = false, fenceLength = 0, fenceIndent = indent)
                if (close != null) {
                    parser.append(rest.substring(0, close.range.first))
                    parser.finished = true
                } else if (rest.isNotBlank()) {
                    parser.append(rest)
                }
                BlockStart.of(parser).atIndex(line.length)
            }

            else -> BlockStart.none()
        }
    }
}

private class MathBlockParser(
    private val dollar: Boolean,
    private val fenceLength: Int,
    private val fenceIndent: Int,
) : AbstractBlockParser() {
    private val lines = StringBuilder()
    private var hasLine = false
    private val node = MathBlockNode()

    /** 起始行已闭合（单行 `\[ … \]`） */
    var finished = false

    fun append(text: CharSequence) {
        if (hasLine) lines.append('\n')
        lines.append(text)
        hasLine = true
    }

    override fun getBlock(): Block = node

    override fun tryContinue(state: ParserState): BlockContinue? {
        if (finished) return BlockContinue.none()
        val line = state.line.content
        val nonSpace = state.nextNonSpaceIndex
        if (dollar) {
            if (nonSpace < line.length && line[nonSpace] == '$') {
                var n = 0
                while (nonSpace + n < line.length && line[nonSpace + n] == '$') n++
                if (n >= fenceLength && line.subSequence(nonSpace + n, line.length).isBlank()) {
                    return BlockContinue.finished()
                }
            }
        } else {
            val content = stripIndent(line.subSequence(state.index, line.length).toString())
            val close = TEX_BLOCK_CLOSE.find(content)
            if (close != null) {
                append(content.substring(0, close.range.first))
                return BlockContinue.finished()
            }
        }
        // 内容行去掉不超过起始缩进的空格（与围栏代码块一致）
        var index = state.index
        var remaining = fenceIndent
        while (remaining > 0 && index < line.length && line[index] == ' ') {
            index++
            remaining--
        }
        return BlockContinue.atIndex(index)
    }

    private fun stripIndent(content: String): String {
        var i = 0
        while (i < fenceIndent && i < content.length && content[i] == ' ') i++
        return content.substring(i)
    }

    /** 起始行的剩余部分已在 tryStart 里处理，这里收到的是空串，跳过 */
    private var openingLineSkipped = false

    override fun addLine(line: SourceLine) {
        if (!openingLineSkipped) {
            openingLineSkipped = true
            return
        }
        append(line.content)
    }

    override fun closeBlock() {
        node.tex = lines.toString().trim()
    }
}

// ---------------------------------------------------------------- 行内

/** `$…$` 与 `$$…$$` */
private class DollarMathParser : InlineContentParser {
    override fun tryParse(inlineParserState: InlineParserState): ParsedInline? {
        val scanner = inlineParserState.scanner()
        val start = scanner.position()
        if (scanner.peekPreviousCodePoint() == '$'.code) return ParsedInline.none()
        val open = scanner.matchMultiple('$')
        val single = open == 1
        if (single && (scanner.peek() == Scanner.END || scanner.peek().isWhitespace())) {
            scanner.setPosition(start)
            return ParsedInline.none()
        }
        val contentStart = scanner.position()
        var last = '$'
        while (scanner.hasNext()) {
            val c = scanner.peek()
            if (c == '$') {
                val closeStart = scanner.position()
                val run = scanner.matchMultiple('$')
                if (run == open) {
                    if (single && (last.isWhitespace() || scanner.peek().isDigit())) break
                    val tex = scanner.getSource(contentStart, closeStart).content.trim()
                    if (tex.isEmpty()) break
                    return ParsedInline.of(MathInlineNode(tex, display = !single), scanner.position())
                }
                // 单 `$` 起始时，遇到的第一个 `$` 串就是闭合候选；长度不符说明不是公式
                if (single) break
                last = '$'
                continue
            }
            last = c
            scanner.next()
        }
        scanner.setPosition(start)
        return ParsedInline.none()
    }

    object Factory : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf('$')

        override fun create(): InlineContentParser = DollarMathParser()
    }
}

/** `\(…\)` 与 `\[…\]`；优先于核心的反斜杠转义解析器 */
private class TexMathParser : InlineContentParser {
    override fun tryParse(inlineParserState: InlineParserState): ParsedInline? {
        val scanner = inlineParserState.scanner()
        val start = scanner.position()
        if (scanner.peekPreviousCodePoint() == '\\'.code) return ParsedInline.none()
        scanner.next() // `\`
        val openChar = scanner.peek()
        val closeChar = when (openChar) {
            '(' -> ')'
            '[' -> ']'
            else -> {
                scanner.setPosition(start)
                return ParsedInline.none()
            }
        }
        scanner.next()
        val contentStart = scanner.position()
        while (scanner.hasNext()) {
            if (scanner.peek() == '\\') {
                val closeStart = scanner.position()
                scanner.next()
                when (scanner.peek()) {
                    closeChar -> {
                        scanner.next()
                        val tex = scanner.getSource(contentStart, closeStart).content.trim()
                        return ParsedInline.of(MathInlineNode(tex, display = openChar == '['), scanner.position())
                    }
                    // `\\` 成对作为内容
                    '\\' -> scanner.next()
                }
                continue
            }
            scanner.next()
        }
        scanner.setPosition(start)
        return ParsedInline.none()
    }

    object Factory : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf('\\')

        override fun create(): InlineContentParser = TexMathParser()
    }
}
