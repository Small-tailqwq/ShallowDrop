package io.github.smalltailqwq.shallowdrop.ui.markdown

import io.github.smalltailqwq.shallowdrop.ui.markdown.MdBlock.MathBlock
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdBlock.Paragraph
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Code
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Math
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Text
import org.junit.Assert.assertEquals
import org.junit.Test

class MathParseTest {
    private fun parse(md: String) = MarkdownParser.parse(md).blocks

    private fun single(md: String): MdBlock = parse(md).single()

    private fun inlines(md: String) = (single(md) as Paragraph).inlines

    // ---------------------------------------------------------------- 行内

    @Test
    fun texParenInline() {
        assertEquals(listOf(Text("设 "), Math("x^2 + y^2 = 1", false), Text(" 成立")), inlines("设 \\( x^2 + y^2 = 1 \\) 成立"))
    }

    @Test
    fun texBracketInlineMidParagraph() {
        assertEquals(listOf(Text("其中 "), Math("a_b", true), Text(" 是")), inlines("其中 \\[a_b\\] 是"))
    }

    @Test
    fun texInlineKeepsBackslashes() {
        // 公式内容不受 Markdown 转义与强调影响
        assertEquals(listOf(Math("\\frac{a_1}{b_*} \\\\ c", false)), inlines("\\(\\frac{a_1}{b_*} \\\\ c\\)"))
    }

    @Test
    fun escapedBackslashIsNotMath() {
        assertEquals(listOf(Text("\\(x)")), inlines("\\\\(x)"))
    }

    @Test
    fun unclosedTexInlineIsText() {
        // 未闭合：反斜杠转义按 CommonMark 处理
        assertEquals(listOf(Text("(x + 1")), inlines("\\(x + 1"))
    }

    @Test
    fun singleDollarInline() {
        assertEquals(listOf(Text("面积 "), Math("\\pi r^2", false), Text(" 平方米")), inlines("面积 \$\\pi r^2\$ 平方米"))
        assertEquals(listOf(Math("x", false), Text("和"), Math("y", false)), inlines("\$x\$和\$y\$"))
    }

    @Test
    fun doubleDollarInline() {
        assertEquals(listOf(Text("行内 "), Math("E=mc^2", true), Text(" 结束")), inlines("行内 \$\$E=mc^2\$\$ 结束"))
    }

    @Test
    fun dollarRunsOfOtherLengthAreContent() {
        assertEquals(listOf(Text("x "), Math("a \$ b", true)), inlines("x \$\$a \$ b\$\$"))
    }

    @Test
    fun currencyIsNotMath() {
        assertEquals(listOf(Text("价格 \$5 到 \$10 之间")), inlines("价格 \$5 到 \$10 之间"))
        assertEquals(listOf(Text("\$5-\$10")), inlines("\$5-\$10"))
        assertEquals(listOf(Text("价格\$5，或者\$6")), inlines("价格\$5，或者\$6"))
        assertEquals(listOf(Text("花了 \$3.50。")), inlines("花了 \$3.50。"))
        assertEquals(listOf(Text("US\$ 100")), inlines("US\$ 100"))
        assertEquals(listOf(Text("\$ 5 \$")), inlines("\$ 5 \$"))
    }

    @Test
    fun currencyFollowedByRealMath() {
        assertEquals(listOf(Text("\$5 和 "), Math("x", false)), inlines("\$5 和 \$x\$"))
    }

    @Test
    fun escapedDollar() {
        assertEquals(listOf(Text("\$x\$")), inlines("\\\$x\\\$"))
    }

    @Test
    fun mathInsideCodeSpanIgnored() {
        assertEquals(listOf(Code("\$x\$ \\(y\\)")), inlines("`\$x\$ \\(y\\)`"))
    }

    @Test
    fun inlineMathSpansSoftBreak() {
        assertEquals(listOf(Math("a +\nb", false)), inlines("\\(a +\nb\\)"))
    }

    @Test
    fun emphasisAroundMath() {
        assertEquals(
            listOf(MdInline.Strong(listOf(Text("注意 "), Math("a*b", false)))),
            inlines("**注意 \$a*b\$**"),
        )
    }

    // ---------------------------------------------------------------- 块级

    @Test
    fun dollarBlock() {
        assertEquals(MathBlock("\\sum_{i=1}^n i"), single("\$\$\n\\sum_{i=1}^n i\n\$\$"))
    }

    @Test
    fun dollarBlockInterruptsParagraph() {
        val blocks = parse("前文\n\$\$\nx\n\$\$\n后文")
        assertEquals(listOf(Paragraph(listOf(Text("前文"))), MathBlock("x"), Paragraph(listOf(Text("后文")))), blocks)
    }

    @Test
    fun dollarBlockWithContentOnOpeningLine() {
        assertEquals(MathBlock("a = 1\nb = 2"), single("\$\$ a = 1\nb = 2\n\$\$"))
    }

    @Test
    fun singleLineDoubleDollarParagraphPromotedToBlock() {
        assertEquals(MathBlock("E=mc^2"), single("\$\$E=mc^2\$\$"))
        assertEquals(MathBlock("E=mc^2"), single("\$\$ E=mc^2 \$\$"))
    }

    @Test
    fun texBracketBlock() {
        assertEquals(MathBlock("\\int_0^1 x\\,dx"), single("\\[\n\\int_0^1 x\\,dx\n\\]"))
    }

    @Test
    fun texBracketBlockSingleLine() {
        val blocks = parse("\\[ a^2 + b^2 = c^2 \\]\n后文")
        assertEquals(listOf(MathBlock("a^2 + b^2 = c^2"), Paragraph(listOf(Text("后文")))), blocks)
    }

    @Test
    fun texBracketBlockClosesAtEndOfContentLine() {
        assertEquals(MathBlock("a \\\\\nb"), single("\\[\na \\\\\nb \\]"))
    }

    @Test
    fun texBracketFollowedByTextIsInline() {
        assertEquals(listOf(Math("1", true), Text(" 参考")), inlines("\\[1\\] 参考"))
    }

    @Test
    fun mathBlockInsideListAndQuote() {
        val list = single("1. 公式：\n   \$\$\n   x\n   \$\$") as MdBlock.ListBlock
        assertEquals(MathBlock("x"), list.items[0].blocks[1])
        val quote = single("> \\[\n> y\n> \\]") as MdBlock.BlockQuote
        assertEquals(listOf(MathBlock("y")), quote.blocks)
    }

    @Test
    fun matrixBlockKeepsRowBreaks() {
        val tex = "\\begin{pmatrix}\na & b \\\\\nc & d\n\\end{pmatrix}"
        assertEquals(MathBlock(tex), single("\$\$\n$tex\n\$\$"))
    }

    // ---------------------------------------------------------------- 流式未闭合

    @Test
    fun unclosedDollarBlockRunsToEnd() {
        assertEquals(MathBlock("\\frac{a}{b}\n+ c"), single("\$\$\n\\frac{a}{b}\n+ c"))
    }

    @Test
    fun unclosedTexBlockRunsToEnd() {
        assertEquals(MathBlock("x ="), single("\\[\nx ="))
    }

    @Test
    fun unclosedInlineDollarIsText() {
        assertEquals(listOf(Text("当 \$x^2")), inlines("当 \$x^2"))
    }

    @Test
    fun streamingOpenFenceOnly() {
        assertEquals(MathBlock(""), single("\$\$"))
        assertEquals(listOf(Paragraph(listOf(Text("当"))), MathBlock("")), parse("当\n\\["))
    }
}
