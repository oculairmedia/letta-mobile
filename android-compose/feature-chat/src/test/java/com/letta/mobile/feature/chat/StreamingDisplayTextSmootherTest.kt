package com.letta.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDisplayTextSmootherTest {

    @Test
    fun `reveals bursty arrivals at a smoothed steady cadence`() {
        val smoother = StreamingDisplayTextSmoother()

        smoother.updateTarget("Hello world", isStreaming = true, nowMs = 0L)
        val first = smoother.step(16L)
        // Slower readable baseline: 16 ms reveals the first char.
        assertEquals("H", first)

        val second = smoother.step(26L)
        // Continued frames advance steadily rather than jumping to the full chunk.
        assertEquals("He", second)

        // Burst arrival at 30 ms — clock is NOT reset, so cadence stays independent
        smoother.updateTarget("Hello world from Letta", isStreaming = true, nowMs = 30L)

        val third = smoother.step(36L)
        // Only one more char revealed after the burst — velocity tweens toward the new rate.
        assertEquals("Hel", third)
        assertTrue(
            "burst arrival should not instantly reveal all new text",
            third.length < "Hello world from Letta".length,
        )
    }

    @Test
    fun `drains remaining tail after stream end`() {
        val smoother = StreamingDisplayTextSmoother()

        smoother.updateTarget("Hello world from Letta", isStreaming = true, nowMs = 0L)
        smoother.step(16L)
        smoother.updateTarget("Hello world from Letta", isStreaming = false, nowMs = 32L)

        var text = ""
        var now = 48L
        repeat(20) {
            text = smoother.step(now)
            now += 16L
        }

        assertEquals("Hello world from Letta", text)
        assertTrue(smoother.isFullyRevealed)
    }

    @Test
    fun `resets safely when target text rewrites instead of extending`() {
        val smoother = StreamingDisplayTextSmoother()

        smoother.updateTarget("Hello world", isStreaming = true, nowMs = 0L)
        // Reveal 2 chars at 16 ms
        smoother.step(16L)

        // Abruptly rewrite to a different target
        smoother.updateTarget("Hi there", isStreaming = true, nowMs = 32L)
        val text = smoother.step(48L)
        assertTrue(text.length <= "Hi there".length)
    }
}
