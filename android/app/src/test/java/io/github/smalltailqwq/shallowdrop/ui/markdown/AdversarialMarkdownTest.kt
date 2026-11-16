package io.github.smalltailqwq.shallowdrop.ui.markdown

import org.junit.Assert.assertTrue
import org.junit.Test

/** 畸形与极端输入：解析、公式转换、高亮都不得抛异常或明显卡死。 */
class AdversarialMarkdownTest {
    private val nasty = listOf(
        "$", "$$", "$$$", "\\(", "\\[", "\\]", "\\)", "$\\", "$$\n", "$$\n$$", "\\[\n", "$ $", "$" + "a", "a$", "$$" + "a$",
        "\\(\\(\\)\\)", "$\\$$", "a \\[ b \\( c $$ d $ e", "$$\n\\begin{matrix} a & b \\\\ c\n",
        "| a | b |\n|---|", "| a |\n|---|---|\n| 1 | 2 | 3 |\n| 4 |", "|||\n|-|-|\n|", "|\n|-\n",
        "```", "```\n", "````js\n```\n", "~~~\n```\n~~~", "```" + "x".repeat(300),
        "- ", "- [ ]", "- [x] a\n  - [ ] b\n      - c\n1. \n2.", "> ", "> - > 1. ```\n",
        "[citation:", "[citation:0]", "[citation:99999999999999999999]", "[citation:1][citation:-1][citation:a]",
        "[a](", "[a]()", "![", "<", "<a href=\"", "&#xFFFFFFFF;", "\u0000", "\uFEFF# t", "\r\n\r", "\t\t- a",
        "#".repeat(10), "*".repeat(200), "_".repeat(200), "[".repeat(500), "(".repeat(500), ">".repeat(300),
        "\\frac", "\\frac{", "\\frac{a}", "\\sqrt[", "^", "_", "a^", "a_{", "{", "}", "\\left(", "\\right)",
        "\\begin{", "\\begin{cases}", "\\end{x}", "\\text{", "\\\\", "\\", "x^{y^{z^{w", "\\mathbb", "\\not",
    )

    @Test
    fun parserSurvivesNastyInputAndPrefixes() {
        val all = nasty.joinToString("\n\n")
        for (text in nasty + all) {
            MarkdownParser.parse(text)
            MarkdownParser.parse("$$\n$text\n$$")
            MarkdownParser.parse("text $$text$ \\($text\\)")
        }
        val incremental = IncrementalMarkdownParser()
        for (end in 0..all.length) incremental.parse(all.substring(0, end))
        incremental.finish(all)
    }

    /** TableView 按「行数 × 表头列数」索引单元格，行必须已补齐或截断 */
    @Test
    fun raggedTableRowsMatchHeader() {
        val doc = MarkdownParser.parse("| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |\n||\n| x\n")
        val table = doc.blocks.filterIsInstance<MdBlock.Table>().single()
        assertTrue(table.header.isNotEmpty())
        assertTrue(table.rows.isNotEmpty() && table.rows.all { it.size == table.header.size })
    }

    @Test
    fun texSurvivesNastyAndDeepInput() {
        for (text in nasty) {
            TexToUnicode.convert(text)
            TexToUnicode.convert(text, display = true)
        }
        val deep = "\\frac{".repeat(5000) + "a" + "}".repeat(5000)
        TexToUnicode.convert(deep)
        TexToUnicode.convert("{".repeat(20000))
        TexToUnicode.convert("x^".repeat(20000))
        TexToUnicode.convert("\\sqrt{".repeat(20000))
        MarkdownParser.parse("$" + deep + "$")
    }

    @Test
    fun deeplyNestedMarkdownDoesNotOverflow() {
        MarkdownParser.parse("> ".repeat(2000) + "a")
        MarkdownParser.parse((0 until 500).joinToString("\n") { "  ".repeat(it) + "- a" })
        MarkdownParser.parse("*a ".repeat(3000))
        MarkdownParser.parse("[a](b ".repeat(2000))
    }

    @Test
    fun highlighterIsFastOnPathologicalInput() {
        val inputs = listOf(
            "\"" + "\\".repeat(20000),
            "/*" + "*".repeat(20000),
            "'".repeat(20000),
            "a".repeat(50000),
            "<".repeat(20000),
            "#".repeat(20000) + "\n" + "\"\"\"".repeat(5000),
            "`".repeat(20000),
            ("x = \"abc" + "\n").repeat(5000),
        )
        val languages = listOf("kotlin", "python", "javascript", "html", "css", "json", "bash", "sql", "c", "rust", "markdown", "xml", "yaml", "go")
        val start = System.nanoTime()
        for (language in languages) {
            val resolved = CodeHighlighter.resolveLanguage(language) ?: continue
            for (input in inputs) CodeHighlighter.highlight(input, resolved)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("highlight took ${elapsedMs}ms", elapsedMs < 20_000)
    }
}
