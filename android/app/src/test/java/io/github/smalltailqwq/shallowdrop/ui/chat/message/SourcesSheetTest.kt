package io.github.smalltailqwq.shallowdrop.ui.chat.message

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.ToolStatus
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class SourcesSheetTest {
    @get:Rule val compose = createComposeRule()
    private val sources = (1..50).map { Source("https://example.com/$it", "页面 $it", "example.com", null, "页面摘要", null, it) }
    private val opened = mutableListOf<String>()

    private fun show(entries: List<Source> = sources) = compose.setContent {
        CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }) { ShallowDropTheme { SourcesSheet(entries) {} } }
    }

    @Test fun portraitHandleExpandsAndEverySourceIsReachable() {
        show()
        compose.onNodeWithContentDescription("展开面板").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("收起面板").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(sources.last().title))
        compose.onNodeWithText(sources.last().title).assertIsDisplayed().performClick()
        assertEquals(listOf(sources.last().url), opened)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h360dp-xhdpi")
    fun landscapeUsesAvailableWidthAndStartsExpanded() {
        show()
        compose.onNodeWithContentDescription("关闭面板").assertIsDisplayed()
        val row = compose.onNodeWithText(sources.first().title).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Expected a full-width source row, got $row", row.width >= 780.dp)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(sources.last().title))
        compose.onNodeWithText(sources.last().title).assertIsDisplayed()
    }

    @Test fun repeatedPageUrlsRemainVisibleAndFailedPagesAreDisabled() {
        val first = sources.first()
        show(listOf(first, first.copy(title = "重复浏览"), first.copy(title = "读取失败", status = ToolStatus.Failed)))
        compose.onNodeWithText(first.title).assertIsDisplayed()
        compose.onNodeWithText("重复浏览").assertIsDisplayed().performClick()
        compose.onNodeWithText("读取失败").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(listOf(first.url), opened)
    }
}
