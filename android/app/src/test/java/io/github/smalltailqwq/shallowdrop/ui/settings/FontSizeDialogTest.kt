package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize
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
class FontSizeDialogTest {
    @get:Rule val compose = createComposeRule()
    private var saved: ChatFontSize? = null
    private var dismissed = false

    private fun show() {
        compose.setContent {
            ShallowDropTheme {
                FontSizeDialog(ChatFontSize.Standard, onSave = { saved = it }, onDismiss = { dismissed = true })
            }
        }
    }

    private fun select(index: Float) {
        compose.onNodeWithContentDescription("聊天字号").performSemanticsAction(SemanticsActions.SetProgress) { it(index) }
    }

    @Test
    fun sliderPreviewsTextBeforeSaving() {
        show()
        select(3f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("预览聊天字号").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(20.sp, layouts.single().layoutInput.style.fontSize)
        assertNull(saved)
        compose.onNodeWithText("确定").performClick()
        assertEquals(ChatFontSize.ExtraLarge, saved)
    }

    @Test
    fun cancelDiscardsPreview() {
        show()
        select(0f)
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
        assertNull(saved)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-xhdpi", fontScale = 2f)
    fun narrowLargeSystemFontCanConfirm() {
        show()
        select(2f)
        compose.onNodeWithText("确定").assertIsDisplayed().performClick()
        assertEquals(ChatFontSize.Large, saved)
    }
}
