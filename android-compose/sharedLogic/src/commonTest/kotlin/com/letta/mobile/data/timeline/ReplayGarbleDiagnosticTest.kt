package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-k9y5d: regression guard for the replayed-assistant-text
 * corruption observed on device after an app restart + new message
 * ("Kitchen sink is installed" -> "chen sink is installed",
 *  "The APK is 174MB" -> "The APK isMB",
 *  "local on-device agent" -> "local onice agent").
 *
 * Root cause (PROJECTION/MERGE on replay, NOT the persisted record): on
 * replay / out-of-order resume, the frame carrying the COMPLETE correct
 * assistant text can arrive with a seq id that is <= an existing event which
 * currently holds only a shorter, garbled partial. The old reducer dropped
 * that frame purely on `incoming.seqId <= existing.seqId`
 * (`hasAlreadyIngestedStreamFrame`), stranding the corrupted partial; and
 * when the dropped frame slipped through, the snapshot merge APPENDED two
 * competing snapshots, duplicating/garbling the body.
 *
 * Fix: the seq guard only drops a frame whose content is a pure contained
 * subset (empty/equal/prefix/suffix) of what we already hold; and the merge
 * gained a SNAPSHOT_CONFLICT branch — for a NON-forward (replayed, lower seq)
 * frame that shares no clean prefix/suffix it keeps the LONGER (complete)
 * snapshot instead of appending. Genuine forward incremental deltas still
 * append, so live streaming is unaffected.
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

    // (1) Reconstruct the exact corruption. A stranded partial tail snapshot
    // sits in the timeline (high seq) when the full snapshot replays with a
    // LOWER seq. Old behaviour kept "chen sink is installed"; the full text
    // must win.
    @Test
    fun `replay full snapshot wins over stranded partial with higher seq`() {
        var t = Timeline("conv")
        t = reduce(t, f("chen sink is installed", 6))
        t = reduce(t, f("Kitchen sink is installed", 5))
        assertEquals(
            "Kitchen sink is installed",
            cur(t),
            "the complete replayed text must replace the stranded partial",
        )
    }

    // (1b) The "174" drop variant.
    @Test
    fun `replay full snapshot wins for the 174MB corruption`() {
        var t = Timeline("conv")
        t = reduce(t, f("isMB with the embedded runtime", 9))
        t = reduce(t, f("The APK is 174MB with the embedded runtime", 7))
        assertEquals(
            "The APK is 174MB with the embedded runtime",
            cur(t),
            "complete text must replace a stranded partial regardless of seq",
        )
    }

    // (2) Replay-specific: a COMPLETE message must NEVER be shortened or
    // garbled by a later partial/duplicate/empty frame.
    @Test
    fun `complete message is never shortened by a later partial replay frame`() {
        val full = "Kitchen sink is installed. The APK is 174MB (local on-device agent)."
        var t = reduce(Timeline("conv"), f(full, 7))
        // A replayed earlier partial (lower seq).
        t = reduce(t, f("Kitchen sink is", 3))
        assertEquals(full, cur(t), "stale prefix partial must not truncate the complete text")
        // A replayed duplicate (same seq).
        t = reduce(t, f(full, 7))
        assertEquals(full, cur(t), "duplicate frame must not change the text")
        // A replayed empty frame.
        t = reduce(t, f("", 8))
        assertEquals(full, cur(t), "empty frame must not blank the text")
        // A coincidental-suffix partial (lower seq).
        t = reduce(t, f("device agent).", 4))
        assertEquals(full, cur(t), "suffix-only partial must not shrink the text")
    }

    // (3a) Genuine forward incremental deltas (live streaming) still append —
    // proving the fix does not regress streaming. "Y" + "es ..." -> "Yes ..."
    @Test
    fun `forward incremental deltas still append`() {
        var t = Timeline("conv")
        t = reduce(t, f("Y", 1))
        t = reduce(t, f("es — confirmed", 2))
        assertEquals("Yes — confirmed", cur(t), "forward deltas must append into the growing message")
    }

    // (3b) A genuine cumulative prefix snapshot still grows to the longer text.
    @Test
    fun `cumulative prefix snapshot grows normally`() {
        var t = Timeline("conv")
        t = reduce(t, f("The APK", 1))
        t = reduce(t, f("The APK is 174MB", 2))
        assertEquals("The APK is 174MB", cur(t), "cumulative snapshot must grow to the longer text")
    }

    // (3c) Direct branch coverage of mergeStreamText: without a reliable
    // ordering signal (no seq ids) a shorter incoming that coincidentally
    // shares a prefix/suffix must NOT replace or truncate the existing text.
    @Test
    fun `merge without seq ids does not let coincidental overlap mangle text`() {
        // No snapshot signal -> append, never truncate.
        val appended = mergeStreamText(
            existing = "Kitchen sink is installed",
            incoming = "lled",
            canUseSnapshotMerge = false,
        )
        assertEquals(StreamTextMergeBranch.APPEND, appended.branch)
        assertEquals("Kitchen sink is installedlled", appended.text)
        assertTrue(appended.text.startsWith("Kitchen sink is installed"), "prefix must be preserved")

        // Non-forward snapshot collision -> keep the longer complete text.
        val conflict = mergeStreamText(
            existing = "chen sink is installed",
            incoming = "Kitchen sink is installed",
            canUseSnapshotMerge = true,
            incomingIsForwardDelta = false,
        )
        assertEquals(StreamTextMergeBranch.SNAPSHOT_CONFLICT, conflict.branch)
        assertEquals("Kitchen sink is installed", conflict.text)

        // Forward snapshot delta with no overlap -> append (incremental stream).
        val forward = mergeStreamText(
            existing = "Y",
            incoming = "es — confirmed",
            canUseSnapshotMerge = true,
            incomingIsForwardDelta = true,
        )
        assertEquals(StreamTextMergeBranch.APPEND, forward.branch)
        assertEquals("Yes — confirmed", forward.text)
    }

    // letta-mobile-mvcr4: on-device screenshot showed "jules-watch #39"
    // rendered as "ules-watch #39" (leading 'j' dropped) and
    // "completed"/"completions," rendered as "complet "/ "complet "
    // (trailing chars dropped). Two distinct drops in the same streamed
    // message, both on plain prose with no markdown tokens.
    //
    // The merge currently has no branch that fits a forward (higher-seq)
    // delta where the incoming text overlaps the existing text by ALMOST
    // the whole prefix except the very first char (case A) or by ALMOST
    // the whole suffix except the trailing chars (case B). The
    // `SUFFIX_DUPLICATE` branch keeps `existing` when `existing.endsWith(incoming)` —
    // correct — but the *first-char* variant has no clean prefix/suffix
    // overlap so it falls through to APPEND and duplicates the body,
    // which the reveal/smoother then has to reconcile. The mid-word
    // trailing-chars variant is the inverse shape (no clean overlap,
    // APPEND path) and produces a different downstream visual drop
    // depending on how the reveal strips the duplicate tail.
    //
    // Reproduce the exact screenshot shapes as reducer replays so the
    // correct branch / reconciliation behavior is enforced.
    @Test
    fun `near-prefix forward delta does not eat the leading char`() {
        var t = Timeline("conv")
        // First forward delta captures the assistant's first word,
        // but the next snapshot recomputes the buffer and drops the
        // first character (the 'j' in "jules-watch #39").
        t = reduce(t, f("jules-watch #39", seq = 10))
        t = reduce(t, f("ules-watch #39", seq = 11))
        assertEquals(
            "jules-watch #39",
            cur(t),
            "near-prefix forward delta must not drop leading chars",
        )
    }

    @Test
    fun `near-suffix forward delta does not eat trailing chars`() {
        var t = Timeline("conv")
        // First forward delta carries the early word, second delta is a
        // snapshot that overlaps the trailing chars (the 'ed'/'ions,'
        // at the end of "completed"/"completions,"). With no clean
        // prefix relationship, the merge must keep the complete text,
        // not the stranded tail.
        t = reduce(t, f("complet ", seq = 10))
        t = reduce(t, f("completed", seq = 11))
        assertEquals(
            "completed",
            cur(t),
            "near-suffix forward delta must not truncate trailing chars",
        )
    }
}
