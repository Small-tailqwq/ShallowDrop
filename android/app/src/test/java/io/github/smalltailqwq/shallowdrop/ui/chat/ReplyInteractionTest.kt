package io.github.smalltailqwq.shallowdrop.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.core.contract.TurnView
import io.github.smalltailqwq.shallowdrop.core.model.Message
import io.github.smalltailqwq.shallowdrop.core.model.ReplyBlock
import io.github.smalltailqwq.shallowdrop.core.model.ReplyStatus
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeDirection
import io.github.smalltailqwq.shallowdrop.ui.chat.message.AssistantMessage
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
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
class ReplyInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var regenerated = 0
    private val text = "这是一段可以重新生成的回复，用于阅读和横向滑动。".repeat(3)

    private fun show(last: Boolean = true, busy: Boolean = false, body: String = text,
        swipe: ReplySwipeSettings = ReplySwipeSettings(enabled = true)) {
        val turn = TurnView(Message.Assistant("a", 0, status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text(body))),
            0, 1, canRegenerate = true)
        compose.setContent {
            ShallowDropTheme {
                Surface {
                    AssistantMessage(turn, last, false, busy, { regenerated++ }, {}, {}, {}, {}, Modifier.padding(16.dp), swipeSettings = swipe)
                }
            }
        }
    }

    private fun swipeReplyLeft() = compose.onNodeWithText(text).performTouchInput {
        swipe(Offset(width * 0.85f, centerY), Offset(width * 0.1f, centerY), 250)
    }

    private fun swipeReplyRight() = compose.onNodeWithText(text).performTouchInput {
        swipe(Offset(width * 0.15f, centerY), Offset(width * 0.9f, centerY), 250)
    }

    @Test fun onlyConfiguredDirectionRegeneratesOnRelease() {
        show()
        swipeReplyRight()
        assertEquals(0, regenerated)
        swipeReplyLeft()
        assertEquals(1, regenerated)
    }

    @Test fun leftToRightCanBeSelected() {
        show(swipe = ReplySwipeSettings(enabled = true, direction = ReplySwipeDirection.LeftToRight))
        swipeReplyRight()
        assertEquals(1, regenerated)
    }

    @Test fun defaultSettingsLeaveHorizontalGesturesAlone() {
        show(swipe = ReplySwipeSettings())
        swipeReplyLeft()
        swipeReplyRight()
        assertEquals(0, regenerated)
    }

    @Test fun startingInEdgeDeadZoneDoesNotRegenerate() {
        show(swipe = ReplySwipeSettings(enabled = true, direction = ReplySwipeDirection.LeftToRight, edgeDeadZoneDp = 64))
        compose.onNodeWithText(text).performTouchInput { swipe(Offset(10f, centerY), Offset(width - 10f, centerY), 250) }
        assertEquals(0, regenerated)
        compose.onNodeWithText(text).performTouchInput { swipe(Offset(centerX, centerY), Offset(width - 10f, centerY), 250) }
        assertEquals(1, regenerated)
    }

    @Test fun triggerDistanceIsConfigurable() {
        show(swipe = ReplySwipeSettings(enabled = true, triggerDistanceDp = 160))
        compose.onNodeWithText(text).performTouchInput { swipe(Offset(centerX, centerY), Offset(10f, centerY), 250) }
        assertEquals(0, regenerated)
        swipeReplyLeft()
        assertEquals(1, regenerated)
    }

    @Test fun shortAndVerticalDragsDoNotRegenerate() {
        show()
        compose.onNodeWithText(text).performTouchInput { swipe(center, center + Offset(40f, 0f), 400) }
        compose.onNodeWithText(text).performTouchInput { swipe(Offset(centerX, 5f), Offset(centerX, height - 5f), 400) }
        assertEquals(0, regenerated)
    }

    @Test fun historicalReplyHasNoRegenerateActionOrSwipe() {
        show(last = false)
        compose.onAllNodesWithContentDescription("重新生成").assertCountEquals(0)
        swipeReplyLeft()
        assertEquals(0, regenerated)
    }

    @Test fun generationInProgressDisablesRegeneration() {
        show(busy = true)
        compose.onAllNodesWithContentDescription("重新生成").assertCountEquals(0)
        swipeReplyRight()
        assertEquals(0, regenerated)
    }

    @Test
    // API 28+ 的放大镜需要真实 Surface，Robolectric 无法创建。
    @Config(sdk = [27])
    fun longPressSelectionDoesNotTriggerRegeneration() {
        show(swipe = ReplySwipeSettings(enabled = true, direction = ReplySwipeDirection.LeftToRight))
        compose.onNodeWithText(text).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(Offset(width - 10f, centerY))
            up()
        }
        assertEquals(0, regenerated)
    }

    @Test fun edgeDeadZoneKeepsDrawerOpeningGesture() {
        val drawer = DrawerState(DrawerValue.Closed)
        val turn = TurnView(Message.Assistant("a", 0, status = ReplyStatus.Done, blocks = listOf(ReplyBlock.Text(text))), 0, 1, canRegenerate = true)
        compose.setContent {
            ShallowDropTheme {
                Surface {
                    ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet { Text("会话") } }) {
                        AssistantMessage(turn, true, false, false, { regenerated++ }, {}, {}, {}, {}, Modifier.padding(16.dp),
                            swipeSettings = ReplySwipeSettings(true, ReplySwipeDirection.LeftToRight, 64))
                    }
                }
            }
        }
        compose.onNodeWithText(text).performTouchInput { swipe(Offset(10f, centerY), Offset(width - 10f, centerY), 250) }
        compose.waitForIdle()
        assertTrue(drawer.isOpen)
        assertEquals(0, regenerated)
    }

    @Test fun codeBlockKeepsItsHorizontalScrollGesture() {
        show(body = "```kotlin\nval a = " + "1234567890".repeat(30) + "\n```")
        val code = compose.onAllNodes(hasScrollAction())[0]
        code.performTouchInput { swipeLeft() }
        assertTrue(code.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        assertEquals(0, regenerated)
    }
}
