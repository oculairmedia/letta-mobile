package com.letta.mobile.data.timeline.headless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadlessStreamingRevealStateTest {
    @Test
    fun `append buffers content and reveals in chunks`() {
        val state = HeadlessStreamingRevealState(defaultRevealCodePoints = 5)

        state.append("hello streaming world")
        val first = state.revealNext()
        val second = state.revealNext()

        assertEquals("hello streaming world", first.buffer)
        assertEquals("hello", first.revealed)
        assertEquals("hello stre", second.revealed)
        assertEquals(HeadlessStreamingRevealPhase.REVEALING, second.phase)
        assertPrefixInvariant(second)
    }

    @Test
    fun `stop freezes reveal and resume continues from same prefix`() {
        val state = HeadlessStreamingRevealState(defaultRevealCodePoints = 4)
        state.append("abcdefghijkl")
        state.revealNext()

        val stopped = state.stop()
        val stillStopped = state.revealNext(maxCodePoints = 4)
        val resumed = state.resume()
        val advanced = state.revealNext(maxCodePoints = 4)

        assertEquals("abcd", stopped.revealed)
        assertEquals("abcd", stillStopped.revealed)
        assertEquals(HeadlessStreamingRevealPhase.STOPPED, stillStopped.phase)
        assertEquals(HeadlessStreamingRevealPhase.REVEALING, resumed.phase)
        assertEquals("abcdefgh", advanced.revealed)
        assertPrefixInvariant(advanced)
    }

    @Test
    fun `skip reveals all buffered content without completing source`() {
        val state = HeadlessStreamingRevealState()
        state.append("partial answer")

        val skipped = state.skipToEnd()

        assertEquals("partial answer", skipped.revealed)
        assertEquals("", skipped.pending)
        assertEquals(HeadlessStreamingRevealPhase.WAITING, skipped.phase)
        assertPrefixInvariant(skipped)
    }

    @Test
    fun `source complete waits for pending reveal before done`() {
        val state = HeadlessStreamingRevealState(defaultRevealCodePoints = 100)
        state.append("final answer")

        val completeWithPending = state.markSourceComplete()
        val done = state.revealNext()

        assertEquals(HeadlessStreamingRevealPhase.REVEALING, completeWithPending.phase)
        assertEquals("final answer", done.revealed)
        assertEquals(HeadlessStreamingRevealPhase.DONE, done.phase)
        assertPrefixInvariant(done)
    }

    @Test
    fun `fast upstream bursts preserve glyphs and reveal canonical prefix`() {
        val state = HeadlessStreamingRevealState(defaultRevealCodePoints = 3)
        val chunks = listOf("Hel", "lo ", "🌍", " — ", "stream", "ing")
        var canonical = ""

        chunks.forEach { chunk ->
            canonical += chunk
            val snapshot = state.append(chunk)
            assertEquals(canonical, snapshot.buffer)
            assertPrefixInvariant(snapshot)
        }

        while (state.phase == HeadlessStreamingRevealPhase.REVEALING) {
            val snapshot = state.revealNext()
            assertTrue(canonical.startsWith(snapshot.revealed))
            assertPrefixInvariant(snapshot)
        }

        assertEquals(canonical, state.revealed)
        assertEquals("Hello 🌍 — streaming", state.buffer)
        assertEquals(HeadlessStreamingRevealPhase.WAITING, state.phase)
    }

    @Test
    fun `replace canonical buffer trims revealed to preserved prefix`() {
        val state = HeadlessStreamingRevealState(defaultRevealCodePoints = 20)
        state.append("assistant draft")
        state.revealNext()

        val replaced = state.replaceBuffer("assistive final")

        assertEquals("assist", replaced.revealed)
        assertEquals("assistive final", replaced.buffer)
        assertEquals("ive final", replaced.pending)
        assertPrefixInvariant(replaced)
    }

    @Test
    fun `chunk reveal does not split surrogate pair glyphs`() {
        val state = HeadlessStreamingRevealState(defaultRevealCodePoints = 1)
        state.append("🌍a")

        val first = state.revealNext()
        val second = state.revealNext()

        assertEquals("🌍", first.revealed)
        assertEquals("🌍a", second.revealed)
        assertPrefixInvariant(first)
        assertPrefixInvariant(second)
    }

    private fun assertPrefixInvariant(snapshot: HeadlessStreamingRevealSnapshot) {
        assertTrue(snapshot.buffer.startsWith(snapshot.revealed))
        assertEquals(snapshot.buffer.removePrefix(snapshot.revealed), snapshot.pending)
    }

    // ========================================================================
    // letta-mobile-av0bz: tool-call boundary character drop / word scramble repro
    // ========================================================================

    /**
     * REPRO for letta-mobile-av0bz: seeding HeadlessStreamingRevealState with
     * a prefix that is NOT a prefix of the first real target must clamp the
     * cursor to a valid prefix length and NEVER drop the head.
     *
     * Bug mechanism: at a tool-call boundary, AssistantResponseText's
     * `initialText = remember(messageId){text}` captures a prefix (the text
     * before the tool call). The smoother is seeded with
     * `initialRevealedCount = prefix.codePointCount()`. When text grows after
     * the tool call (a second segment arrives), the buffer no longer starts
     * with the original seed, so `replaceBuffer` hits `commonPrefixWith`
     * which can clip the divergent head and drop leading chars.
     *
     * On-device examples:
     *  - 'jules-watch' -> 'ules-watch' (dropped 'j')
     *  - "I'll" -> "'ll" (dropped "I")
     *  - "PR created I'll the dev APK the branch install the" (full scramble)
     *
     * This test MUST fail on current main and MUST pass after the fix.
     */
    @Test
    fun `tool-call boundary - divergent seed does not drop head chars`() {
        // Simulate: first composition captured "I'll install the PR" (text before
        // the tool call), smoother seeded with initialRevealedCount=20.
        val divergentSeed = "I'll install the PR "
        val state = HeadlessStreamingRevealState(
            initialBuffer = divergentSeed,
            initialRevealedCount = divergentSeed.length, // 20 codepoints
        )

        // Now the REAL target arrives after the tool call boundary — a completely
        // different string starting with 'T', not 'I'.
        val realTarget = "The dev APK is building"
        state.replaceBuffer(realTarget)

        // CRITICAL: revealed must be clamped to a valid prefix of realTarget.
        // The bug left revealed="" or revealed="he dev" (skipping 'T').
        assertTrue(
            realTarget.startsWith(state.revealed),
            "REPRO ASSERTION: revealed '${state.revealed}' must be a prefix of '$realTarget' (no head drop)"
        )

        // Reveal step by step
        var shown = state.revealed
        repeat(60) {
            val snapshot = state.revealNext()
            shown = snapshot.revealed
            // Every intermediate reveal must be a prefix of the real target
            assertTrue(
                realTarget.startsWith(shown),
                "Displayed '$shown' must be a prefix of '$realTarget'"
            )
            // Must NEVER skip the leading 'T'
            if (shown.isNotEmpty() && !shown.startsWith("T")) {
                throw AssertionError(
                    "BUG REPRODUCED: shown '$shown' does not start with 'T' - head dropped"
                )
            }
        }
        assertEquals(realTarget, shown, "Final reveal must be the full target")
    }

    /**
     * On-device repro: 'jules-watch' -> 'ules-watch' (dropped 'j').
     *
     * Likely mechanism: LazyColumn recycle or stale initialText captured a
     * prefix like "Filed the PR, installing" (length ~25), then the real
     * target "I'll install jules-watch" arrived and the seed cursor at
     * position 25 clipped the head.
     */
    @Test
    fun `tool-call boundary - jules-watch does not drop j when seed diverges`() {
        val staleSeed = "Filed the PR, now installing the"
        val state = HeadlessStreamingRevealState(
            initialBuffer = staleSeed,
            initialRevealedCount = staleSeed.length,
        )

        val realTarget = "I'll install jules-watch on your device"
        state.replaceBuffer(realTarget)

        var shown = state.revealed
        repeat(80) {
            val snapshot = state.revealNext()
            shown = snapshot.revealed
            // CRITICAL: must never show 'ules-watch' without 'jules'
            if (shown.contains("ules-watch") && !shown.contains("jules-watch")) {
                throw AssertionError(
                    "BUG REPRODUCED: shown '$shown' contains 'ules-watch' without 'jules' - dropped 'j'"
                )
            }
            assertTrue(
                realTarget.startsWith(shown),
                "Displayed '$shown' must be a prefix of '$realTarget'"
            )
        }
        assertEquals(realTarget, shown, "Final reveal must match target")
    }

    /**
     * Edge case: seed longer than the first real target. The cursor must clamp
     * to the actual buffer length, not drop the buffer head.
     */
    @Test
    fun `tool-call boundary - seed longer than target clamps cursor`() {
        val longSeed = "This is a very long seed text that exceeds the real target by far"
        val state = HeadlessStreamingRevealState(
            initialBuffer = longSeed,
            initialRevealedCount = longSeed.length,
        )

        val shortTarget = "Filed."
        state.replaceBuffer(shortTarget)

        var shown = state.revealed
        repeat(30) {
            val snapshot = state.revealNext()
            shown = snapshot.revealed
            assertTrue(
                shortTarget.startsWith(shown),
                "Displayed '$shown' must be a prefix of '$shortTarget'"
            )
            // Must NEVER drop 'F' or show 'iled.' etc.
            if (shown.isNotEmpty() && !shown.startsWith("F")) {
                throw AssertionError(
                    "BUG REPRODUCED: shown '$shown' does not start with 'F' - head dropped"
                )
            }
        }
        assertEquals(shortTarget, shown, "Final reveal must match target")
    }

    /**
     * Empty buffer then non-empty target: the cursor starts at 0, so this
     * should always work. Sanity check that the bug is specific to non-zero
     * initialRevealedCount.
     */
    @Test
    fun `tool-call boundary - empty seed then target always preserves head`() {
        val state = HeadlessStreamingRevealState(
            initialBuffer = "",
            initialRevealedCount = 0,
        )

        val target = "Filed."
        state.replaceBuffer(target)

        var shown = state.revealed
        repeat(30) {
            val snapshot = state.revealNext()
            shown = snapshot.revealed
            assertTrue(target.startsWith(shown))
        }
        assertEquals(target, shown, "Final reveal must match target")
    }
}
