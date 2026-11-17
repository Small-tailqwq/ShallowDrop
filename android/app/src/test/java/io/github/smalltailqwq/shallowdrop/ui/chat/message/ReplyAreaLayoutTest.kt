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
import io.github.smalltailqwq.shallowdrop.core.model.ServerToolKind
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.FindItem
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.PageItem
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.ReplyView
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source
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

    private val pages = (1..7).map {
        PageItem("page-$it", "https://example.com/page-$it", "网页 $it：布局与交互设计的详细说明", ToolStatus.Wip)
    }
    private val thinking = Step.Thinking("thinking", listOf(ReplyBlock.Thinking("先核对资料中的布局与交互规则。", elapsedSeconds = 2.0)))
    private val longThinking = thinking.copy(blocks = listOf(ReplyBlock.Thinking(
        (1..8).joinToString("\n\n") { "第 $it 步：核对资料中的布局与交互规则。" }, elapsedSeconds = 2.0,
    )))
    private var shownSteps by mutableStateOf<List<Step>>(emptyList())
    private var streaming by mutableStateOf(true)
    private var hasResponse by mutableStateOf(false)
    private val openedUrls = mutableListOf<String>()
    private val openedSources = mutableListOf<List<Source>>()
    private val openedPages = mutableListOf<List<Source>>()
    private var registeredSources = emptyList<Source>()
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
                                view = ReplyView(shownSteps, null, registeredSources, emptyList()),
                                streaming = streaming,
                                hasResponse = hasResponse,
                                onOpenSources = { openedSources += it },
                                onOpenPages = { openedPages += it },
                                modifier = Modifier.testTag("reply-area"),
                            )
                            Text("后续内容")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun readingLinksOpenAsACompleteList() {
        show(Step.ReadLink(pages))
        pages.forEach { compose.onNodeWithText(it.title).assertDoesNotExist() }
        compose.onNodeWithText("正在阅读").assertIsDisplayed().performClick()
        assertEquals(pages.map { it.url }, openedPages.single().map { it.url })
        assertTrue(openedPages.single().all { it.status == ToolStatus.Wip })
        assertTrue(openedUrls.isEmpty())
    }

    @Test
    fun browsingEntryIncludesUnregisteredAndRepeatedPagesWithTheirStatus() {
        val known = Source(pages.first().url, "完整页面标题", "来源站点", "2026-11-17", "页面简介", "https://example.com/icon.png", 3)
        registeredSources = listOf(known)
        val visited = pages.mapIndexed { index, page -> page.copy(status = if (index == 5) ToolStatus.Failed else ToolStatus.Done) } +
            pages.first().copy(id = "repeat", status = ToolStatus.Wip)
        show(Step.Open(visited))
        pages.forEach { compose.onNodeWithText(it.title).assertDoesNotExist() }
        compose.onNodeWithText("浏览 8 个页面").assertIsDisplayed().performClick()
        val shown = openedPages.single()
        assertEquals(visited.map { it.url }, shown.map { it.url })
        assertEquals(visited.map { it.status }, shown.map { it.status })
        assertEquals(known.copy(status = ToolStatus.Done), shown.first())
        assertEquals(pages[1].title, shown[1].title)
        assertEquals("example.com", shown[1].siteName)
        assertEquals(0, shown[1].index)
        assertTrue(openedSources.isEmpty())
        assertTrue(openedUrls.isEmpty())
    }

    @Test
    fun failedBrowsingStillShowsTheAttemptedPages() {
        show(Step.Open(pages.map { it.copy(status = ToolStatus.Failed) }))
        compose.onNodeWithText("浏览页面失败").assertIsDisplayed().performClick()
        assertEquals(pages.map { it.url }, openedPages.single().map { it.url })
        assertTrue(openedPages.single().all { it.status == ToolStatus.Failed })
    }

    @Test
    fun pressingSearchLeavesTheFollowingTimelineSegmentUnchanged() {
        val source = Source("https://example.com/page", "搜索页面", "example.com", null, null, null, 1)
        show(
            Step.Search("search", ServerToolKind.DeepSearch, listOf("布局"), listOf(source), ToolStatus.Done),
            Step.Preamble("following", "后续步骤"),
        )
        val entry = compose.onNodeWithText("搜索到 1 个网页")
        val entryBounds = entry.getUnclippedBoundsInRoot()
        val following = compose.onNodeWithText("后续步骤").getUnclippedBoundsInRoot()
        assertTrue("触摸区域至少为 48dp", entryBounds.height >= 48.dp)
        assertTrue("连接线所在的间距不属于按钮", following.top > entryBounds.bottom)

        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val area = compose.onNodeWithTag("reply-area").fetchSemanticsNode().boundsInRoot
        val lineX = (area.left - root.left).toInt() + with(compose.density) { 12.dp.roundToPx() }
        val yStart = with(compose.density) { (following.top - 8.dp).roundToPx() } - root.top.toInt()
        val yEnd = with(compose.density) { (following.top - 2.dp).roundToPx() } - root.top.toInt()
        assertTrue("连接线应存在", before.getPixel(lineX, yStart) != before.getPixel(lineX + 6, yStart))
        compose.mainClock.autoAdvance = false
        entry.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(160)
        val pressed = compose.onRoot().captureToImage().asAndroidBitmap()
        for (y in yStart until yEnd) for (x in lineX - 2..lineX + 2) {
            assertEquals("按压不改变连接线像素 ($x, $y)", before.getPixel(x, y), pressed.getPixel(x, y))
        }
        entry.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        assertEquals(listOf(source), openedSources.single())
        assertTrue(openedPages.isEmpty())
    }

    @Test
    fun expandedThinkingKeepsBrowsingCompactAndFindKeywordsVisible() {
        show(
            thinking,
            Step.Open(pages.map { it.copy(status = ToolStatus.Done) }),
            Step.Find(listOf(FindItem("find", "响应式布局", ToolStatus.Done))),
        )
        compose.onNodeWithText("正在思考").performClick()
        val browsing = compose.onNodeWithText("浏览 7 个页面").assertIsDisplayed().getUnclippedBoundsInRoot()
        val followingStep = compose.onNodeWithText("查找关键词").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(browsing.bottom < followingStep.top)
        pages.forEach { compose.onNodeWithText(it.title).assertDoesNotExist() }
        compose.onNodeWithText("响应式布局").assertIsDisplayed()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File("build/ui-review-cot").apply { mkdirs() }
        File(output, "expanded.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        compose.runOnIdle {
            streaming = false
            hasResponse = true
        }
        compose.onNodeWithText("已思考（用时 2 秒）").assertIsDisplayed()
        compose.onNodeWithText("浏览 7 个页面").assertIsDisplayed().performClick()
        assertEquals(pages.map { it.url }, openedPages.single().map { it.url })
    }

    @Test
    fun peekInterceptsPageListTapsUntilExpanded() {
        show(longThinking, Step.Open(pages.map { it.copy(status = ToolStatus.Done) }))
        val initialHeight = compose.onNodeWithTag("reply-area").getUnclippedBoundsInRoot().height
        assertEquals(180.dp, initialHeight)
        compose.onNodeWithText("浏览 7 个页面").assertIsDisplayed().performTouchInput { click() }
        assertTrue(openedPages.isEmpty())
        assertTrue(openedUrls.isEmpty())
        compose.onNodeWithText("浏览 7 个页面").performTouchInput { click() }
        assertEquals(pages.map { it.url }, openedPages.single().map { it.url })
    }

    @Test
    fun untouchedPeekAnimatesClosedWhenResponseArrives() {
        show(longThinking, Step.ReadLink(pages))
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
        compose.onNodeWithText("正在阅读").assertDoesNotExist()
        assertEquals(48.dp, compose.onNodeWithTag("reply-area").getUnclippedBoundsInRoot().height)
    }
}
