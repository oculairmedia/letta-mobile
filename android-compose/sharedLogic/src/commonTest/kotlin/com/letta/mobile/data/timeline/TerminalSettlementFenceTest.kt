package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-9lgfu (P0): assistant stream terminal drops final words.
 *
 * A terminal/reconcile settlement whose frame sequence predates a content
 * delta already folded into the row used to REPLACE the accumulated body
 * unconditionally — regressing it to an older, truncated prefix while the
 * terminal timestamp made the loss look authoritative.
 *
 * These tests pin the fence: stale non-superset settlements must fold
 * conservatively (keep-or-grow), forward/superset settlements stay intact,
 * both arrival orders converge to the same complete body, and duplicates are
 * idempotent. Tail shapes per acceptance criterion 7 (1-char, final word,
 * multi-byte emoji, markdown delimiter, newline) are exercised via the
 * truncated-tail parameterization.
 */
class TerminalSettlementFenceTest {

    private enum class FrameKind(val runId: String, val carriesOtid: Boolean) {
        LIVE(LIVE_RUN_ID, true),
        TERMINAL(TERMINAL_RUN_ID, true),
        RECONCILED(TERMINAL_RUN_ID, false),
    }

    private companion object {
        const val CONVERSATION_ID = "conv-9lgfu"
        const val LIVE_RUN_ID = "iroh-run-1"
        const val TERMINAL_RUN_ID = "run-real-9"
    }

    @AfterTest
    fun tearDown() {
        Telemetry.clear()
    }

    private fun frame(kind: FrameKind, id: String, content: String, seqId: Int): AssistantMessage = AssistantMessage(
        id = id,
        contentRaw = JsonPrimitive(content),
        runId = kind.runId,
        otid = if (kind.carriesOtid) "otid-$id" else null,
        seqId = seqId,
    )

    private fun liveFrame(id: String, content: String, seqId: Int) = frame(FrameKind.LIVE, id, content, seqId)
    private fun terminalFrame(id: String, content: String, seqId: Int) = frame(FrameKind.TERMINAL, id, content, seqId)
    private fun reconciledFrame(id: String, content: String, seqId: Int) =
        frame(FrameKind.RECONCILED, id, content, seqId)

    private fun evaluate(
        accumulatedText: String,
        terminalText: String,
        accumulatedSeqId: Int? = 5,
        terminalSeqId: Int? = 3,
    ): TerminalSettlementFenceDecision {
        val accumulated = liveFrame("assistant-x", accumulatedText, accumulatedSeqId ?: 0)
            .toTimelineEvent(position = 0.0) as TimelineEvent.Confirmed
        val terminal = terminalFrame("assistant-x", terminalText, terminalSeqId ?: 0)
            .toTimelineEvent(position = 0.0) as TimelineEvent.Confirmed
        return evaluateTerminalSettlementFence(
            conversationId = CONVERSATION_ID,
            accumulated = accumulated.copy(seqId = accumulatedSeqId),
            terminal = terminal.copy(seqId = terminalSeqId),
        )
    }

    // ---- direct fence unit tests -------------------------------------------

    @Test
    fun `fence passes when either sequence is absent`() {
        val d1 = evaluate("abc", "a", accumulatedSeqId = null)
        val d2 = evaluate("abc", "a", terminalSeqId = null)
        assertFalse(d1.blocked)
        assertFalse(d2.blocked)
        assertEquals("seq_absent", d1.reason)
    }

    @Test
    fun `fence passes when terminal seq is forward or equal`() {
        val newer = evaluate("abc", "xyz", terminalSeqId = 6)
        val equal = evaluate("abc", "xyz", terminalSeqId = 5)
        assertFalse(newer.blocked)
        assertFalse(equal.blocked)
    }

    @Test
    fun `fence blocks stale non-superset terminal`() {
        val decision = evaluate("waiting was the right call.", "waiting was the")
        assertTrue(decision.blocked)
        assertEquals("stale_terminal_not_superset", decision.reason)
    }

    @Test
    fun `fence allows stale terminal that is a content superset`() {
        val decision = evaluate("waiting was the", "waiting was the right call.")
        assertFalse(decision.blocked)
        assertEquals("stale_seq_but_superset", decision.reason)
    }

    @Test
    fun `fence preserves meaningful leading and trailing whitespace`() {
        val missingIndent = evaluate("    code()\n", "code()")
        val exactFormatting = evaluate("    code()\n", "prefix\n    code()\n")

        assertTrue(missingIndent.blocked)
        assertFalse(exactFormatting.blocked)
    }

    // ---- reducer-level regression: the reported symptom --------------------

    private fun reduce(
        prev: Timeline = Timeline(conversationId = CONVERSATION_ID),
        frame: AssistantMessage,
    ): TimelineReducerOutput = reduceStreamFrame(
        TimelineReducerInput(
            prev = prev,
            frame = frame,
            pendingToolReturnsByCallId = persistentMapOf(),
        )
    )

