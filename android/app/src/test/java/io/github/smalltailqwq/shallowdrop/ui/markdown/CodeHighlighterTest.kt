package io.github.smalltailqwq.shallowdrop.ui.markdown

import io.github.smalltailqwq.shallowdrop.ui.markdown.CodeTokenKind.Comment
import io.github.smalltailqwq.shallowdrop.ui.markdown.CodeTokenKind.Function
import io.github.smalltailqwq.shallowdrop.ui.markdown.CodeTokenKind.Keyword
import io.github.smalltailqwq.shallowdrop.ui.markdown.CodeTokenKind.Literal
import io.github.smalltailqwq.shallowdrop.ui.markdown.CodeTokenKind.Number
import io.github.smalltailqwq.shallowdrop.ui.markdown.CodeTokenKind.String
import io.github.smalltailqwq.shallowdrop.ui.markdown.CodeTokenKind.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlighterTest {
    private fun tokens(code: String, lang: String) =
        CodeHighlighter.highlight(code, lang).map { code.substring(it.start, it.end) to it.kind }

    @Test
    fun kotlin() {
        val code = "val name = \"a\" // 注释\nfun f(x: Int) = listOf(1, 2.5)"
        assertEquals(
            listOf(
                "val" to Keyword, "\"a\"" to String, "// 注释" to Comment, "fun" to Keyword, "f" to Function,
                "Int" to Type, "listOf" to Function, "1" to Number, "2.5" to Number,
            ),
            tokens(code, "kt"),
        )
    }

    @Test
    fun python() {
        val code = "def f():\n    '''doc\n    string'''\n    return None  # 注释"
        assertEquals(
            listOf("def" to Keyword, "f" to Function, "'''doc\n    string'''" to String, "return" to Keyword, "None" to Literal, "# 注释" to Comment),
            tokens(code, "py"),
        )
    }

    @Test
    fun sqlIsCaseInsensitive() {
        val result = tokens("SELECT * FROM t WHERE id = 1 -- x", "sql")
        assertEquals(listOf("SELECT" to Keyword, "FROM" to Keyword, "WHERE" to Keyword, "1" to Number, "-- x" to Comment), result)
    }

    @Test
    fun blockCommentAndTemplateString() {
        val result = tokens("/* a\nb */ const s = `x\ny`", "js")
        assertEquals(listOf("/* a\nb */" to Comment, "const" to Keyword, "`x\ny`" to String), result)
    }

    @Test
    fun rustLifetimeIsNotString() {
        val result = tokens("fn f<'a>(s: &'a str) -> char { 'x' }", "rust")
        assertTrue(result.contains("'x'" to String))
        assertTrue(result.none { it.second == String && it.first.length > 3 })
    }

    @Test
    fun unterminatedStringStopsAtLineEnd() {
        val result = tokens("x = \"abc\ny = 1", "python")
        assertEquals("\"abc" to String, result[0])
        assertEquals("1" to Number, result.last())
    }

    @Test
    fun markup() {
        val result = tokens("<div class=\"a\"><!-- c --></div>", "html")
        assertEquals(
            listOf("div" to CodeTokenKind.Tag, "class" to CodeTokenKind.Attribute, "\"a\"" to String, "<!-- c -->" to Comment, "div" to CodeTokenKind.Tag),
            result,
        )
    }

    @Test
    fun unsupportedLanguage() {
        assertNull(CodeHighlighter.resolveLanguage("brainfuck"))
        assertTrue(CodeHighlighter.highlight("+++", "text").isEmpty())
    }

    @Test
    fun tokensAreOrderedAndNonOverlapping() {
        val code = "#include <stdio.h>\nint main() { printf(\"%d\", 42); /* x */ return 0; }"
        val list = CodeHighlighter.highlight(code, "c")
        for (k in 1 until list.size) assertTrue(list[k - 1].end <= list[k].start)
    }
}
