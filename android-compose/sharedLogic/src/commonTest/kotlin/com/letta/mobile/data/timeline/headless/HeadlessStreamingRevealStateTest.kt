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
}
