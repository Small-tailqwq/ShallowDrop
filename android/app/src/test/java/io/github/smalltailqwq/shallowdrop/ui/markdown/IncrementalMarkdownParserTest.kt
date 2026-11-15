package io.github.smalltailqwq.shallowdrop.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IncrementalMarkdownParserTest {
    private val sample = """
        # 标题

        第一段，含 **粗体** 与 \( x^2 \)[citation:1]。
        续行

        - 列表一
          - 嵌套
        - 列表二

        | a | b |
        |---|---|
        | 1 | 2 |

        ```python
        def f(x):
            return x * 2
        ```

        $$
        \int_0^1 x\,dx
        $$

        > 引用
        > 多行

        Setext 标题
        ===

        \[
        a = b
        \]

        1. 一
        2. 二

        结尾段落，价格 $5 到 $10。
    """.trimIndent()

    /** 逐字符流式喂入，每一步结果都应与整篇解析一致 */
    @Test
    fun streamingCharByCharMatchesFullParse() {
        val parser = IncrementalMarkdownParser()
        for (end in 1..sample.length) {
            val text = sample.substring(0, end)
            val expected = MarkdownParser.parse(text).blocks
            val actual = parser.parse(text).blocks
            val diff = expected.indices.firstOrNull { expected[it] != actual.getOrNull(it) }
            assertEquals("前缀长度 $end：${text.takeLast(20)}｜第 $diff 块 ${diff?.let { actual.getOrNull(it) }}", expected, actual)
        }
    }

    @Test
    fun streamingInChunksMatchesFullParse() {
        val parser = IncrementalMarkdownParser()
        var end = 0
        var step = 1
        while (end < sample.length) {
            end = minOf(sample.length, end + step)
            step = step % 7 + 1
            val text = sample.substring(0, end)
            assertEquals(MarkdownParser.parse(text), parser.parse(text))
        }
    }

    @Test
    fun earlierBlocksReuseInstances() {
        val parser = IncrementalMarkdownParser()
        val first = parser.parse("# 标题\n\n段落一\n\n段落二\n\n段落")
        val second = parser.parse("# 标题\n\n段落一\n\n段落二\n\n段落三继续")
        assertSame(first.blocks[0], second.blocks[0])
        assertSame(first.blocks[1], second.blocks[1])
        assertEquals(MdBlock.Paragraph(listOf(MdInline.Text("段落三继续"))), second.blocks[3])
    }

    @Test
    fun nonAppendReparsesAndReusesEqualBlocks() {
        val parser = IncrementalMarkdownParser()
        val first = parser.parse("段落一\n\n段落二")
        val second = parser.parse("段落一\n\n另一段")
        assertSame(first.blocks[0], second.blocks[0])
        assertEquals(MdBlock.Paragraph(listOf(MdInline.Text("另一段"))), second.blocks[1])
    }

    @Test
    fun sameTextReturnsSameDocument() {
        val parser = IncrementalMarkdownParser()
        val a = parser.parse("abc")
        assertSame(a, parser.parse("abc"))
    }

    @Test
    fun paragraphTurnsIntoTableWhenDelimiterArrives() {
        val parser = IncrementalMarkdownParser()
        parser.parse("前文\n\n| a | b |")
        val doc = parser.parse("前文\n\n| a | b |\n|---|---|")
        assertEquals(MarkdownParser.parse("前文\n\n| a | b |\n|---|---|"), doc)
        assert(doc.blocks[1] is MdBlock.Table)
    }
}
