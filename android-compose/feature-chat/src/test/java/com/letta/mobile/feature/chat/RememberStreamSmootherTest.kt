package com.letta.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberStreamSmootherTest {

    @Test
    fun `updates and reveals text progressively`() {
        val smoother = StreamingDisplayTextSmoother()

        // Start streaming
        smoother.updateTarget("Hello", isStreaming = true, nowMs = 0L)

        // First frame - should reveal first character
        val step1 = smoother.step(16L)
        assertEquals("H", step1)

        // Second frame - should reveal second character
        val step2 = smoother.step(32L)
        assertEquals("He", step2)

        // Not yet fully revealed
        assertFalse(smoother.isFullyRevealed)
    }

    @Test
    fun `completes reveal when streaming ends`() {
        val smoother = StreamingDisplayTextSmoother()

        smoother.updateTarget("Hi", isStreaming = true, nowMs = 0L)
        smoother.step(16L)

        // End streaming
        smoother.updateTarget("Hi", isStreaming = false, nowMs = 32L)

        // Continue stepping until fully revealed
        var text = ""
        var loops = 0
        while (!smoother.isFullyRevealed && loops < 20) {
            text = smoother.step((loops * 16L + 48L))
            loops++
        }

        assertEquals("Hi", text)
        assertTrue(smoother.isFullyRevealed)
    }

    @Test
    fun `handles empty text gracefully`() {
        val smoother = StreamingDisplayTextSmoother()

        smoother.updateTarget("", isStreaming = true, nowMs = 0L)
        val text = smoother.step(16L)

        assertEquals("", text)
        assertTrue(smoother.isFullyRevealed)
    }

    @Test
    fun `handles abrupt target changes`() {
        val smoother = StreamingDisplayTextSmoother()

        // Start with one target
        smoother.updateTarget("Hello", isStreaming = true, nowMs = 0L)
        smoother.step(16L)

        // Abruptly change to different target
        smoother.updateTarget("Different", isStreaming = true, nowMs = 32L)

        // Should handle transition without crashing
        val text = smoother.step(48L)
        assertTrue(text.length <= "Different".length)
    }
}