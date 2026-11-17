package io.github.smalltailqwq.shallowdrop.ui.settings

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
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
    private val ticks = mutableListOf<HapticFeedbackType>()
    private val haptic = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { ticks += hapticFeedbackType }
    }

    private fun showFontSize() = compose.setContent {
        CompositionLocalProvider(LocalHapticFeedback provides haptic) {
            ShallowDropTheme { FontSizeScreen(ChatFontSize.Standard, onSave = { savedSize = it }, onBack = { leftPage = true }) }
        }
    }

    private fun showSwipe(initial: ReplySwipeSettings = ReplySwipeSettings()) = compose.setContent {
        CompositionLocalProvider(LocalHapticFeedback provides haptic) {
            ShallowDropTheme { ReplySwipeScreen(initial, onSave = { savedSwipe = it }, onBack = { leftPage = true }) }
        }
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

    private fun progress(title: String) = compose.onNodeWithContentDescription(title)
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test
    fun fontSizeDragPreviewsIntermediateSizeThenSnapsOnRelease() {
        showFontSize()
        val slider = compose.onNodeWithContentDescription("聊天字号")
        slider.performTouchInput {
            down(Offset(width / 3f, centerY))
            moveTo(Offset(width * .57f, centerY), 160)
        }
        assertTrue(progress("聊天字号") in 1.2f..1.9f)
        assertTrue(fontSizeOf("画一只鹈鹕骑自行车") in 16.4f..17.8f)
        assertNull(savedSize)
        slider.performTouchInput { up() }
        assertEquals(2f, progress("聊天字号"))
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(ChatFontSize.Large, savedSize)
    }

    @Test
    fun tappingFontSizeLabelAnimatesToItsTick() {
        showFontSize()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("特大").performClick()
        compose.mainClock.advanceTimeBy(64)
        assertTrue(progress("聊天字号") > 1f && progress("聊天字号") < 3f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(3f, progress("聊天字号"))
        assertNull(savedSize)
    }

    @Test
    fun fontSizeHapticFollowsChangedSelectionWithoutAnimationOrRestoreTicks() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptic) {
                ShallowDropTheme { FontSizeScreen(ChatFontSize.Standard, onSave = { savedSize = it }, onBack = {}) }
            }
        }
        assertTrue(ticks.isEmpty())
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("大").performClick()
        assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick), ticks)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(1, ticks.size)
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(2f, progress("聊天字号"))
        compose.onNodeWithText("大").performClick()
        assertEquals(1, ticks.size)
        compose.onNodeWithText("特大").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.SegmentTick), ticks)
        assertNull(savedSize)
    }

    @Test
    fun horizontalLabelsShareTheThumbCenters() {
        showFontSize()
        capture("font-size-portrait.png")
        assertLabelCenters(vertical = false)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-xxhdpi")
    fun landscapeUsesANarrowVerticalSliderAndAlignedLabels() {
        showFontSize()
        capture("font-size-landscape.png")
        val slider = compose.onNodeWithContentDescription("聊天字号").fetchSemanticsNode().boundsInRoot
        assertTrue(slider.height > slider.width * 2)
        assertTrue(compose.onNodeWithTag("preview-controls").fetchSemanticsNode().boundsInRoot.width < 200f * compose.density.density)
        assertLabelCenters(vertical = true)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-xxhdpi")
    fun verticalSliderSupportsUpwardDragKeyboardAndAccessibility() {
        showFontSize()
        val slider = compose.onNodeWithContentDescription("聊天字号")
        slider.performTouchInput {
            down(Offset(centerX, height * 2f / 3))
            moveTo(Offset(centerX, height * .40f), 160)
        }
        assertTrue(progress("聊天字号") > 1f && progress("聊天字号") < 2f)
        slider.performTouchInput { up() }
        assertEquals(2f, progress("聊天字号"))
        slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        slider.performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(1f, progress("聊天字号"))
        slider.performKeyInput { pressKey(Key.DirectionUp) }
        assertEquals(2f, progress("聊天字号"))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2.8f) }
        assertEquals(3f, progress("聊天字号"))
        assertNull(savedSize)
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(ChatFontSize.ExtraLarge, savedSize)
    }

    private fun assertLabelCenters(vertical: Boolean) {
        listOf("小", "标准", "大", "特大").forEachIndexed { index, label ->
            compose.onNodeWithContentDescription("聊天字号")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
            val thumb = compose.onNodeWithTag("preview-slider-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            val difference = if (vertical) thumb.center.y - text.center.y else thumb.center.x - text.center.x
            assertTrue("$label center differs by $difference px", abs(difference) <= 1.01f)
        }
    }

    private fun capture(name: String) {
        val output = File("build/ui-review").apply { mkdirs() }
        File(output, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun swipeDistanceDragIsContinuousAndSettlesToFourDp() {
        showSwipe(ReplySwipeSettings(enabled = true))
        val slider = compose.onNodeWithContentDescription("边缘死区")
        val bounds = slider.fetchSemanticsNode().boundsInRoot
        val thumb = compose.onAllNodesWithTag("preview-slider-thumb", useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val origin = thumb.center - bounds.topLeft
        val pixelsPerDp = (bounds.width - thumb.width) / 96f
        slider.performTouchInput {
            down(origin)
            moveTo(origin + Offset(pixelsPerDp * 17f, 0f), 160)
        }
        val moving = progress("边缘死区")
        assertTrue(moving > 32f && moving < 49f)
        slider.performTouchInput { moveTo(origin + Offset(pixelsPerDp * 17.375f, 0f), 16) }
        val advanced = progress("边缘死区")
        assertEquals(.375f, advanced - moving, .03f)
        val snapped = (advanced / 4).roundToInt() * 4
        slider.performTouchInput { up() }
        assertEquals(snapped.toFloat(), progress("边缘死区"))
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(snapped, savedSwipe!!.edgeDeadZoneDp)
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
    fun unchangedSwipeDistancesAreNotRoundedOnLeave() {
        showSwipe(ReplySwipeSettings(edgeDeadZoneDp = 31, triggerDistanceDp = 97))
        compose.onNodeWithContentDescription("返回").performClick()
        assertNull(savedSwipe)
        assertTrue(ticks.isEmpty())
        assertTrue(leftPage)
    }

    @Test
    fun swipeHapticUsesFourDpTicksAndLimitsRapidFeedback() {
        showSwipe(ReplySwipeSettings(enabled = true))
        val slider = compose.onNodeWithContentDescription("边缘死区")
        assertTrue(ticks.isEmpty())
        slider.performSemanticsAction(SemanticsActions.SetProgress) { set ->
            set(33f)
            assertTrue(ticks.isEmpty())
            set(34.1f)
            set(35.9f)
            assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick), ticks)
            set(40f)
            assertEquals(1, ticks.size)
            set(96f)
            set(96f)
        }
        assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.SegmentTick), ticks)
        assertEquals(96f, progress("边缘死区"))
        assertNull(savedSwipe)
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
