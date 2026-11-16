package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source
import io.github.smalltailqwq.shallowdrop.ui.markdown.MarkdownText
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
/**
 * 消息正文的 Markdown 渲染入口。markdown 中引用处为 `[citation:N]`，sources 按编号排列。
 * 文字样式通过 LocalTextStyle / LocalContentColor 传入（思考块为小号灰字）。
 */
@Composable
fun MessageMarkdown(
    markdown: String,
    sources: List<Source>,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val citations = remember(sources) {
        sources.map { TextCitation(url = it.url, title = it.title.takeIf { title -> title != it.url }) }
    }
    val uriHandler = LocalUriHandler.current
    val open: (String) -> Unit = { url -> uriHandler.openWebUrl(url) }
    MarkdownText(
        markdown = markdown,
        modifier = modifier,
        citations = citations,
        streaming = streaming,
        onCitationClick = { open(it.url) },
        onLinkClick = open,
    )
}

internal fun UriHandler.openWebUrl(value: String) {
    val url = value.toHttpUrlOrNull() ?: return
    try {
        openUri(url.toString())
    } catch (_: IllegalArgumentException) {
        // Compose 将找不到可处理 Activity 的异常包装为 IllegalArgumentException。
    }
}

