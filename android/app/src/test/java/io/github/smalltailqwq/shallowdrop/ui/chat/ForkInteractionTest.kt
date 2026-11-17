package io.github.smalltailqwq.shallowdrop.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.ui.chat.message.AssistantMessage
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
class ForkInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var forked = 0
    private var regenerated = 0

    private fun show(last: Boolean = false, busy: Boolean = false, versions: Int = 1) {
        val turn = TurnView(
            Message.Assistant("a", 0, status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text("回答"))),
            versions - 1, versions, canRegenerate = last, canContinue = last, canFork = true,
        )
        compose.setContent {
            ShallowDropTheme {
                Surface {
                    AssistantMessage(turn, last, false, busy, { regenerated++ }, {}, {}, {}, {},
                        Modifier.padding(16.dp), onFork = { forked++ })
                }
            }
        }
    }

    @Test fun completedHistoricalReplyOffersFork() {
        show()
        compose.onNodeWithContentDescription("创建分支").performClick()
        assertEquals(1, forked)
    }

    @Test fun generationHidesFork() {
        show(busy = true)
        compose.onAllNodesWithContentDescription("创建分支").assertCountEquals(0)
    }

    @Test fun thousandsOfVersionsKeepAllActionsReachableOnNarrowScreen() {
        show(last = true, versions = 5001)
        compose.onNodeWithText("5001 / 5001").assertIsDisplayed()
        compose.onNodeWithContentDescription("创建分支").assertWidthIsAtLeast(32.dp).performClick()
        compose.onNodeWithContentDescription("重新生成").assertWidthIsAtLeast(32.dp).performClick()
        compose.onNodeWithText("继续生成").assertIsDisplayed()
        assertEquals(1, forked)
        assertEquals(1, regenerated)
    }
}
