package io.github.smalltailqwq.shallowdrop.ui.markdown

import io.github.smalltailqwq.shallowdrop.ui.markdown.MdBlock.BlockQuote
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdBlock.CodeBlock
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdBlock.Heading
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdBlock.ListBlock
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdBlock.Paragraph
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdBlock.Table
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Citation
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Code
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Emphasis
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.LineBreak
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Link
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Strikethrough
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Strong
import io.github.smalltailqwq.shallowdrop.ui.markdown.MdInline.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private fun parse(md: String) = MarkdownParser.parse(md).blocks

    private fun single(md: String): MdBlock = parse(md).single()

    @Test
    fun headingAndInlineStyles() {
        val blocks = parse("# 标题 **粗**\n\n普通 *斜* **粗** ~~删~~ `code` 结尾")
        assertEquals(Heading(1, listOf(Text("标题 "), Strong(listOf(Text("粗"))))), blocks[0])
        assertEquals(
            Paragraph(
                listOf(
                    Text("普通 "), Emphasis(listOf(Text("斜"))), Text(" "), Strong(listOf(Text("粗"))), Text(" "),
                    Strikethrough(listOf(Text("删"))), Text(" "), Code("code"), Text(" 结尾"),
                ),
            ),
            blocks[1],
        )
    }

    @Test
    fun singleTildeIsNotStrikethrough() {
        assertEquals(Paragraph(listOf(Text("范围 5~10 和 3~4"))), single("范围 5~10 和 3~4"))
    }

    @Test
    fun strongWithSymbolsCanTouchChineseText() {
        for (content in listOf("10℃ ~ 20℃", "10°C ~ 20°C", "（重点）", "“重点”", "50%", "¥100")) {
            assertEquals(content, Paragraph(listOf(Text("前"), Strong(listOf(Text(content))), Text("后"))), single("前**$content**后"))
        }
    }

    @Test
    fun chineseStrongPreservesNestedInlineFormatting() {
        assertEquals(
            Paragraph(listOf(Text("前"), Strong(listOf(Text("（"), Code("**字面值**"), Text("与"), Emphasis(listOf(Text("重点"))), Text("）"))), Text("后"))),
            single("前**（`**字面值**`与*重点*）**后"),
        )
    }

    @Test
    fun escapedStrongMarkersAndCodeRemainLiteral() {
        assertEquals(Paragraph(listOf(Text("前**（字面值）**后"))), single("前\\**（字面值）\\**后"))
        assertEquals(Paragraph(listOf(Code("前**（字面值）**后"))), single("`前**（字面值）**后`"))
    }

    @Test
    fun chineseStrongKeepsReferenceLinksAndMath() {
        assertEquals(
            Paragraph(listOf(Text("前"), Strong(listOf(Text("（"), Link("https://example.com", listOf(Text("站点"))), Text("与"), MdInline.Math("x^2", false), Text("）"))), Text("后"))),
            parse("[站点]: https://example.com\n\n前**（[站点]与\$x^2\$）**后").last(),
        )
    }

    @Test
    fun adjacentStandardAndChineseStrongRemainSeparate() {
        assertEquals(Paragraph(listOf(Strong(listOf(Text("普通"))), Text("前"), Strong(listOf(Text("（重点）"))), Text("后"))),
            single("**普通**前**（重点）**后"))
        assertEquals(Paragraph(listOf(Text("东京是"), Strong(listOf(Text("阴天"))), Text("，气温在"), Strong(listOf(Text("10℃到17℃"))), Text("之间"))),
            single("东京是**阴天**，气温在**10℃到17℃**之间"))
    }

    @Test
    fun emptyHeadingDropped() {
        assertTrue(parse("#").isEmpty())
    }

    @Test
    fun softBreaksBecomeLineBreaks() {
        assertEquals(Paragraph(listOf(Text("a"), LineBreak, Text("b"))), single("a\nb"))
        assertEquals(Paragraph(listOf(Text("a"), LineBreak, Text("b"))), single("a<br>b"))
    }

    @Test
    fun linksAndAutolinks() {
        assertEquals(
            Paragraph(listOf(Link("https://example.com", listOf(Text("站点"))))),
            single("[站点](https://example.com)"),
        )
        // 缺协议补 https
        assertEquals(Paragraph(listOf(Link("https://example.com/a", listOf(Text("x"))))), single("[x](example.com/a)"))
        // 锚点、mailto、javascript 只显示文字
        assertEquals(Paragraph(listOf(Link(null, listOf(Text("锚"))))), single("[锚](#sec)"))
        assertEquals(Paragraph(listOf(Link(null, listOf(Text("信"))))), single("[信](mailto:a@b.c)"))
        assertEquals(Paragraph(listOf(Link(null, listOf(Text("坏"))))), single("[坏](javascript:alert(1))"))
        val auto = single("访问 https://www.deepseek.com 了解") as Paragraph
        assertEquals(Link("https://www.deepseek.com", listOf(Text("https://www.deepseek.com"))), auto.inlines[1])
    }

    @Test
    fun imageBecomesLink() {
        assertEquals(
            Paragraph(listOf(Link("https://a.com/x.png", listOf(Text("https://a.com/x.png"))))),
            single("![图](https://a.com/x.png)"),
        )
    }

    @Test
    fun fencedCodeBlock() {
        assertEquals(CodeBlock("kotlin", "val a = 1\nprintln(a)"), single("```kotlin\nval a = 1\nprintln(a)\n```"))
        assertEquals(CodeBlock("text", "plain"), single("```\nplain\n```"))
        assertEquals(CodeBlock("python", "x = 1"), single("~~~python title\nx = 1\n~~~"))
    }

    @Test
    fun indentedCodeDisabled() {
        // 关闭缩进代码块：前导空格的内容仍是段落
        assertEquals(Paragraph(listOf(Text("缩进的文字"))), single("    缩进的文字"))
    }

    @Test
    fun unclosedCodeFenceWhileStreaming() {
        assertEquals(CodeBlock("js", "const a = 1\nconst b"), single("```js\nconst a = 1\nconst b"))
    }

    @Test
    fun nestedLists() {
        val md = """
            - 一
              - 一点一
                1. 深层
            - 二

            3. 三
            4. 四
        """.trimIndent()
        val blocks = parse(md)
        val bullet = blocks[0] as ListBlock
        assertEquals(false, bullet.ordered)
        assertTrue(bullet.tight)
        assertEquals(2, bullet.items.size)
        val nested = bullet.items[0].blocks[1] as ListBlock
        assertEquals(Paragraph(listOf(Text("一点一"))), nested.items[0].blocks[0])
        val deep = nested.items[0].blocks[1] as ListBlock
        assertTrue(deep.ordered)
        assertEquals(1, deep.start)
        val ordered = blocks[1] as ListBlock
        assertEquals(3, ordered.start)
        assertEquals(2, ordered.items.size)
    }

    @Test
    fun looseList() {
        val list = single("- a\n\n- b") as ListBlock
        assertEquals(false, list.tight)
    }

    @Test
    fun taskList() {
        val list = single("- [ ] 待办\n- [x] 完成\n- 普通\n- [X]") as ListBlock
        assertEquals(false, list.items[0].checked)
        assertEquals(listOf(Paragraph(listOf(Text("待办")))), list.items[0].blocks)
        assertEquals(true, list.items[1].checked)
        assertNull(list.items[2].checked)
        assertEquals(true, list.items[3].checked)
        assertTrue(list.items[3].blocks.isEmpty())
    }

    @Test
    fun blockquoteAndRule() {
        val blocks = parse("> 引用\n> 第二行\n\n---")
        assertEquals(BlockQuote(listOf(Paragraph(listOf(Text("引用"), LineBreak, Text("第二行"))))), blocks[0])
        assertEquals(MdBlock.ThematicBreak, blocks[1])
    }

    @Test
    fun gfmTable() {
        val md = """
            | 名称 | 数量 | 说明 |
            |:-----|:----:|-----:|
            | **苹果** | 3 | `红` |
            | 梨 | 5 |
        """.trimIndent()
        val table = single(md) as Table
        assertEquals(listOf(TableAlign.Left, TableAlign.Center, TableAlign.Right), table.alignments)
        assertEquals(TableCell(listOf(Text("名称"))), table.header[0])
        assertEquals(2, table.rows.size)
        assertEquals(TableCell(listOf(Strong(listOf(Text("苹果"))))), table.rows[0][0])
        assertEquals(TableCell(listOf(Code("红"))), table.rows[0][2])
        // 缺的单元格补齐为空
        assertEquals(3, table.rows[1].size)
        assertEquals(TableCell(emptyList()), table.rows[1][2])
    }

    @Test
    fun tableWithMathAndCitationInCells() {
        val table = single("| a | b |\n|---|---|\n| \$x^2\$ | 见[citation:2] |") as Table
        assertEquals(TableCell(listOf(MdInline.Math("x^2", display = false))), table.rows[0][0])
        assertEquals(TableCell(listOf(Text("见"), Citation(2))), table.rows[0][1])
    }

    @Test
    fun citations() {
        assertEquals(
            Paragraph(listOf(Text("结论"), Citation(1), Citation(3), Text("。"))),
            single("结论[citation:1][citation:3]。"),
        )
        // 链接文字里的角标保留
        assertEquals(
            Paragraph(listOf(Link("https://a.com", listOf(Text("来源"), Citation(2))))),
            single("[来源[citation:2]](https://a.com)"),
        )
        // 行内代码里不处理
        assertEquals(Paragraph(listOf(Code("[citation:1]"))), single("`[citation:1]`"))
        // 标题、列表、引用里都能识别
        assertEquals(Heading(2, listOf(Text("标题"), Citation(4))), single("## 标题[citation:4]"))
        val list = single("- 项[citation:5]") as ListBlock
        assertEquals(Paragraph(listOf(Text("项"), Citation(5))), list.items[0].blocks[0])
    }

    @Test
    fun citationAfterAutolink() {
        val p = single("见 https://a.com [citation:1]") as Paragraph
        assertEquals(Citation(1), p.inlines.last())
    }

    @Test
    fun htmlShownAsText() {
        assertEquals(Paragraph(listOf(Text("a <span>b"))), single("a <span>b"))
    }

    @Test
    fun linkReferenceDefinitionShownAsText() {
        val blocks = parse("[1]: https://a.com")
        assertEquals(listOf(Paragraph(listOf(Text("[1]: https://a.com")))), blocks)
    }

    @Test
    fun parseCachedReturnsSameInstance() {
        val md = "缓存测试 **x**"
        assertTrue(MarkdownParser.parseCached(md) === MarkdownParser.parseCached(md))
    }
}
