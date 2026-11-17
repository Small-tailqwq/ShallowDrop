package io.github.smalltailqwq.shallowdrop.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.ui.common.ConfirmDialog
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class CompactionDialogTest {
    @get:Rule val compose = createComposeRule()
    private var saved: CompactionThresholds? = null
    private var savedEnabled: Boolean? = null
    private val ticks = mutableListOf<HapticFeedbackType>()

    private fun show() {
        compose.setContent { ShallowDropTheme { CompactionDialog(CompactionThresholds(), onSave = { thresholds, enabled -> saved = thresholds; savedEnabled = enabled }, onDismiss = {}) } }
    }
    private fun field(start: Boolean) = compose.onNodeWithTag(if (start) "compaction_background_value" else "compaction_foreground_value")
    private fun pin(start: Boolean) = compose.onNodeWithTag(if (start) "compaction_background_slider" else "compaction_foreground_slider")
    private fun progress(start: Boolean) = pin(start).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun move(start: Boolean, value: Float) = pin(start).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    private fun assertFullyVisible(target: SemanticsNodeInteraction) {
        target.assertIsDisplayed()
        val node = target.fetchSemanticsNode()
        assertEquals("内容不应被水平裁切", node.size.width.toFloat(), node.boundsInRoot.width, 1f)
        assertEquals("内容不应被垂直裁切", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
    }

    @Test fun defaultsAndPreciseNumbersSaveTogether() {
        show()
        assertEquals(65f, progress(true))
        assertEquals(80f, progress(false))
        field(true).performTextReplacement("45")
        field(false).performTextReplacement("65")
        compose.onNodeWithText("确定").performClick()
        assertEquals(CompactionThresholds(45, 65), saved)
        assertEquals(true, savedEnabled)
    }

    @Test fun numericCommitClampsOrderGapAndUpperLimit() {
        show()
        field(true).performTextReplacement("94")
        field(true).performImeAction()
        field(true).assertTextContains("75")
        field(false).performTextReplacement("90")
        field(false).performImeAction()
        field(false).assertTextContains("80")
        field(false).performTextReplacement("2")
        field(false).performImeAction()
        field(false).assertTextContains("80")
        field(true).performTextReplacement("0")
        field(true).performImeAction()
        field(true).assertTextContains("1")
        field(false).performTextReplacement("0")
        field(false).performImeAction()
        field(false).assertTextContains("6")
        compose.onNodeWithText("确定").performClick()
        assertEquals(CompactionThresholds(1, 6), saved)
    }

    @Test fun typingWaitingThresholdDoesNotClampFirstDigit() {
        show()
        field(false).performTextClearance()
        field(false).performTextInput("7")
        field(false).assertTextContains("7")
        assertEquals(80f, progress(false))
        field(false).performTextInput("5")
        field(false).performImeAction()
        assertEquals(75f, progress(false))
        assertEquals(65f, progress(true))
    }

    @Test fun switchDisablesControlsAndKeepsThresholdsWhenReenabled() {
        show()
        field(true).performTextReplacement("45")
        field(false).performTextReplacement("65")
        compose.onNodeWithTag("compaction_enabled").performClick().assertIsOff()
        field(true).assertIsNotEnabled()
        field(false).assertIsNotEnabled()
        pin(true).assertIsNotEnabled()
        pin(false).assertIsNotEnabled()
        compose.onNodeWithText("关闭后使用完整历史，上下文满后需新建对话。").assertIsDisplayed()
        compose.onNodeWithTag("compaction_enabled").performClick().assertIsOn()
        field(true).assertIsEnabled().assertTextContains("45")
        field(false).assertIsEnabled().assertTextContains("65")
        compose.onNodeWithTag("compaction_enabled").performClick()
        compose.onNodeWithText("确定").performClick()
        assertEquals(false, savedEnabled)
        assertEquals(CompactionThresholds(45, 65), saved)
    }

    @Test fun disabledDraftSurvivesRecreationAndCancelDoesNotSave() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ShallowDropTheme {
            CompactionDialog(CompactionThresholds(45, 65), onSave = { thresholds, enabled -> saved = thresholds; savedEnabled = enabled }, onDismiss = {})
        } }
        compose.onNodeWithTag("compaction_enabled").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("compaction_enabled").assertIsOff()
        field(true).assertIsNotEnabled().assertTextContains("45")
        compose.onNodeWithText("取消").performClick()
        assertNull(saved)
        assertNull(savedEnabled)
    }

    @Test fun draggingCannotCrossPinsAndPinsRemainSeparatelyTouchable() {
        show()
        pin(true).performTouchInput { swipe(center, center + Offset(500f, 0f), 300) }
        assertEquals(75f, progress(true))
        assertEquals(80f, progress(false))
        pin(false).performTouchInput { swipe(center, center - Offset(500f, 0f), 300) }
        assertEquals(80f, progress(false))
        val start = pin(true).fetchSemanticsNode().boundsInRoot
        val wait = pin(false).fetchSemanticsNode().boundsInRoot
        assertTrue(wait.bottom <= start.top)
        move(true, 1f)
        move(false, 0f)
        assertEquals(1f, progress(true))
        assertEquals(6f, progress(false))
    }

    @Test fun hapticOnlyForChangedIntegerAndDistinctAtBoundary() {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { ticks += hapticFeedbackType }
            }) {
                var thresholds by remember { mutableStateOf(CompactionThresholds()) }
                ShallowDropTheme { CompactionScale(thresholds, Color.Blue, Color.Green, true, onChange = { thresholds = it }) }
            }
        }
        assertTrue(ticks.isEmpty())
        move(true, 72f)
        assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick), ticks)
        move(true, 99f)
        assertEquals(75f, progress(true))
        assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.SegmentTick), ticks)
        move(true, 99f)
        move(false, 100f)
        assertEquals(2, ticks.size)
    }

    @Test fun manualEntryAnimatesPinToExactPosition() {
        show()
        compose.mainClock.autoAdvance = false
        field(true).performTextReplacement("45")
        compose.mainClock.advanceTimeBy(48)
        assertTrue(progress(true) > 45f && progress(true) <= 65f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(45f, progress(true))
    }

    @Test fun reservedRegionStripesStayInsideTrackWithStraightThresholdEdge() {
        var enabled by mutableStateOf(true)
        compose.setContent {
            ShallowDropTheme {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
                    surfaceContainerHighest = Color.White, onSurfaceVariant = Color.Black, surface = Color.White,
                )) {
                    Box(Modifier.width(360.dp).background(Color.White)) {
                        Box(Modifier.alpha(if (enabled) 1f else .5f)) {
                            CompactionScale(CompactionThresholds(45, 65), Color.Blue, Color.Green, enabled, onChange = {})
                        }
                    }
                }
            }
        }
        val output = File("build/ui-review").apply { mkdirs() }
        for (active in listOf(true, false)) {
            compose.runOnIdle { enabled = active }
            val bitmap = compose.onNodeWithTag("compaction_scale").captureToImage().asAndroidBitmap()
            File(output, "compaction-track-${if (active) "enabled" else "disabled"}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrackStripePixels(bitmap)
        }
    }

    private fun assertTrackStripePixels(bitmap: Bitmap) {
        val density = compose.density.density
        val right = bitmap.width - 24f * density
        val centerY = 104f * density
        val radius = 7f * density
        val centerX = right - radius
        val cap = 24f * density + (bitmap.width - 48f * density) * .8f
        val white = android.graphics.Color.WHITE
        var checked = 0
        for (y in (centerY - radius).toInt() until (centerY + radius).toInt()) {
            for (x in centerX.toInt() until right.toInt()) {
                val dx = x + .5f - centerX
                val dy = y + .5f - centerY
                if (dx * dx + dy * dy > (radius + 1f) * (radius + 1f)) {
                    assertEquals("圆角外不应有条纹 ($x, $y)", white, bitmap.getPixel(x, y))
                    checked++
                }
            }
        }
        assertTrue("应检查实际圆角外像素", checked > 0)
        val interior = ((cap + 8f * density).toInt() until (centerX - 2f * density).toInt())
            .map { bitmap.getPixel(it, centerY.roundToInt()) }.toSet()
        assertTrue("保留区域内部应有条纹", interior.any { it != white })
        assertTrue("条纹之间应保留轨道底色", white in interior)
        val edgeY = (centerY + radius - .75f * density).roundToInt()
        assertNotEquals("80% 边界应保持直边", white, bitmap.getPixel((cap + .75f * density).roundToInt(), edgeY))
        assertEquals("条纹不应越过 80% 边界", white, bitmap.getPixel((cap - 1f * density).roundToInt(), edgeY))
    }

    @Test fun emptyDraftRestoresAndCommitUsesLastValidValue() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ShallowDropTheme {
            CompactionDialog(CompactionThresholds(), onSave = { thresholds, enabled -> saved = thresholds; savedEnabled = enabled }, onDismiss = {})
        } }
        field(true).performTextClearance()
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(65f, progress(true))
        compose.onNodeWithText("确定").performClick()
        assertEquals(CompactionThresholds(), saved)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-xhdpi", fontScale = 2f)
    fun narrowLargeFontCanEditAndConfirm() {
        show()
        field(true).performScrollTo().performTextReplacement("45")
        field(false).performScrollTo().performTextReplacement("65")
        field(false).performImeAction()
        compose.onNodeWithText("确定").assertIsDisplayed().performClick()
        assertEquals(CompactionThresholds(45, 65), saved)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-xhdpi")
    fun landscapeShowsScaleBesideFieldsAndKeepsActionsVisible() {
        show()
        val field = field(true).getUnclippedBoundsInRoot()
        val scale = compose.onNodeWithTag("compaction_scale").getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithText("确定").getUnclippedBoundsInRoot()
        assertTrue("刻度应与输入并排", field.right < scale.left)
        assertTrue("刻度和标签应在按钮上方完整可见", scale.bottom <= confirm.top)
        assertFullyVisible(compose.onNodeWithTag("compaction_scale"))
        assertFullyVisible(compose.onNodeWithTag("compaction_limits").performScrollTo())
        assertFullyVisible(compose.onNodeWithText("取消"))
        assertFullyVisible(compose.onNodeWithText("确定"))
        compose.onNodeWithText("确定").performClick()
        assertEquals(CompactionThresholds(), saved)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-xhdpi", fontScale = 2f)
    fun landscapeLargeFontCanScrollThroughControlsAndConfirm() {
        show()
        assertFullyVisible(field(true).performScrollTo())
        field(true).performScrollTo().performTextReplacement("45")
        assertFullyVisible(field(false).performScrollTo())
        field(false).performScrollTo().performTextReplacement("65")
        field(false).performImeAction()
        assertFullyVisible(pin(false).performScrollTo())
        assertFullyVisible(pin(true).performScrollTo())
        assertFullyVisible(compose.onNodeWithTag("compaction_limits").performScrollTo())
        assertFullyVisible(compose.onNodeWithText("取消"))
        assertFullyVisible(compose.onNodeWithText("确定"))
        compose.onNodeWithText("确定").performClick()
        assertEquals(CompactionThresholds(45, 65), saved)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h240dp-land-xhdpi")
    fun shortLandscapeCanScrollToFieldsAndConfirm() {
        show()
        assertFullyVisible(field(true).performScrollTo())
        field(true).performScrollTo().performTextReplacement("45")
        assertFullyVisible(field(false).performScrollTo())
        field(false).performScrollTo().performTextReplacement("65")
        field(false).performImeAction()
        assertFullyVisible(compose.onNodeWithText("取消"))
        assertFullyVisible(compose.onNodeWithText("确定"))
        compose.onNodeWithText("确定").performClick()
        assertEquals(CompactionThresholds(45, 65), saved)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-xhdpi", fontScale = 2f)
    fun longConfirmationScrollsWhileActionsStayVisible() {
        val body = "需要确认的会话内容。".repeat(60)
        compose.setContent {
            ShallowDropTheme { ConfirmDialog("确认操作", body, "确定", {}, {}) }
        }
        val text = compose.onNodeWithText(body)
        val range = text.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > 0f)
        text.performTouchInput { swipeUp() }
        assertTrue(range.value() > 0f)
        assertFullyVisible(compose.onNodeWithText("取消"))
        assertFullyVisible(compose.onNodeWithText("确定"))
        compose.onNodeWithText("确定").performClick()
    }
}

