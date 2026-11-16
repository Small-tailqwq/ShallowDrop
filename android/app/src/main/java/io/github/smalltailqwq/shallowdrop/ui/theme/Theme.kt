package io.github.smalltailqwq.shallowdrop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize

/*
 * 原创蓝色系配色（不开 dynamic color）。中性色偏冷灰，主色为钴蓝。
 */

private val Light = lightColorScheme(
    primary = Color(0xFF2B5FD9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF0B275F),
    secondary = Color(0xFF55607A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E9F5),
    onSecondaryContainer = Color(0xFF1B2335),
    tertiary = Color(0xFF00708A),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFE6E9F0),
    onSurfaceVariant = Color(0xFF5D6370),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FB),
    surfaceContainer = Color(0xFFF1F3F7),
    surfaceContainerHigh = Color(0xFFEBEEF3),
    surfaceContainerHighest = Color(0xFFE4E8EF),
    outline = Color(0xFFB4BAC6),
    outlineVariant = Color(0xFFDDE1E8),
    inverseSurface = Color(0xFF2C2F35),
    inverseOnSurface = Color(0xFFF0F1F5),
    inversePrimary = Color(0xFFAFC4FF),
    scrim = Color(0xFF000000),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C0FF),
    onPrimary = Color(0xFF0C2C6E),
    primaryContainer = Color(0xFF223F8C),
    onPrimaryContainer = Color(0xFFDDE6FF),
    secondary = Color(0xFFBCC5DB),
    onSecondary = Color(0xFF262F42),
    secondaryContainer = Color(0xFF333B4E),
    onSecondaryContainer = Color(0xFFDCE2F2),
    tertiary = Color(0xFF7FD3EA),
    onTertiary = Color(0xFF003543),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5F0B0B),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131418),
    onBackground = Color(0xFFE4E5EA),
    surface = Color(0xFF131418),
    onSurface = Color(0xFFE4E5EA),
    surfaceVariant = Color(0xFF2E323B),
    onSurfaceVariant = Color(0xFFA6ACB8),
    surfaceContainerLowest = Color(0xFF0E0F12),
    surfaceContainerLow = Color(0xFF1A1C21),
    surfaceContainer = Color(0xFF1F2126),
    surfaceContainerHigh = Color(0xFF262830),
    surfaceContainerHighest = Color(0xFF30323A),
    outline = Color(0xFF6E7480),
    outlineVariant = Color(0xFF3A3E47),
    inverseSurface = Color(0xFFE4E5EA),
    inverseOnSurface = Color(0xFF2C2F35),
    inversePrimary = Color(0xFF2B5FD9),
    scrim = Color(0xFF000000),
)

/** Material 之外的少量语义色。 */
@Immutable
data class ExtraColors(
    /** 用户消息气泡 */
    val userBubble: Color,
    /** 思考块左侧竖线与正文 */
    val thinkingLine: Color,
    val thinkingText: Color,
)

private val LightExtra = ExtraColors(
    userBubble = Color(0xFFEAF0FF),
    thinkingLine = Color(0xFFD9DDE5),
    thinkingText = Color(0xFF5D6370),
)

private val DarkExtra = ExtraColors(
    userBubble = Color(0xFF26304A),
    thinkingLine = Color(0xFF3A3E47),
    thinkingText = Color(0xFFA6ACB8),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

private val AppTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** 分组列表页（设置等）的页面底色：与对话页拉开一档，衬托卡片 */
@Composable
fun groupedPageColor(): Color = MaterialTheme.colorScheme.run { if (background.luminance() < 0.5f) surfaceContainerLow else surfaceContainer }

/** 分组列表页中卡片的底色 */
@Composable
fun groupedCardColor(): Color = MaterialTheme.colorScheme.run { if (background.luminance() < 0.5f) surfaceContainerHigh else surfaceContainerLowest }

/** 消息正文样式 */
val MessageTextStyle: TextStyle @Composable get() = MaterialTheme.typography.bodyLarge

/** 聊天内容独立缩放，工具按钮与导航保持原有大小。 */
@Composable
fun ChatTextTheme(size: ChatFontSize, content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val typography = remember(base, size) {
        val scale = size.sp / 16f
        fun TextStyle.scaled() = copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
        base.copy(
            bodyLarge = base.bodyLarge.scaled(),
            bodyMedium = base.bodyMedium.scaled(),
            headlineSmall = base.headlineSmall.scaled(),
            titleLarge = base.titleLarge.scaled(),
            titleMedium = base.titleMedium.scaled(),
        )
    }
    MaterialTheme(typography = typography, content = content)
}

@Composable
fun isAppInDarkTheme(preference: ThemePreference): Boolean = when (preference) {
    ThemePreference.System -> isSystemInDarkTheme()
    ThemePreference.Light -> false
    ThemePreference.Dark -> true
}

@Composable
fun ShallowDropTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalExtraColors provides if (darkTheme) DarkExtra else LightExtra,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) Dark else Light,
            typography = AppTypography,
            content = content,
        )
    }
}
