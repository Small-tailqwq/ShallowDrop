package io.github.smalltailqwq.shallowdrop.ui.chat.message

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.FindItem
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.PageItem
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.ReplyView
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Step
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.ToolStatus
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import java.io.File
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
class ReplyAreaLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val pages = (1..4).map {
        PageItem("page-$it", "https://example.com/page-$it", "网页 $it：布局与交互设计的详细说明", ToolStatus.Wip)
    }
    private val thinking = Step.Thinking("thinking", listOf(ReplyBlock.Thinking("先核对资料中的布局与交互规则。", elapsedSeconds = 2.0)))
    private var shownSteps by mutableStateOf<List<Step>>(emptyList())
    private var streaming by mutableStateOf(true)
    private var hasResponse by mutableStateOf(false)
    private val openedUrls = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) { openedUrls += uri }
    }

    private fun show(vararg steps: Step) {
        shownSteps = steps.toList()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                ShallowDropTheme {
                    Surface {
                        Column(Modifier.width(320.dp).padding(16.dp)) {
                            ReplyArea(
                                message = Message.Assistant("a", 0, status = if (streaming) ReplyStatus.Streaming else ReplyStatus.Done),
                                view = ReplyView(shownSteps, null, emptyList(), emptyList()),
                                streaming = streaming,
                                hasResponse = hasResponse,
                                onOpenSources = {},
                                modifier = Modifier.testTag("reply-area"),
                            )
                            Text("后续内容")
                        }
                    }
                }
            }
        }
    }

    private fun assertAllPagesVisible() {
        val next = compose.onNodeWithText("后续内容").getUnclippedBoundsInRoot()
        for (page in pages) {
            val node = compose.onNodeWithText(page.title).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("${page.id} must fit above the following content", bounds.bottom <= next.top)
            assertTrue("${page.id} must fit within the content width", bounds.right <= 304.dp)
        }
    }

    @Test
    fun allReadingLinksAreVisibleWhenTheyWrap() {
        show(Step.ReadLink(pages))
        assertAllPagesVisible()
    }

    @Test
    fun allOpenedPagesAreVisibleWhenTheyWrap() {
        show(Step.Open(pages.map { it.copy(status = ToolStatus.Done) }))
        assertAllPagesVisible()
    }

    @Test
    fun expandingThinkingRevealsEveryWrappedPageAndTheFollowingStep() {
        show(
            thinking,
            Step.Open(pages.map { it.copy(status = ToolStatus.Done) }),
            Step.Find(listOf(FindItem("find", "响应式布局", ToolStatus.Done))),
        )
        compose.onNodeWithText("正在思考").performClick()
        assertAllPagesVisible()
        val lastPage = compose.onNodeWithText(pages.last().title).getUnclippedBoundsInRoot()
        val followingStep = compose.onNodeWithText("查找关键词").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(lastPage.bottom < followingStep.top)
        compose.onNodeWithText("响应式布局").assertIsDisplayed()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File("build/ui-review-cot").apply { mkdirs() }
        File(output, "expanded.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        compose.runOnIdle {
            streaming = false
            hasResponse = true
        }
        compose.onNodeWithText("已思考（用时 2 秒）").assertIsDisplayed()
        assertAllPagesVisible()
    }

    @Test
    fun peekFollowsNewPagesAndInterceptsLinkTapsUntilExpanded() {
        show(thinking, Step.ReadLink(pages.take(2)))
        val initialHeight = compose.onNodeWithTag("reply-area").getUnclippedBoundsInRoot().height
        assertEquals(180.dp, initialHeight)
        compose.onNodeWithText(pages[1].title).assertIsDisplayed()

        compose.runOnIdle { shownSteps = listOf(thinking, Step.ReadLink(pages)) }
        compose.onNodeWithText(pages.last().title).assertIsDisplayed()
        compose.onNodeWithText(pages.first().title).assertIsNotDisplayed()
        assertEquals(initialHeight, compose.onNodeWithTag("reply-area").getUnclippedBoundsInRoot().height)

        compose.onNodeWithText(pages.last().title).performTouchInput { click() }
        assertAllPagesVisible()
        assertTrue(openedUrls.isEmpty())
        compose.onNodeWithText(pages.last().title).performTouchInput { click() }
        assertEquals(listOf(pages.last().url), openedUrls)
    }

    @Test
    fun untouchedPeekAnimatesClosedWhenResponseArrives() {
        show(thinking, Step.ReadLink(pages))
        val initialHeight = compose.onNodeWithTag("reply-area").getUnclippedBoundsInRoot().height
        assertEquals(180.dp, initialHeight)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            streaming = false
            hasResponse = true
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        val shrinkingHeight = compose.onNodeWithTag("reply-area").getUnclippedBoundsInRoot().height
        assertTrue("Expected a height between 48.dp and $initialHeight during collapse, got $shrinkingHeight", shrinkingHeight > 48.dp && shrinkingHeight < initialHeight)

        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("已思考（用时 2 秒）").assertIsDisplayed()
        compose.onNodeWithText(pages.last().title).assertDoesNotExist()
        assertEquals(48.dp, compose.onNodeWithTag("reply-area").getUnclippedBoundsInRoot().height)
    }
}
