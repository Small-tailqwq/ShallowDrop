package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeDirection
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class ReplySwipeDialogTest {
    @get:Rule val compose = createComposeRule()
    private var saved: ReplySwipeSettings? = null

    private fun show() = compose.setContent {
        ShallowDropTheme { ReplySwipeDialog(ReplySwipeSettings(), onSave = { saved = it }, onDismiss = {}) }
    }

    @Test fun enablingDirectionAndDistancesSaveTogether() {
        show()
        compose.onNodeWithText("启用").performClick()
        compose.onNodeWithText("从左向右").performClick()
        compose.onNodeWithContentDescription("边缘死区").performSemanticsAction(SemanticsActions.SetProgress) { it(64f) }
        compose.onNodeWithContentDescription("触发距离").performSemanticsAction(SemanticsActions.SetProgress) { it(128f) }
        assertNull(saved)
        compose.onNodeWithText("确定").performClick()
        assertEquals(ReplySwipeSettings(true, ReplySwipeDirection.LeftToRight, 64, 128), saved)
    }

    @Test fun cancelDoesNotSaveChanges() {
        show()
        compose.onNodeWithText("启用").performClick()
        compose.onNodeWithText("取消").performClick()
        assertNull(saved)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-xhdpi", fontScale = 2f)
    fun narrowLargeSystemFontKeepsConfirmationVisible() {
        show()
        compose.onNodeWithText("确定").assertIsDisplayed().performClick()
        assertEquals(ReplySwipeSettings(), saved)
    }
}
