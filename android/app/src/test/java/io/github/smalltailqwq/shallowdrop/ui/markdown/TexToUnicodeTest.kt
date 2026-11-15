package io.github.smalltailqwq.shallowdrop.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TexToUnicodeTest {
    private fun plain(tex: String, display: Boolean = false) = TexToUnicode.plainText(tex, display)

    @Test
    fun greekAndOperators() {
        assertEquals("α + β ≤ γ", plain("\\alpha+\\beta\\leq\\gamma"))
        assertEquals("a × b ≠ c", plain("a \\times b \\neq c"))
        assertEquals("−x", plain("-x"))
        assertEquals("f(x, y) = 0", plain("f(x,y)=0").replace(' ', ' '))
    }

    @Test
    fun fractionsAndRoots() {
        assertEquals("1/2", plain("\\frac{1}{2}"))
        assertEquals("(a + b)/c", plain("\\frac{a+b}{c}"))
        assertEquals("√x", plain("\\sqrt{x}"))
        assertEquals("√(x + 1)", plain("\\sqrt{x+1}"))
        assertEquals("3√8", plain("\\sqrt[3]{8}"))
    }

    @Test
    fun scriptsCarryLevels() {
        val spans = TexToUnicode.convert("x_i^2")
        assertEquals(listOf(MathSpan("x", italic = true), MathSpan("i", italic = true, scripts = "_"), MathSpan("2", scripts = "^")), spans)
        val nested = TexToUnicode.convert("e^{x^2}")
        assertEquals("^^", nested.last().scripts)
    }

    @Test
    fun functionsAndBigOperators() {
        assertEquals("sin x", plain("\\sin x").replace(' ', ' '))
        assertEquals("sin(x)", plain("\\sin(x)"))
        val sum = TexToUnicode.convert("\\sum_{i=1}^{n} i")
        assertEquals("∑", sum.first().text)
        assertEquals("i=1", sum.filter { it.scripts == "_" }.joinToString("") { it.text })
        assertEquals("n", sum.filter { it.scripts == "^" }.joinToString("") { it.text })
    }

    @Test
    fun fontsAndText() {
        assertEquals("x ∈ ℝ", plain("x \\in \\mathbb{R}"))
        assertEquals("如果 x > 0", plain("\\text{如果 } x > 0"))
        val bold = TexToUnicode.convert("\\mathbf{v}")
        assertTrue(bold.single().bold)
        assertEquals("rank A", plain("\\operatorname{rank} A").replace(' ', ' '))
    }

    @Test
    fun accents() {
        assertEquals("x̂", plain("\\hat{x}"))
        assertEquals("v⃗", plain("\\vec v"))
        assertEquals("A̅B̅", plain("\\overline{AB}"))
    }

    @Test
    fun delimitersAndSpacing() {
        assertEquals("(x/2)", plain("\\left( \\frac{x}{2} \\right)"))
        assertEquals("|x|", plain("\\left| x \\right|"))
        assertEquals("{x}", plain("\\{x\\}"))
        assertEquals("a b", plain("a\\quad b"))
    }

    @Test
    fun environments() {
        assertEquals("(a  b; c  d)", plain("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}"))
        val cases = plain("f(x) = \\begin{cases} 1 & x > 0 \\\\ 0 & \\text{otherwise} \\end{cases}", display = true)
        assertEquals("f(x) = {1 x > 0\n0 otherwise", cases)
        assertEquals("a = b; c = d", plain("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}"))
    }

    @Test
    fun chemistry() {
        val spans = TexToUnicode.convert("\\ce{H2O -> H2 + O2}")
        assertEquals("H2O → H2 + O2", spans.joinToString("") { it.text })
        assertTrue(spans.any { it.text == "2" && it.scripts == "_" })
    }

    @Test
    fun unknownCommandKept() {
        assertTrue(plain("\\foo x").startsWith("\\foo"))
    }

    @Test
    fun malformedInputNeverThrows() {
        for (tex in listOf("{", "}", "\\frac{", "x^", "\\begin{matrix} a &", "\\left(", "\\sqrt[", "{{{{{{", "\\")) {
            TexToUnicode.convert(tex)
        }
        // 嵌套过深时退回原文
        val deep = "{".repeat(500) + "x" + "}".repeat(500)
        assertEquals(deep, TexToUnicode.plainText(deep))
    }
}
