package io.github.smalltailqwq.shallowdrop.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHighlightTest {
    @Test fun keywordHighlightPreservesFormattingAndLinks() {
        val text = buildAnnotatedString {
            append("Kotlin kotlin KOTLIN")
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 6)
            addStringAnnotation("URL", "https://kotlinlang.org", 0, 6)
        }
        val marked = highlightKeyword(text, "kotlin", SpanStyle(background = Color.Yellow))
        assertEquals(text.text, marked.text)
        assertEquals(listOf(0 to 6, 7 to 13, 14 to 20), marked.spanStyles
            .filter { it.item.background == Color.Yellow }.map { it.start to it.end })
        assertTrue(marked.spanStyles.containsAll(text.spanStyles))
        assertEquals(text.getStringAnnotations(0, text.length), marked.getStringAnnotations(0, marked.length))
    }
}
