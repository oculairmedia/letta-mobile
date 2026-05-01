package com.letta.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDisplayTextSmootherTest {

    @Test
    fun `reveals bursty arrivals at a smoothed steady cadence`() {
        val smoother = StreamingDisplayTextSmoother()

        smoother.updateTarget("Hello world", isStreaming = true, nowMs = 0L)
        val first = smoother.step(16L)
        assertTrue(first.length in 1..3)

        smoother.updateTarget("Hello world from Letta", isStreaming = true, nowMs = 400L)
        val second = smoother.step(416L)
        assertTrue(
            "second step should grow progressively instead of jumping to full burst",
            second.length < "Hello world from Letta".length,
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
    }

    @Test
    fun `resets safely when target text rewrites instead of extending`() {
        val smoother = StreamingDisplayTextSmoother()

        smoother.updateTarget("Hello world", isStreaming = true, nowMs = 0L)
        smoother.step(16L)
        smoother.updateTarget("Hi there", isStreaming = true, nowMs = 32L)

        val text = smoother.step(48L)
        assertTrue(text.length <= "Hi there".length)
    }
}
