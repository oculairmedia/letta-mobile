package com.letta.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.letta.mobile.feature.chat.render.StreamingDisplayTextSmoother

class StreamingDisplayTextSmootherTest {

    @Test
    fun `reveals bursty arrivals at a smoothed steady cadence`() {
        // letta-mobile-1kz40: re-tuned cadence. The reveal is no longer a fixed
        // ~144 c/s crawl, so this asserts the steady-cadence INVARIANTS rather
        // than brittle exact char counts: the head is preserved (always a true
        // prefix), the reveal advances monotonically, and a mid-stream burst is
        // smoothed (not dumped instantly in a single frame).
        val smoother = StreamingDisplayTextSmoother()
        val target = "Hello world"

        smoother.updateTarget(target, isStreaming = true, nowMs = 0L)
        val first = smoother.step(16L)
        assertTrue("Displayed must be a prefix of target, was '$first'", target.startsWith(first))
        assertTrue("First frame reveals from the head", first.isEmpty() || first[0] == 'H')

        val second = smoother.step(26L)
        assertTrue("Reveal advances monotonically", second.length >= first.length)
        assertTrue(target.startsWith(second))

        // Burst arrival at 30 ms — clock is NOT reset, so cadence stays independent.
        val grown = "Hello world from Letta"
        smoother.updateTarget(grown, isStreaming = true, nowMs = 30L)

        val third = smoother.step(36L)
        assertTrue("Displayed stays a prefix after burst", grown.startsWith(third))
        assertTrue("Reveal does not rewind on burst", third.length >= second.length)
        assertTrue(
            "burst arrival should not instantly reveal all new text in one frame",
            third.length < grown.length,
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

    // ---------------------------------------------------------------------
    // letta-mobile-1kz40: head-preservation, no-rewind, and convergence.
    // The smoother shipped without head-preservation coverage; these tests
    // lock in that the displayed substring is ALWAYS a true prefix of the
    // target, the head is never clipped, the visible text never rewinds, and
    // the reveal converges to arrival after a burst.
    // ---------------------------------------------------------------------

    /**
     * Head preservation: seed('') then updateTarget('Filed.') must reveal
     * 'F','Fi','Fil',... never skipping the head. The reported bug rendered
     * 'Filed.' as 'ed:' — the leading 'Fil' was dropped.
     */
    @Test
    fun `head preservation - empty seed then Filed reveals F Fi Fil never skipping head`() {
        val smoother = StreamingDisplayTextSmoother()
        val target = "Filed."

        smoother.seed("", isStreaming = true, nowMs = 0L)
        smoother.updateTarget(target, isStreaming = true, nowMs = 0L)

        var now = 50L
        var lastLen = 0
        repeat(60) {
            val shown = smoother.step(now)
            // Displayed must ALWAYS be a true prefix of the target.
            assertTrue(
                "Displayed '$shown' must be a prefix of '$target'",
                target.startsWith(shown),
            )
            // Reveal grows monotonically (never rewinds).
            assertTrue("Reveal must not rewind", shown.length >= lastLen)
            lastLen = shown.length
            now += 50L
        }
        // The first non-empty reveal must start with 'F' — never 'ed' or '.'.
        // (already guaranteed by the prefix assertion above, but assert the
        // final reveal equals the full target so the head was preserved end to
        // end.)
        assertEquals(target, smoother.step(now))
    }

    /**
     * seed('Fil') + updateTarget('Filed.') continues from 'Fil' without
     * dropping any head characters.
     */
    @Test
    fun `head preservation - prefix seed continues without dropping head`() {
        val smoother = StreamingDisplayTextSmoother()
        val target = "Filed."

        smoother.seed("Fil", isStreaming = true, nowMs = 0L)
        smoother.updateTarget(target, isStreaming = true, nowMs = 16L)

        var now = 66L
        var lastLen = 0
        var shown = ""
        repeat(60) {
            shown = smoother.step(now)
            assertTrue(
                "Displayed '$shown' must be a prefix of '$target'",
                target.startsWith(shown),
            )
            // Already-painted 'Fil' must never be rewound below 3 chars.
            assertTrue("Must not rewind below seeded prefix", shown.length >= 3)
            assertTrue("Reveal must not rewind", shown.length >= lastLen)
            lastLen = shown.length
            now += 50L
        }
        assertEquals(target, shown)
    }

    /**
     * Non-prefix seed('Xyz') + updateTarget('Filed.') must NOT clip the head.
     * 'Xyz' is not a prefix of 'Filed.', so the cursor must reset to a valid
     * prefix length (here 0) and the head is preserved.
     */
    @Test
    fun `head preservation - non-prefix seed does not clip head`() {
        val smoother = StreamingDisplayTextSmoother()
        val target = "Filed."

        smoother.seed("Xyz", isStreaming = true, nowMs = 0L)
        smoother.updateTarget(target, isStreaming = true, nowMs = 16L)

        var now = 66L
        var shown = ""
        repeat(60) {
            shown = smoother.step(now)
            // CRITICAL: the displayed text must be a true prefix of 'Filed.'.
            // The old code left the cursor at length 3 (== 'Xyz'.length)
            // against a target that does not share that prefix, producing
            // 'Fil' or worse 'ed.' garbage. The clamp forces it back to a
            // verified prefix so the head survives.
            assertTrue(
                "Displayed '$shown' must be a prefix of '$target' (no head clip)",
                target.startsWith(shown),
            )
            now += 50L
        }
        assertEquals(target, shown)
    }

    /**
     * No-rewind: a non-append updateTarget keeps the common prefix's revealed
     * chars. revealedCount must not drop below the common-prefix length, and
     * the visible text must never re-type from scratch.
     */
    @Test
    fun `no rewind - non-append target keeps common prefix revealed chars`() {
        val smoother = StreamingDisplayTextSmoother()

        // Reveal a good chunk of the first target.
        smoother.updateTarget("Hello world, this is", isStreaming = true, nowMs = 0L)
        var shown = ""
        var now = 50L
        repeat(40) {
            shown = smoother.step(now)
            now += 50L
        }
        val revealedBefore = shown.length
        assertTrue("Precondition: revealed a meaningful prefix", revealedBefore >= 5)

        // Non-append rewrite that shares the prefix 'Hello world, this is' but
        // replaces the (unrevealed) tail. Common prefix is at least the part
        // already revealed.
        val rewritten = "Hello world, this is a different ending entirely"
        smoother.updateTarget(rewritten, isStreaming = true, nowMs = now)

        val afterRewrite = smoother.step(now + 50L)
        // The revealed text must not have rewound — still a prefix of the new
        // target, and at least as long as what we had (the common prefix
        // covers all previously revealed chars).
        assertTrue(
            "After non-append rewrite, displayed '$afterRewrite' must be a prefix of the new target",
            rewritten.startsWith(afterRewrite),
        )
        assertTrue(
            "Revealed length must not drop below the common-prefix-covered reveal " +
                "(was $revealedBefore, now ${afterRewrite.length})",
            afterRewrite.length >= revealedBefore,
        )
    }

    /**
     * Convergence: after a large burst (target jumps way up), stepping
     * converges revealedCount to target.length within a bounded time, not
     * stuck far behind. With the re-tuned constants the reveal must catch a
     * ~600 char burst within a small bounded number of paint frames.
     */
    @Test
    fun `convergence - large burst is caught within a bounded time`() {
        val smoother = StreamingDisplayTextSmoother()
        val burst = "x".repeat(600)

        // A long response lands essentially all at once (one big coalesced
        // delta), streaming still open.
        smoother.updateTarget(burst, isStreaming = true, nowMs = 0L)

        // Step at the 50ms paint cadence and measure how long to converge.
        var now = 50L
        var frames = 0
        val maxFrames = 80 // 80 * 50ms = 4s upper bound
        while (!smoother.isFullyRevealed && frames < maxFrames) {
            smoother.step(now)
            now += 50L
            frames++
        }
        assertTrue(
            "Reveal must converge to the full burst within the bound, " +
                "took $frames frames (${frames * 50}ms)",
            smoother.isFullyRevealed,
        )
        // Stronger: a 600-char burst should converge well within ~2.5s with
        // throughput-preserving constants, not crawl for 4s+.
        assertTrue(
            "Reveal should track arrival, not crawl (took ${frames * 50}ms for 600 chars)",
            frames * 50 <= 2_500,
        )
    }

    /**
     * Bounded lag: while streaming a long response that keeps arriving, the
     * reveal must stay within a small bound (not fall many lines behind). This
     * is the 'never lags more than ~1 line behind' acceptance.
     */
    @Test
    fun `bounded lag - reveal stays within a small bound of steady arrival`() {
        val smoother = StreamingDisplayTextSmoother()

        // Simulate steady-ish bursty arrival: 40 chars every 100ms for 2s
        // (~400 c/s sustained), with the paint loop stepping every 50ms.
        val full = "abcdefghij".repeat(80) // 800 chars
        var arrived = 0
        var now = 0L
        var maxBacklog = 0
        val burstChars = 40
        while (arrived < full.length) {
            arrived = (arrived + burstChars).coerceAtMost(full.length)
            smoother.updateTarget(full.substring(0, arrived), isStreaming = true, nowMs = now)
            // two paint frames per 100ms arrival window
            val s1 = smoother.step(now + 50L)
            val s2 = smoother.step(now + 100L)
            maxBacklog = maxOf(maxBacklog, arrived - s2.length)
            // displayed always a prefix
            assertTrue(full.startsWith(s1) && full.startsWith(s2))
            now += 100L
        }
        // Backlog should stay bounded to roughly a line or two, not hundreds.
        assertTrue(
            "Reveal fell too far behind steady arrival (max backlog $maxBacklog chars)",
            maxBacklog <= 120,
        )
    }
}
