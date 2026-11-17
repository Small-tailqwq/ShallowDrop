package io.github.smalltailqwq.shallowdrop.ui.chat.message

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUriHandler
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolInput
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.buildReplyView
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class WebLinkClickTest {
    @get:Rule val compose = createComposeRule()

    private enum class Entry { Page, Sources, Citation }
    private var url by mutableStateOf("https://example.com/page")
    private var missingActivity = false
    private var pageListOpen by mutableStateOf(false)
    private val opened = mutableListOf<String>()
    private val uriHandler = AndroidUriHandler(object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun startActivity(intent: Intent) {
            if (missingActivity) throw ActivityNotFoundException("No activity handles this URL")
            assertEquals(Intent.ACTION_VIEW, intent.action)
            opened += requireNotNull(intent.dataString)
        }
    })

    private fun show(entry: Entry) {
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                ShallowDropTheme {
                    val message = Message.Assistant("a", 0, status = ReplyStatus.Done, blocks = listOf(
                        ReplyBlock.ServerToolUse("open", "deepseek_web_search", ServerToolInput.OpenPage(url), ServerToolKind.DeepSearch),
                        ReplyBlock.WebFetchToolResult("open", url = url, title = "网页来源", kind = ServerToolKind.DeepSearch),
                        ReplyBlock.Text("来源", citations = listOf(TextCitation(url = url))),
                    ))
                    val view = buildReplyView(message, streaming = false)
                    Surface {
                        when (entry) {
                            Entry.Page -> {
                                ReplyArea(message, view, streaming = false, hasResponse = true,
                                    onOpenSources = {}, onOpenPages = { pageListOpen = true })
                                if (pageListOpen) SourcesSheet(view.sources, onDismiss = { pageListOpen = false })
                            }
                            Entry.Sources -> SourcesSheet(view.sources, onDismiss = {})
                            Entry.Citation -> MessageMarkdown(requireNotNull(view.answer).markdown, view.sources)
                        }
                    }
                }
            }
        }
    }

    private fun click(entry: Entry) {
        if (entry == Entry.Page && !pageListOpen) {
            compose.onNodeWithText("浏览 1 个页面").assertIsDisplayed().performClick()
        }
        if (entry == Entry.Citation) {
            compose.onNodeWithText("1").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        } else {
            compose.onNodeWithText("网页来源", useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
        }
    }

    private fun rejectsNonWebLinks(entry: Entry) {
        show(entry)
        val invalid = listOf(
            "intent://example.com/#Intent;scheme=custom;end",
            "javascript:alert(1)",
            "content://example.com/private",
            "file:///sdcard/document.txt",
            "mailto:user@example.com",
            "tel:12345",
            "ftp://example.com/file",
            "CuStOm://example.com/path",
            "\tInTeNt://example.com/path\n",
            "%69ntent://example.com/path",
            "https://",
            "https://exa mple.com/",
            "https://example.com:99999/",
            "example.com/path",
            "//example.com/path",
        )
        for (value in invalid) {
            compose.runOnIdle { url = value }
            click(entry)
            assertEquals("Must not open $value", emptyList<String>(), opened)
        }
    }

    private fun opensWebLinks(entry: Entry) {
        show(entry)
        val valid = listOf(
            "https://example.com/page?q=1#section" to "https://example.com/page?q=1#section",
            "http://example.com:8080/path" to "http://example.com:8080/path",
            "HTTPS://EXAMPLE.COM/文档" to "https://example.com/%E6%96%87%E6%A1%A3",
        )
        for ((value, expected) in valid) {
            compose.runOnIdle { url = value }
            click(entry)
            assertEquals(expected, opened.last())
        }
        assertEquals(valid.size, opened.size)
    }

    private fun survivesMissingActivity(entry: Entry) {
        missingActivity = true
        show(entry)
        click(entry)
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun pageRejectsNonWebLinks() = rejectsNonWebLinks(Entry.Page)
    @Test fun sourcesRejectNonWebLinks() = rejectsNonWebLinks(Entry.Sources)
    @Test fun citationRejectsNonWebLinks() = rejectsNonWebLinks(Entry.Citation)
    @Test fun pageOpensWebLinks() = opensWebLinks(Entry.Page)
    @Test fun sourcesOpenWebLinks() = opensWebLinks(Entry.Sources)
    @Test fun citationOpensWebLinks() = opensWebLinks(Entry.Citation)
    @Test fun pageSurvivesMissingActivity() = survivesMissingActivity(Entry.Page)
    @Test fun sourcesSurviveMissingActivity() = survivesMissingActivity(Entry.Sources)
    @Test fun citationSurvivesMissingActivity() = survivesMissingActivity(Entry.Citation)
}
