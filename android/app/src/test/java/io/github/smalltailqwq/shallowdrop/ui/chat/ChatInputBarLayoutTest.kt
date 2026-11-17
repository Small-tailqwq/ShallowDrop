package io.github.smalltailqwq.shallowdrop.ui.chat

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.github.smalltailqwq.shallowdrop.core.contract.ConversationState
import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import java.io.File
import kotlin.math.abs
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
class ChatInputBarLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<String>()

    private fun show(state: ChatUiState = ChatUiState(), width: Dp = 320.dp) {
        compose.setContent {
            ShallowDropTheme {
                Surface {
                    Box(Modifier.width(width)) {
                        ChatInputBar(
                            state = state,
                            onTextChange = {},
                            onSend = { actions += "send" },
                            onStop = { actions += "stop" },
                            onToggleThinking = { actions += "thinking" },
                            onToggleSearch = { actions += "search" },
                            onAddAttachments = { _, _ -> },
                            onRemoveAttachment = {},
                            onRetryAttachment = {},
                            modifier = Modifier.testTag("input-bar"),
                        )
                    }
                }
            }
        }
    }

    private fun assertAligned(label: String, screenshot: String) {
        val attachment = compose.onNodeWithContentDescription("上传文件").getUnclippedBoundsInRoot()
        val action = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
        val lastToggle = compose.onNodeWithText("智能搜索").getUnclippedBoundsInRoot()
        assertEquals(40.dp, attachment.width)
        assertEquals(40.dp, attachment.height)
        assertEquals(36.dp, action.width)
        assertEquals(36.dp, action.height)
        val attachmentY = (attachment.top.value + attachment.bottom.value) / 2
        val actionY = (action.top.value + action.bottom.value) / 2
        val toggleY = (lastToggle.top.value + lastToggle.bottom.value) / 2
        assertEquals("附件与操作按钮的容器中心", attachmentY, actionY, 0.5f)
        assertEquals("操作区与最后一行胶囊的中心", toggleY, actionY, 0.5f)

        val attachmentInk = inkCenterY("上传文件")
        val actionInk = inkCenterY(label)
        assertEquals("附件与操作图标的可见像素中心", attachmentInk, actionInk, 1f)
        val bitmap = compose.onNodeWithTag("input-bar").captureToImage().asAndroidBitmap()
        val output = File("build/ui-review-input").apply { mkdirs() }
        File(output, "$screenshot.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("INPUT $screenshot containers_dp=$attachmentY/$actionY glyphs_px=$attachmentInk/$actionInk")
    }

    private fun inkCenterY(label: String): Float {
        val icon = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
        val bounds = icon.fetchSemanticsNode().boundsInRoot
        val bitmap = icon.captureToImage().asAndroidBitmap()
        val background = bitmap.getPixel(0, 0)
        var first = bitmap.height
        var last = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val difference = abs(Color.red(pixel) - Color.red(background)) +
                abs(Color.green(pixel) - Color.green(background)) + abs(Color.blue(pixel) - Color.blue(background))
            if (difference > 30) {
                first = minOf(first, y)
                last = maxOf(last, y)
            }
        }
        assertTrue("$label 应有可见图形", last >= first)
        return bounds.top + (first + last) / 2f
    }

    @Test fun emptyDraftAlignsAttachmentAndDisabledSend() {
        show()
        assertAligned("发送", "portrait-empty")
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        val insideTopEdge = with(compose.density) { 2.dp.toPx() }
        compose.onNodeWithContentDescription("上传文件").performTouchInput { click(Offset(centerX, insideTopEdge)) }
        compose.onNodeWithText("图片").assertIsDisplayed()
        compose.onNodeWithText("文件").assertIsDisplayed()
    }

    @Test fun multilineDraftKeepsSendAlignedAndTappable() {
        show(ChatUiState(draft = Draft("第一行\n第二行\n第三行")))
        assertAligned("发送", "portrait-multiline")
        val outsideVisualTop = with(compose.density) { -2.dp.toPx() }
        compose.onNodeWithContentDescription("发送").performTouchInput { click(Offset(centerX, outsideVisualTop)) }
        assertEquals(listOf("send"), actions)
    }

    @Test fun narrowInputAlignsControlsWithTheLastWrappedToggle() {
        show(ChatUiState(draft = Draft("待发送")), width = 240.dp)
        val thinking = compose.onNodeWithText("深度思考").getUnclippedBoundsInRoot()
        val search = compose.onNodeWithText("智能搜索").getUnclippedBoundsInRoot()
        assertTrue("窄屏应触发胶囊换行", search.top > thinking.bottom)
        assertAligned("发送", "portrait-narrow")
    }

    @Test fun generatingKeepsStopAlignedAndTappable() {
        show(ChatUiState(conversation = ConversationState(SessionSummary("s", "对话", 0, 0), emptyList(), generating = true)), width = 240.dp)
        assertAligned("停止生成", "portrait-stop")
        compose.onNodeWithContentDescription("停止生成").performTouchInput { click() }
        assertEquals(listOf("stop"), actions)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-xxhdpi")
    fun landscapeInputKeepsTheSameActionCenters() {
        show(ChatUiState(draft = Draft("横屏第一行\n横屏第二行")), width = 640.dp)
        assertAligned("发送", "landscape-multiline")
    }
}
