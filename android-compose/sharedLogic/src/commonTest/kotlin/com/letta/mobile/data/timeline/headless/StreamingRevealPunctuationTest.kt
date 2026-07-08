package com.letta.mobile.data.timeline.headless

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-h30cy: the streamed assistant row rendered with punctuation/words
 * stripped vs the clean reconciled final. The reveal state (HeadlessStreamingRevealState,
 * the pure core of StreamingDisplayTextSmoother) is prefix-based, but replaceBuffer
 * truncates `revealed` to the common prefix when a new canonical buffer does NOT
 * start with the already-revealed text. This harness proves the reveal preserves
 * the full text once source is marked complete, across append AND replaceBuffer
 * (divergent) update paths — the mangle scenario.
 */
class StreamingRevealPunctuationTest {

    private val full = "we've been here before — clean for a stretch, then it resurfaces. " +
        "Don't trust it yet. Keep the harness running, keep the data flowing."

    @Test
    fun `forward append reveals the full punctuated text`() {
        val s = HeadlessStreamingRevealState()
        // stream in growing prefixes (the normal path)
        var shown = 0
        while (shown < full.length) {
            shown = minOf(shown + 5, full.length)
            s.replaceBuffer(full.substring(0, shown))
        }
        s.markSourceComplete()
        s.skipToEnd()
        assertEquals(full, s.revealed)
    }

    @Test
    fun `divergent buffer then complete still reveals full text`() {
        val s = HeadlessStreamingRevealState()
        s.append("we've been here before — clean")
        s.revealNext(1000) // reveal what we have
        // a divergent canonical buffer arrives (does NOT start with revealed —
        // e.g. whitespace/punctuation renormalized upstream)
        s.replaceBuffer("we've been here before—clean for a stretch")
        // then the true full text arrives and source completes
        s.replaceBuffer(full)
        s.markSourceComplete()
        s.skipToEnd()
        assertEquals(full, s.revealed)
    }
}
