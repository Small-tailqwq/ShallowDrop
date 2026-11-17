package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import io.github.smalltailqwq.shallowdrop.ui.components.AppBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.ToolStatus

/** 来源面板：列出搜索结果，点击用浏览器打开。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(sources: List<Source>, @StringRes title: Int = R.string.search_results, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AppBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            itemsIndexed(sources, key = { index, source -> "$index:${source.url}" }) { _, source ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = source.status != ToolStatus.Failed && source.url.isNotBlank()) { uriHandler.openWebUrl(source.url) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (source.index > 0) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                    Text("${source.index}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        SourceIcon(source.siteIcon)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            listOfNotNull(source.siteName.ifEmpty { null }, source.publishedAt?.take(10)).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(source.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (source.status == ToolStatus.Failed || source.status == ToolStatus.Wip) {
                        Text(
                            stringResource(if (source.status == ToolStatus.Failed) R.string.web_page_failed else R.string.reading_link),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (source.status == ToolStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    source.snippet?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
