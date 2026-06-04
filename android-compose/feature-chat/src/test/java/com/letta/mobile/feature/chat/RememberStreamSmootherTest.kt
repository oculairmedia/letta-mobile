package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.render.STREAMING_TEXT_PAINT_INTERVAL_MS
import com.letta.mobile.feature.chat.render.StreamingDisplayTextSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberStreamSmootherTest {

    @Test
    fun `visible smoother cadence matches streaming markdown paint window`() {
        assertEquals(50L, STREAMING_TEXT_PAINT_INTERVAL_MS)
    }

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
    fun `seed reveals prefix immediately without rewinding to empty`() {
        // letta-mobile-uoiu6: the first token painted via the non-smoothed
        // path must NOT be re-revealed from an empty string when streaming
        // engages, or the opening word visibly flashes/redraws.
        val smoother = StreamingDisplayTextSmoother()

        // First word was already on screen when the smoother engages.
        smoother.seed("Hello", isStreaming = true, nowMs = 0L)

        // The very first paint must already show the full seeded prefix —
        // no growth from "", "H", "He"... that would be the flash.
        val firstPaint = smoother.step(16L)
        assertTrue(
            "Seeded prefix must be fully visible on first paint, was '$firstPaint'",
            firstPaint.startsWith("Hello"),
        )
    }

    @Test
    fun `seed then growth continues from prefix without re-revealing it`() {
        val smoother = StreamingDisplayTextSmoother()

        smoother.seed("Hello", isStreaming = true, nowMs = 0L)
        // First delta grows the message past the seeded prefix.
        smoother.updateTarget("Hello world", isStreaming = true, nowMs = 16L)

        // The reveal continues forward from the seeded prefix; it must never
        // drop below the already-visible prefix length.
        var text = smoother.step(32L)
        assertTrue(
            "Reveal must not rewind below seeded prefix, was '$text'",
            text.length >= "Hello".length,
        )
        assertTrue(text.startsWith("Hello"))

        // Eventually catches the full grown target.
        var loops = 0
        while (!smoother.isFullyRevealed && loops < 50) {
            text = smoother.step(48L + loops * 16L)
            loops++
        }
        assertEquals("Hello world", text)
    }

    @Test
    fun `seed with empty prefix behaves like a normal reveal`() {
        val smoother = StreamingDisplayTextSmoother()

        // Empty seed => nothing was painted yet; normal char-by-char reveal.
        smoother.seed("", isStreaming = true, nowMs = 0L)
        smoother.updateTarget("Hi", isStreaming = true, nowMs = 0L)

        val step1 = smoother.step(16L)
        assertEquals("H", step1)
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