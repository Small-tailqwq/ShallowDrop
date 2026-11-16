package io.github.smalltailqwq.shallowdrop.ui.voice

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbDynamicsTest {
    private val frame = 1f / 60f

    private fun OrbDynamics.run(seconds: Float, mood: OrbMood, input: Float = 0f, pressed: Boolean = false) {
        repeat((seconds / frame).toInt()) { step(frame, mood, input, pressed = pressed) }
    }

    @Test
    fun levelRisesFastAndFallsSlowly() {
        val orb = OrbDynamics()
        orb.run(0.1f, OrbMood.Listening, input = 1f)
        val risen = orb.level
        assertTrue("开口 0.1 秒应已明显响应，实际 $risen", risen > 0.7f)
        orb.run(0.1f, OrbMood.Listening, input = 0f)
        assertTrue("停顿 0.1 秒不应立刻落回，实际 ${orb.level}", orb.level > risen * 0.55f)
        orb.run(2f, OrbMood.Listening, input = 0f)
        assertEquals(0f, orb.level, 0.01f)
    }

    @Test
    fun levelFallsFasterWhileSpeaking() {
        val listening = OrbDynamics().apply { run(0.5f, OrbMood.Listening, input = 1f) }
        val speaking = OrbDynamics().apply { run(0.5f, OrbMood.Speaking, input = 1f) }
        listening.run(0.15f, OrbMood.Listening, input = 0f)
        speaking.run(0.15f, OrbMood.Speaking, input = 0f)
        assertTrue("朗读的音节间隙应看得出回落", speaking.level < listening.level * 0.6f)
    }

    @Test
    fun levelIsIgnoredOutsideListeningAndSpeaking() {
        val orb = OrbDynamics()
        orb.run(1f, OrbMood.Thinking, input = 1f)
        assertEquals(0f, orb.level, 0.001f)
    }

    @Test
    fun moodsBlendInsteadOfSwitching() {
        val orb = OrbDynamics()
        orb.run(1.5f, OrbMood.Listening)
        assertEquals(1f, orb.listening, 0.01f)
        assertEquals(0f, orb.thinking, 0.01f)

        orb.step(frame, OrbMood.Thinking, 0f)
        assertTrue("切到思考的第一帧波纹不应消失", orb.listening > 0.8f)
        assertTrue(orb.thinking > 0f && orb.thinking < 0.2f)

        orb.run(1.5f, OrbMood.Thinking)
        assertEquals(0f, orb.listening, 0.01f)
        assertEquals(1f, orb.thinking, 0.01f)

        orb.run(1.5f, OrbMood.Muted)
        assertEquals(1f, orb.dim, 0.01f)
    }

    @Test
    fun scaleSettlesPerMoodAndShrinksWhenPressed() {
        val orb = OrbDynamics()
        assertEquals(OrbDynamics.ENTER_SCALE, orb.scale, 0f)
        orb.run(2f, OrbMood.Muted)
        assertEquals(0.76f, orb.scale, 0.005f)
        orb.run(2f, OrbMood.Stopped)
        assertEquals(0.72f, orb.scale, 0.005f)
        orb.run(2f, OrbMood.Speaking, input = 1f)
        val loud = orb.scale
        assertEquals(1.04f, loud, 0.01f)
        orb.run(2f, OrbMood.Speaking, input = 1f, pressed = true)
        assertTrue(orb.scale < loud - 0.03f)
    }

    @Test
    fun ripplesOnlyAdvanceWhileListening() {
        val orb = OrbDynamics()
        orb.run(2f, OrbMood.Muted)
        val before = orb.ripple
        orb.run(1f, OrbMood.Muted)
        assertEquals(before, orb.ripple, 0.01f)
        orb.run(0.5f, OrbMood.Listening)
        assertTrue(abs(orb.ripple - before) > 0.1f)
    }

    @Test
    fun flowKeepsMovingAndSpeedsUpWithEnergy() {
        val calm = OrbDynamics().apply { run(3f, OrbMood.Muted) }
        val busy = OrbDynamics().apply { run(3f, OrbMood.Thinking) }
        assertTrue(calm.flow > 0f)
        assertTrue(busy.flow > calm.flow * 2f)
        val fast = OrbDynamics().apply { repeat(180) { step(frame, OrbMood.Preview, 0f, speed = 2f) } }
        val slow = OrbDynamics().apply { repeat(180) { step(frame, OrbMood.Preview, 0f, speed = 1f) } }
        assertEquals(slow.flow * 2f, fast.flow, 0.001f)
    }

    @Test
    fun longFrameGapStaysStable() {
        val orb = OrbDynamics()
        orb.run(1f, OrbMood.Listening, input = 0.5f)
        // 从后台回来：一帧隔了十秒
        orb.step(10f, OrbMood.Speaking, 1f)
        assertTrue(orb.scale.isFinite() && orb.scale in 0.5f..1.3f)
        orb.step(-1f, OrbMood.Speaking, 1f)
        orb.step(Float.MAX_VALUE, OrbMood.Speaking, 5f)
        assertTrue(orb.scale.isFinite() && orb.scale in 0.5f..1.3f)
        assertTrue(orb.level in 0f..1f)
    }
}
