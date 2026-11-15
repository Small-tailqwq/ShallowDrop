package io.github.smalltailqwq.shallowdrop.ui.markdown

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

/**
 * 渲染样式：颜色与字体全部取自 MaterialTheme，不另定义主题。
 * 正文颜色不写死，跟随 LocalContentColor（放在气泡、卡片里时自动适配）。
 */
@Immutable
internal class MarkdownStyle(
    val body: TextStyle,
    val headings: List<TextStyle>,
    val inlineCode: SpanStyle,
    val link: SpanStyle,
    val math: SpanStyle,
    val codeBlock: TextStyle,
    val codeBlockBackground: Color,
    val codeBlockHeader: Color,
    val codeLabel: TextStyle,
    val codeTokens: Map<CodeTokenKind, SpanStyle>,
    val secondary: Color,
    val quoteBar: Color,
    val divider: Color,
    val citationBackground: Color,
    val citationContent: Color,
    val citationText: TextStyle,
    val tableHeader: TextStyle,
    val tableBody: TextStyle,
)

@Composable
internal fun rememberMarkdownStyle(): MarkdownStyle {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    // 调用方可通过 LocalTextStyle 缩小正文（如思考块），未指定的字段沿用 bodyLarge
    val local = LocalTextStyle.current
    return remember(colors, type, local) {
        val strong = FontWeight.SemiBold
        MarkdownStyle(
            body = type.bodyLarge.merge(local),
            headings = listOf(
                type.headlineSmall.copy(fontWeight = strong),
                type.titleLarge.copy(fontWeight = strong),
                type.titleMedium.copy(fontSize = type.titleLarge.fontSize * 0.9f, fontWeight = strong),
                type.titleMedium.copy(fontWeight = strong),
                type.bodyLarge.copy(fontWeight = strong),
                type.bodyLarge.copy(fontWeight = strong),
            ),
            inlineCode = SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 0.875.em,
                background = colors.surfaceContainerHighest,
            ),
            link = SpanStyle(color = colors.primary),
            math = SpanStyle(fontFamily = FontFamily.Serif),
            codeBlock = type.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = colors.onSurface),
            codeBlockBackground = colors.surfaceContainer,
            codeBlockHeader = colors.surfaceContainerHigh,
            codeLabel = type.labelMedium.copy(color = colors.onSurfaceVariant),
            codeTokens = mapOf(
                CodeTokenKind.Keyword to SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium),
                CodeTokenKind.Literal to SpanStyle(color = colors.tertiary),
                CodeTokenKind.Number to SpanStyle(color = colors.tertiary),
                CodeTokenKind.Type to SpanStyle(color = colors.tertiary, fontWeight = FontWeight.Medium),
                CodeTokenKind.Tag to SpanStyle(color = colors.primary),
                CodeTokenKind.Function to SpanStyle(color = colors.secondary, fontWeight = FontWeight.Medium),
                CodeTokenKind.String to SpanStyle(color = colors.secondary),
                CodeTokenKind.Attribute to SpanStyle(color = colors.tertiary),
                CodeTokenKind.Meta to SpanStyle(color = colors.secondary, fontStyle = FontStyle.Italic),
                CodeTokenKind.Comment to SpanStyle(color = colors.onSurfaceVariant, fontStyle = FontStyle.Italic),
            ),
            secondary = colors.onSurfaceVariant,
            quoteBar = colors.outlineVariant,
            divider = colors.outlineVariant,
            citationBackground = colors.surfaceContainerHighest,
            citationContent = colors.onSurfaceVariant,
            citationText = type.labelSmall,
            tableHeader = type.bodyMedium.copy(fontWeight = strong),
            tableBody = type.bodyMedium,
        )
    }
}
