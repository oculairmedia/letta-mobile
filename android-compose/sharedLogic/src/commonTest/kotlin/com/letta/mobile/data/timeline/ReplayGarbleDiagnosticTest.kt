package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-k9y5d: regression guard for the replayed-assistant-text
 * corruption ("Kitchen sink"->"chen sink", "174MB"->"isMB",
 * "on-device"->"onice").
 *
 * Root cause: on replay / out-of-order resume, cumulative assistant snapshots
 * arrive carrying seq ids. The old seq guard dropped any frame with a lower
 * seq id, so when a stranded partial tail snapshot (high seq) landed before
 * the full snapshot (lower seq), the FULL text was discarded and the partial
 * was kept. And when two seq-carrying snapshots shared no clean prefix/suffix
 * the merge APPENDED them, duplicating/garbling the body.
 *
 * Fix: the seq guard only drops a frame whose text is a pure contained
 * subset (empty/equal/prefix/suffix) of what we already hold; and the merge
 * gained a SNAPSHOT_CONFLICT branch that keeps the LONGER snapshot instead of
 * appending. A complete snapshot can therefore never be dropped or mangled by
 * a stranded partial, regardless of seq ordering.
 */
class ReplayGarbleDiagnosticTest {

    private fun reduce(prev: Timeline, frame: LettaMessage): Timeline =
        reduceStreamFrame(
            TimelineReducerInput(
                prev = prev,
                frame = frame,
                pendingToolReturnsByCallId = persistentMapOf<String, ToolReturnMessage>(),
            ),
        ).next

    private fun f(content: String, seq: Int?) =
        AssistantMessage(
            id = "cm-stream-otidA",
            contentRaw = JsonPrimitive(content),
            runId = "run-1",
            otid = "otidA",
            seqId = seq,
        )

    private fun cur(t: Timeline) = (t.events.single() as TimelineEvent.Confirmed).content

    // The exact failure mode: a partial tail snapshot lands first on replay
    // (higher seq), then the full snapshot replays with a lower seq. The full
    // text must NOT be dropped or shrunk.
    @Test
    fun `out-of-order replay keeps the full snapshot, never the partial`() {
        var t = Timeline("conv")
        // Partial tail arrives first (high seq) — stranded fragment.
        t = reduce(t, f("chen sink is installed", 6))
        // Full snapshot replays with a LOWER seq id.
        t = reduce(t, f("Kitchen sink is installed", 5))
        assertEquals(
            "Kitchen sink is installed",
            cur(t),
            "full snapshot must win even with a lower seq id than a stranded partial",
        )
    }

    // A later, equal-or-longer full snapshot must also be accepted.
    @Test
    fun `full snapshot is accepted even after a partial with higher seq`() {
        var t = Timeline("conv")
        t = reduce(t, f("isMB with the embedded runtime", 9))
        t = reduce(t, f("The APK is 174MB with the embedded runtime", 7))
        assertEquals(
            "The APK is 174MB with the embedded runtime",
            cur(t),
            "complete text must replace a stranded partial regardless of seq",
        )
    }

    // A genuine cumulative prefix snapshot (proper streaming) still grows.
    @Test
    fun `cumulative prefix snapshots still grow normally`() {
        var t = Timeline("conv")
        t = reduce(t, f("The APK", 1))
        t = reduce(t, f("The APK is 174MB", 2))
        assertEquals("The APK is 174MB", cur(t), "cumulative snapshot must grow to the longer text")
    }

    // A stale earlier prefix snapshot must not shrink the complete text.
    @Test
    fun `stale prefix snapshot does not shrink complete text`() {
        var t = Timeline("conv")
        t = reduce(t, f("The APK is 174MB", 3))
        t = reduce(t, f("The APK is", 2))
        assertEquals("The APK is 174MB", cur(t), "a stale prefix must never truncate the held text")
    }

    // Two conflicting seq-carrying snapshots (no clean prefix/suffix) must
    // keep the longer/complete one rather than appending and duplicating.
    @Test
    fun `conflicting snapshots keep the longer, never append`() {
        var t = Timeline("conv")
        t = reduce(t, f("local onice agent", 4))
        t = reduce(t, f("local on-device agent", 5))
        val text = cur(t)
        assertEquals("local on-device agent", text, "longer/complete snapshot must win")
        // And it must NOT be the appended concatenation.
        assertEquals(false, text.contains("onice" + "local"), "must not append/duplicate snapshots")
    }
}
