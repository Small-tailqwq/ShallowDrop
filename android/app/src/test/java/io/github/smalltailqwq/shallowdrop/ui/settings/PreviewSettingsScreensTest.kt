package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeDirection
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class PreviewSettingsScreensTest {
    @get:Rule val compose = createComposeRule()
    private var savedSize: ChatFontSize? = null
    private var savedSwipe: ReplySwipeSettings? = null
    private var leftPage = false

    private fun showFontSize() = compose.setContent {
        ShallowDropTheme { FontSizeScreen(ChatFontSize.Standard, onSave = { savedSize = it }, onBack = { leftPage = true }) }
    }

    private fun showSwipe(initial: ReplySwipeSettings = ReplySwipeSettings()) = compose.setContent {
        ShallowDropTheme { ReplySwipeScreen(initial, onSave = { savedSwipe = it }, onBack = { leftPage = true }) }
    }

    private fun fontSizeOf(text: String): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.fontSize.value
    }

    @Test
    fun fontSizeOnlyPreviewsUntilLeaving() {
        showFontSize()
        assertEquals(16f, fontSizeOf("画一只鹈鹕骑自行车"))
        compose.onNodeWithContentDescription("聊天字号").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        assertEquals(20.sp.value, fontSizeOf("画一只鹈鹕骑自行车"))
        assertNull(savedSize)
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(ChatFontSize.ExtraLarge, savedSize)
        assertTrue(leftPage)
    }

    @Test
    fun unchangedFontSizeIsNotWritten() {
        showFontSize()
        compose.onNodeWithContentDescription("返回").performClick()
        assertNull(savedSize)
        assertTrue(leftPage)
    }

    @Test
    fun swipeSettingsSaveTogetherOnLeave() {
        showSwipe()
        compose.onNodeWithText("启用").performClick()
        compose.onNodeWithText("从左向右").performClick()
        compose.onNodeWithContentDescription("边缘死区").performSemanticsAction(SemanticsActions.SetProgress) { it(64f) }
        compose.onNodeWithContentDescription("触发距离").performSemanticsAction(SemanticsActions.SetProgress) { it(128f) }
        assertNull(savedSwipe)
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(ReplySwipeSettings(true, ReplySwipeDirection.LeftToRight, 64, 128), savedSwipe)
    }

    @Test
    fun previewReplyCanBeSwipedToNextVersion() {
        showSwipe(ReplySwipeSettings(enabled = true, direction = ReplySwipeDirection.RightToLeft, edgeDeadZoneDp = 0, triggerDistanceDp = 64))
        compose.onNodeWithText("收到，逐条修改", substring = true).performScrollTo().performTouchInput { swipeLeft(right - 4f, left + 4f, 400) }
        compose.waitForIdle()
        compose.onNodeWithText("换个思路", substring = true).assertIsDisplayed()
        assertNull(savedSwipe)
    }

    @Test
    fun swipeInWrongDirectionKeepsVersion() {
        showSwipe(ReplySwipeSettings(enabled = true, direction = ReplySwipeDirection.RightToLeft, edgeDeadZoneDp = 0, triggerDistanceDp = 64))
        compose.onNodeWithText("收到，逐条修改", substring = true).performScrollTo().performTouchInput { swipeRight(left + 4f, right - 4f, 400) }
        compose.waitForIdle()
        compose.onNodeWithText("收到，逐条修改", substring = true).assertIsDisplayed()
    }
}
