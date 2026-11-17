package io.github.smalltailqwq.shallowdrop.ui.markdown

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import io.github.smalltailqwq.shallowdrop.ui.components.searchTextHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 代码块：标题栏左侧语言名、右侧复制；正文高亮、横向滚动、不折行 */
@Composable
internal fun CodeBlockView(block: MdBlock.CodeBlock, context: MarkdownContext, modifier: Modifier = Modifier) {
    val style = context.style
    val highlighted = remember(block.code, block.language, style) { highlightCode(block.code, block.language, style) }
    val search = searchTextHighlight(highlighted)
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(style.codeBlockBackground),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(style.codeBlockHeader)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = searchTextHighlight(AnnotatedString(block.language))
            Text(label.text, style = style.codeLabel, maxLines = 1, modifier = label.modifier, onTextLayout = label.onTextLayout)
            Spacer(Modifier.weight(1f))
            CopyButton(block.code, style)
        }
        Text(
            text = search.text,
            style = style.codeBlock,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .then(search.modifier),
            onTextLayout = search.onTextLayout,
        )
    }
}

@Composable
private fun CopyButton(code: String, style: MarkdownStyle) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                copied = true
            }
        },
        modifier = Modifier.size(36.dp),
    ) {
        DsIcon(
            if (copied) R.drawable.ic_check_outline_16 else R.drawable.ic_copy_outline_16,
            contentDescription = stringResource(android.R.string.copy),
            tint = style.secondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

internal fun highlightCode(code: String, language: String, style: MarkdownStyle): AnnotatedString {
    val tokens = CodeHighlighter.highlight(code, language)
    if (tokens.isEmpty()) return AnnotatedString(code)
    return buildAnnotatedString {
        append(code)
        for (token in tokens) {
            val span = style.codeTokens[token.kind] ?: continue
            addStyle(span, token.start, token.end)
        }
    }
}
