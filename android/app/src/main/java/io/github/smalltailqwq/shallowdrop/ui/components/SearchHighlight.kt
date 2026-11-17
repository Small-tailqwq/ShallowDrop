package io.github.smalltailqwq.shallowdrop.ui.components

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString

@Stable
internal class SearchHighlightState(val messageId: String, val query: String) {
    var positioned by mutableStateOf(false)
    private var owner: Any? = null

    fun claim(candidate: Any): Boolean {
        if (owner == null) owner = candidate
        return owner === candidate
    }
}

internal val LocalSearchHighlight = compositionLocalOf<SearchHighlightState?> { null }

internal class SearchTextHighlight(
    val text: AnnotatedString,
    val modifier: Modifier = Modifier,
    val onTextLayout: (TextLayoutResult) -> Unit = {},
    val matches: Boolean = false,
)

internal fun highlightKeyword(text: AnnotatedString, query: String, style: SpanStyle): AnnotatedString {
    if (query.isEmpty()) return text
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val at = text.text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            addStyle(style, at, at + query.length)
            from = at + query.length
        }
    }
}

/** 文字保留原来的链接与排版，只为命中词添加颜色，并把第一处命中滚入视口。 */
@Composable
internal fun searchTextHighlight(text: AnnotatedString): SearchTextHighlight {
    val state = LocalSearchHighlight.current ?: return SearchTextHighlight(text)
    val at = text.text.indexOf(state.query, ignoreCase = true)
    if (at < 0) return SearchTextHighlight(text)
    val colors = MaterialTheme.colorScheme
    val style = SpanStyle(background = colors.primaryContainer, color = colors.onPrimaryContainer)
    val highlighted = remember(text, state.query, style) { highlightKeyword(text, state.query, style) }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(state, layout) {
        val measured = layout
        if (!state.positioned && measured != null && state.claim(requester)) {
            requester.bringIntoView(measured.getBoundingBox(at))
            state.positioned = true
        }
    }
    return SearchTextHighlight(highlighted, Modifier.bringIntoViewRequester(requester), { layout = it }, matches = true)
}
