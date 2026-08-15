package com.letta.mobile.ui.components

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingShaderMotionTest {
    @Test
    fun `energy attack is faster than release`() {
        val start = ThinkingShaderMotionState()
        val attacked = ThinkingShaderMotion.advance(start, 0.1f, targetEnergy = 1f, motionScale = 1f)
        val released = ThinkingShaderMotion.advance(
            ThinkingShaderMotionState(streamEnergy = 1f),
            0.1f,
            targetEnergy = 0f,
            motionScale = 1f,
        )

        assertTrue(attacked.streamEnergy > 1f - released.streamEnergy)
        assertTrue(attacked.streamEnergy in 0f..1f)
        assertTrue(released.streamEnergy in 0f..1f)
    }

    @Test
    fun `changing energy preserves scan phase continuity`() {
        val before = ThinkingShaderMotionState(scanPhase = 0.42f, streamEnergy = 0.1f)
        val after = ThinkingShaderMotion.advance(before, 1f / 60f, targetEnergy = 1f, motionScale = 1f)

        assertTrue(after.scanPhase > before.scanPhase)
        assertTrue(after.scanPhase - before.scanPhase < 0.01f)
    }

    @Test
    fun `reduced motion freezes scan while energy settles`() {
        val before = ThinkingShaderMotionState(scanPhase = 0.73f, streamEnergy = 0f)
        val after = ThinkingShaderMotion.advance(before, 0.1f, targetEnergy = 1f, motionScale = 0f)

        assertEquals(before.scanPhase, after.scanPhase)
        assertTrue(after.streamEnergy > 0f)
    }

    @Test
    fun `large frame gaps are capped`() {
        val start = ThinkingShaderMotionState(scanPhase = 0.25f)
        val capped = ThinkingShaderMotion.advance(start, 10f, targetEnergy = 1f, motionScale = 1f)
        val expected = ThinkingShaderMotion.advance(start, 0.1f, targetEnergy = 1f, motionScale = 1f)

        assertTrue(abs(capped.scanPhase - expected.scanPhase) < 0.000_001f)
        assertTrue(abs(capped.streamEnergy - expected.streamEnergy) < 0.000_001f)
    }

    @Test
    fun `energy and motion inputs are bounded`() {
        val after = ThinkingShaderMotion.advance(
            ThinkingShaderMotionState(),
            deltaSeconds = 0.1f,
            targetEnergy = 9f,
            motionScale = 9f,
        )

        assertTrue(after.streamEnergy in 0f..1f)
        assertTrue(after.scanPhase in 0f..<1f)
    }

    @Test
    fun `phase wraps without becoming negative`() {
        assertEquals(0.25f, ThinkingShaderMotion.wrapUnitPhase(1.25f), 0.000_001f)
        assertEquals(0.75f, ThinkingShaderMotion.wrapUnitPhase(-0.25f), 0.000_001f)
    }
}
