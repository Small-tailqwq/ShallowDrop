package io.github.smalltailqwq.shallowdrop.ui.chat.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySwipePhysicsTest {
    @Test
    fun rubberBandFollowsThenResists() {
        val limit = 100f
        assertEquals(0f, ReplySwipePhysics.rubberBand(-5f, limit), 0f)
        // 起手近似 1:1
        assertEquals(1f, ReplySwipePhysics.rubberBand(1f, limit), 0.02f)
        // 单调递增且不超过上限
        var previous = 0f
        for (d in 10..2000 step 10) {
            val value = ReplySwipePhysics.rubberBand(d.toFloat(), limit)
            assertTrue(value > previous)
            assertTrue(value < limit)
            previous = value
        }
    }

    @Test
    fun visualOffsetAtThresholdIsAboutHalf() {
        val offset = ReplySwipePhysics.visualOffset(96f, 96f)
        assertEquals(96f * 0.9f / 1.9f, offset, 0.01f)
    }

    @Test
    fun deadZoneDependsOnStartSide() {
        // 从左向右滑：左边缘是死区
        assertTrue(ReplySwipePhysics.inDeadZone(10f, 400f, 32f, 1f))
        assertFalse(ReplySwipePhysics.inDeadZone(390f, 400f, 32f, 1f))
        // 从右向左滑：右边缘是死区
        assertTrue(ReplySwipePhysics.inDeadZone(390f, 400f, 32f, -1f))
        assertFalse(ReplySwipePhysics.inDeadZone(10f, 400f, 32f, -1f))
        // 死区为 0 时任何位置都可以起手
        assertFalse(ReplySwipePhysics.inDeadZone(0f, 400f, 0f, 1f))
    }

    @Test
    fun latchVibratesOncePerGesture() {
        val latch = ThresholdLatch()
        assertFalse(latch.update(50f, 96f))
        assertTrue(latch.update(100f, 96f))
        assertTrue(latch.armed)
        assertFalse(latch.update(120f, 96f))
        // 回到阈值内不震，再越过也不重复
        assertFalse(latch.update(60f, 96f))
        assertFalse(latch.armed)
        assertFalse(latch.update(110f, 96f))
        latch.reset()
        assertTrue(latch.update(96f, 96f))
    }
}