    private fun row(timeline: Timeline, serverId: String): TimelineEvent.Confirmed =
        timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.serverId == serverId && it.messageType == TimelineMessageType.ASSISTANT }

    @Test
    fun `stale real-final snapshot cannot truncate synthetic-live accumulator`() {
        // Live stream: synthetic-run row accumulates deltas under seq ids.
        val fullBody = "Filing it now and waiting was the right call."
        var tl = reduce(
            frame = liveFrame("assistant-x", "Filing it now and waiting was the", seqId = 4),
        ).next
        tl = reduce(
            prev = tl,
            frame = liveFrame("assistant-x", fullBody, seqId = 5),
        ).next
        assertEquals(fullBody, row(tl, "assistant-x").content)

        // Terminal settlement: real run id, but its snapshot is OLDER (seq 3)
        // and carries only the pre-final prefix. Must not shrink the body.
        Telemetry.clear()
        tl = reduce(
            prev = tl,
            frame = terminalFrame("assistant-x", "Filing it now and waiting was the", seqId = 3),
        ).next

        assertEquals(fullBody, row(tl, "assistant-x").content)
        assertEquals(TERMINAL_RUN_ID, row(tl, "assistant-x").runId, "id promotion must survive the fence")
        assertEquals(5, row(tl, "assistant-x").seqId, "stale settlement must retain the newest sequence")
        assertTrue(
            Telemetry.snapshot().any {
                it.tag == "TimelineSync" && it.name == "terminal.settlement.fence" &&
                    it.attrs["decision"] == "blocked"
            },
            "stale block must emit the discriminator telemetry; got: " + Telemetry.snapshot(),
        )
    }

    @Test
    fun `final-delta-first and terminal-first schedules converge to same body`() {
        val fullBody = "Filing it now and waiting was the right call."
        val prefix = "Filing it now and waiting was the"

        // Schedule A: deltas first (seq 4 prefix, seq 5 tail), then stale terminal (seq 3).
        var a = reduce(
            frame = liveFrame("assistant-cvg", prefix, seqId = 4),
        ).next
        a = reduce(
            prev = a,
            frame = liveFrame("assistant-cvg", " right call.", seqId = 5),
        ).next
        a = reduce(
            prev = a,
            frame = terminalFrame("assistant-cvg", prefix, seqId = 3),
        ).next

        // Schedule B: terminal snapshot first (seq 3), then the two deltas.
        var b = reduce(
            frame = terminalFrame("assistant-cvg", prefix, seqId = 3),
        ).next
        b = reduce(
            prev = b,
            frame = terminalFrame("assistant-cvg", prefix + " right call.", seqId = 4),
        ).next
        b = reduce(
            prev = b,
            frame = terminalFrame("assistant-cvg", " right call.", seqId = 5),
        ).next

        assertEquals(fullBody, row(a, "assistant-cvg").content)
        assertEquals(fullBody, row(b, "assistant-cvg").content)
    }

    @Test
    fun `duplicate stale terminal delivery stays idempotent`() {
        val fullBody = "done ✓"
        var tl = reduce(
            frame = liveFrame("assistant-idem", "don", seqId = 4),
        ).next
        tl = reduce(
            prev = tl,
            frame = liveFrame("assistant-idem", fullBody, seqId = 5),
        ).next
        val staleTerminal = terminalFrame("assistant-idem", "don", seqId = 3)
        tl = reduce(prev = tl, frame = staleTerminal).next
        tl = reduce(prev = tl, frame = staleTerminal).next
        assertEquals(fullBody, row(tl, "assistant-idem").content)
    }

    // criterion 7 tails: no UTF-8 / code-point / delimiter boundary may be cut
    @Test
    fun `tail shapes survive stale terminal for 1char word emoji markdown newline`() {
        data class Case(val label: String, val head: String, val tail: String)

        val cases = listOf(
            Case("one-char", "waiting was the right cal", "l."),
            Case("final-word", "waiting was the ", "right."),
            Case("emoji-multibyte", "status update ", "all good 🚀🔥"),
            Case("markdown-delimiter", "summary follows\n\n", "**bold conclusion**"),
            Case("newline-tail", "complete answer", "\n\nthanks"),
        )
        for (case in cases) {
            val full = case.head + case.tail
            var tl = reduce(
                frame = liveFrame("assistant-tails", case.head, seqId = 4),
            ).next
            tl = reduce(
                prev = tl,
                frame = liveFrame("assistant-tails", full, seqId = 5),
            ).next
            tl = reduce(
                prev = tl,
                frame = terminalFrame("assistant-tails", case.head, seqId = 3),
            ).next
            assertEquals(
                full, row(tl, "assistant-tails").content,
                "[${case.label}] stale terminal must not cut the ${case.label} tail",
            )
        }
    }

    // ---- reconcile-side fence ----------------------------------------------

    @Test
    fun `mergeServerMessages stale collapse cannot regress synthetic live row`() {
        val fullBody = "Filing it now and waiting was the right call."
        var tl = reduce(
            frame = liveFrame("assistant-rec", "Filing it now and waiting was the", seqId = 4),
        ).next
        tl = reduce(
            prev = tl,
            frame = liveFrame("assistant-rec", " right call.", seqId = 5),
        ).next

        val (mergedTl, _) = tl.mergeServerMessages(
            listOf(
                // Reconciled finals carry their own (or no) otid; sharing the
                // live otid would dedupe before settlement.
                reconciledFrame("assistant-rec", "Filing it now and waiting was the", seqId = 3),
            )
        )
        tl = mergedTl

        assertEquals(fullBody, row(tl, "assistant-rec").content)
    }

    @Test
    fun `mergeServerMessages stale superset final still replaces and grows`() {
        var tl = reduce(
            frame = liveFrame("assistant-grow", "partial view", seqId = 5),
        ).next

        val (grownTl, _) = tl.mergeServerMessages(
            listOf(
                reconciledFrame("assistant-grow", "partial view of the complete answer", seqId = 3),
            )
        )
        tl = grownTl

        assertEquals("partial view of the complete answer", row(tl, "assistant-grow").content)
    }
}
