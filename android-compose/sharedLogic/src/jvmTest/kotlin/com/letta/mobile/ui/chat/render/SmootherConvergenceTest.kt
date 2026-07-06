package com.letta.mobile.ui.chat.render

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-h30cy: after a divergent updateTarget (canonical rewrite that does
 * NOT extend the current buffer), the smoother rewinds `revealed` to the common
 * prefix. If it never converges to the FULL new text once the turn ends
 * (isStreaming=false), the streamed row is stuck showing a truncated/mangled
 * prefix — the display mangle that defeats the twin dedup. This drives that
 * end-state device-free.
 */
class SmootherConvergenceTest {

    @Test
    fun `converges to full text after divergent rewrite once streaming ends`() {
        val smoother = StreamingDisplayTextSmoother()
        smoother.updateTarget("we've been here before — clean for a stretch", isStreaming = true, nowMs = 0L)
        var now = 0L
        repeat(20) { now += 16L; smoother.step(now) }
        val full = "we've been here before—clean for a stretch, then it resurfaces. Don't trust it yet."
        smoother.updateTarget(full, isStreaming = true, nowMs = now)
        repeat(10) { now += 16L; smoother.step(now) }
        smoother.updateTarget(full, isStreaming = false, nowMs = now)
        var shown = ""
        repeat(300) { now += 16L; shown = smoother.step(now) }
        assertEquals(full, shown)
    }

    @Test
    fun `converges to full when isStreaming false immediately after divergence`() {
        val smoother = StreamingDisplayTextSmoother()
        smoother.updateTarget("Hello world, this is a", isStreaming = true, nowMs = 0L)
        var now = 0L
        repeat(15) { now += 16L; smoother.step(now) }
        val full = "Hello world; this is a test, with punctuation."
        smoother.updateTarget(full, isStreaming = false, nowMs = now)
        var shown = ""
        repeat(300) { now += 16L; shown = smoother.step(now) }
        assertEquals(full, shown)
    }
}
