package io.github.smalltailqwq.shallowdrop.ui.easteregg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTapTriggerTest {
    @Test
    fun sevenConsecutiveTapsOpenOnceAndNextVisitNeedsSevenAgain() {
        val trigger = VersionTapTrigger()
        repeat(6) { assertFalse(trigger.tap(it * 120L)) }
        assertTrue(trigger.tap(720))
        repeat(6) { assertFalse(trigger.tap(840 + it * 120L)) }
        assertTrue(trigger.tap(1560))
    }

    @Test
    fun pauseBreaksTheSequence() {
        val trigger = VersionTapTrigger()
        repeat(6) { trigger.tap(it * 100L) }
        assertFalse(trigger.tap(1401))
        repeat(5) { assertFalse(trigger.tap(1500 + it * 100L)) }
        assertTrue(trigger.tap(2000))
    }

    @Test
    fun leavingSettingsDiscardsPartialSequence() {
        val trigger = VersionTapTrigger()
        repeat(6) { trigger.tap(it * 100L) }
        trigger.reset()
        assertFalse(trigger.tap(600))
    }
}
