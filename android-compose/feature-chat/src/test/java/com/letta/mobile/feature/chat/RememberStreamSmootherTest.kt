package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.render.ENABLE_HEADLESS_STREAMING_REVEAL
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
    fun `headless reveal flag is enabled by default`() {
        assertTrue(ENABLE_HEADLESS_STREAMING_REVEAL)
    }

    @Test
    fun `fast burst deltas reveal in paced chunks without losing canonical text`() {
        val smoother = StreamingDisplayTextSmoother(revealCodePointsPerStep = 8)
        val target = "Hello there, this is a longer streaming message to reveal smoothly"

        smoother.updateTarget("Hello there,", isStreaming = true, nowMs = 0L)
        smoother.updateTarget(target, isStreaming = true, nowMs = 5L)

        val firstPaint = smoother.step(STREAMING_TEXT_PAINT_INTERVAL_MS)
        assertEquals("Hello th", firstPaint)
        assertTrue(target.startsWith(firstPaint))
        assertFalse(smoother.isFullyRevealed)

        val secondPaint = smoother.step(STREAMING_TEXT_PAINT_INTERVAL_MS * 2)
        assertEquals("Hello there, thi", secondPaint)
        assertTrue(target.startsWith(secondPaint))
    }

    @Test
    fun `replaceBuffer hydration replacement clamps to canonical prefix`() {
        val smoother = StreamingDisplayTextSmoother(revealCodePointsPerStep = 4)

        smoother.updateTarget("Hello old tail", isStreaming = true, nowMs = 0L)
        assertEquals("Hell", smoother.step(STREAMING_TEXT_PAINT_INTERVAL_MS))

        smoother.updateTarget("Hello new canonical tail", isStreaming = true, nowMs = STREAMING_TEXT_PAINT_INTERVAL_MS)
        val afterReplacement = smoother.step(STREAMING_TEXT_PAINT_INTERVAL_MS * 2)

        assertEquals("Hello ne", afterReplacement)
        assertTrue("Hello new canonical tail".startsWith(afterReplacement))
    }

    @Test
    fun `completion snaps to full canonical content`() {
        val smoother = StreamingDisplayTextSmoother(revealCodePointsPerStep = 2)
        val target = "Hi there"

        smoother.updateTarget(target, isStreaming = true, nowMs = 0L)
        assertEquals("Hi", smoother.step(STREAMING_TEXT_PAINT_INTERVAL_MS))
        assertFalse(smoother.isFullyRevealed)

        smoother.updateTarget(target, isStreaming = false, nowMs = STREAMING_TEXT_PAINT_INTERVAL_MS)

        assertEquals(target, smoother.step(STREAMING_TEXT_PAINT_INTERVAL_MS * 2))
        assertTrue(smoother.isFullyRevealed)
    }

    @Test
    fun `disabled fallback renders canonical full content immediately`() {
        val smoother = StreamingDisplayTextSmoother(revealCodePointsPerStep = 2, enabled = false)
        val target = "Fallback should not reveal progressively"

        smoother.updateTarget(target, isStreaming = true, nowMs = 0L)

        assertEquals(target, smoother.step(STREAMING_TEXT_PAINT_INTERVAL_MS))
        assertTrue(smoother.isFullyRevealed)
    }

    @Test
    fun `seed reveals prefix immediately without rewinding to empty`() {
        val smoother = StreamingDisplayTextSmoother(revealCodePointsPerStep = 2)

        smoother.seed("Hello", isStreaming = true, nowMs = 0L)

        assertEquals("Hello", smoother.step(STREAMING_TEXT_PAINT_INTERVAL_MS))
    }

    @Test
    fun `seed then growth continues from prefix without re-revealing it`() {
        val smoother = StreamingDisplayTextSmoother(revealCodePointsPerStep = 3)

        smoother.seed("Hello", isStreaming = true, nowMs = 0L)
        smoother.updateTarget("Hello world", isStreaming = true, nowMs = 16L)

        val text = smoother.step(STREAMING_TEXT_PAINT_INTERVAL_MS)
        assertEquals("Hello wo", text)
        assertTrue(text.startsWith("Hello"))
    }

    @Test
    fun `handles empty text gracefully`() {
        val smoother = StreamingDisplayTextSmoother()

        smoother.updateTarget("", isStreaming = true, nowMs = 0L)
        val text = smoother.step(16L)

        assertEquals("", text)
        assertTrue(smoother.isFullyRevealed)
    }
}
