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

    @AfterTest
    fun tearDown() {
        Telemetry.clear()
    }

    // ---- direct fence unit tests -------------------------------------------

    @Test
    fun `fence passes when either sequence is absent`() {
        val d1 = evaluateTerminalSettlementFence(
            "c", "s", lastDeltaSeqId = null, terminalSeqId = 3,
            accumulatedText = "abc", terminalText = "a",
        )
        val d2 = evaluateTerminalSettlementFence(
            "c", "s", lastDeltaSeqId = 5, terminalSeqId = null,
            accumulatedText = "abc", terminalText = "a",
        )
        assertFalse(d1.blocked)
        assertFalse(d2.blocked)
        assertEquals("seq_absent", d1.reason)
    }

    @Test
    fun `fence passes when terminal seq is forward or equal`() {
        val newer = evaluateTerminalSettlementFence(
            "c", "s", lastDeltaSeqId = 5, terminalSeqId = 6,
            accumulatedText = "abc", terminalText = "xyz",
        )
        val equal = evaluateTerminalSettlementFence(
            "c", "s", lastDeltaSeqId = 5, terminalSeqId = 5,
            accumulatedText = "abc", terminalText = "xyz",
        )
        assertFalse(newer.blocked)
        assertFalse(equal.blocked)
    }

    @Test
    fun `fence blocks stale non-superset terminal`() {
        val decision = evaluateTerminalSettlementFence(
            "conv-9lgfu", "assistant-x",
            lastDeltaSeqId = 5, terminalSeqId = 3,
            accumulatedText = "waiting was the right call.",
            terminalText = "waiting was the",
        )
        assertTrue(decision.blocked)
        assertEquals("stale_terminal_not_superset", decision.reason)
    }

    @Test
    fun `fence allows stale terminal that is a content superset`() {
        val decision = evaluateTerminalSettlementFence(
            "conv-9lgfu", "assistant-x",
            lastDeltaSeqId = 5, terminalSeqId = 3,
            accumulatedText = "waiting was the",
            terminalText = "waiting was the right call.",
        )
        assertFalse(decision.blocked)
        assertEquals("stale_seq_but_superset", decision.reason)
    }

    // ---- reducer-level regression: the reported symptom --------------------

    private fun reduce(
        prev: Timeline = Timeline(conversationId = "conv-9lgfu"),
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
            frame = AssistantMessage(
                id = "assistant-x",
                contentRaw = JsonPrimitive("Filing it now and waiting was the"),
                runId = "iroh-run-1",
                otid = "otid-x",
                seqId = 4,
            ),
        ).next
        tl = reduce(
            prev = tl,
            frame = AssistantMessage(
                id = "assistant-x",
                contentRaw = JsonPrimitive(fullBody),
                runId = "iroh-run-1",
                otid = "otid-x",
                seqId = 5,
            ),
        ).next
        assertEquals(fullBody, row(tl, "assistant-x").content)

        // Terminal settlement: real run id, but its snapshot is OLDER (seq 3)
        // and carries only the pre-final prefix. Must not shrink the body.
        Telemetry.clear()
        tl = reduce(
            prev = tl,
            frame = AssistantMessage(
                id = "assistant-x",
                contentRaw = JsonPrimitive("Filing it now and waiting was the"),
                runId = "run-real-9",
                otid = "otid-x",
                seqId = 3,
            ),
        ).next

        assertEquals(fullBody, row(tl, "assistant-x").content)
        assertEquals("run-real-9", row(tl, "assistant-x").runId, "id promotion must survive the fence")
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
            frame = AssistantMessage(
                id = "assistant-cvg", contentRaw = JsonPrimitive(prefix),
                runId = "iroh-run-1", otid = "otid-cvg", seqId = 4,
            ),
        ).next
        a = reduce(
            prev = a,
            frame = AssistantMessage(
                id = "assistant-cvg", contentRaw = JsonPrimitive(" right call."),
                runId = "iroh-run-1", otid = "otid-cvg", seqId = 5,
            ),
        ).next
        a = reduce(
            prev = a,
            frame = AssistantMessage(
                id = "assistant-cvg", contentRaw = JsonPrimitive(prefix),
                runId = "run-real-9", otid = "otid-cvg", seqId = 3,
            ),
        ).next

        // Schedule B: terminal snapshot first (seq 3), then the two deltas.
        var b = reduce(
            frame = AssistantMessage(
                id = "assistant-cvg", contentRaw = JsonPrimitive(prefix),
                runId = "run-real-9", otid = "otid-cvg", seqId = 3,
            ),
        ).next
        b = reduce(
            prev = b,
            frame = AssistantMessage(
                id = "assistant-cvg", contentRaw = JsonPrimitive(prefix + " right call."),
                runId = "run-real-9", otid = "otid-cvg", seqId = 4,
            ),
        ).next
        b = reduce(
            prev = b,
            frame = AssistantMessage(
                id = "assistant-cvg", contentRaw = JsonPrimitive(" right call."),
                runId = "run-real-9", otid = "otid-cvg", seqId = 5,
            ),
        ).next

        assertEquals(fullBody, row(a, "assistant-cvg").content)
        assertEquals(fullBody, row(b, "assistant-cvg").content)
    }

    @Test
    fun `duplicate stale terminal delivery stays idempotent`() {
        val fullBody = "done ✓"
        var tl = reduce(
            frame = AssistantMessage(
                id = "assistant-idem", contentRaw = JsonPrimitive("don"),
                runId = "iroh-run-1", otid = "otid-idem", seqId = 4,
            ),
        ).next
        tl = reduce(
            prev = tl,
            frame = AssistantMessage(
                id = "assistant-idem", contentRaw = JsonPrimitive(fullBody),
                runId = "iroh-run-1", otid = "otid-idem", seqId = 5,
            ),
        ).next
        val staleTerminal = AssistantMessage(
            id = "assistant-idem", contentRaw = JsonPrimitive("don"),
            runId = "run-real-9", otid = "otid-idem", seqId = 3,
        )
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
                frame = AssistantMessage(
                    id = "assistant-tails", contentRaw = JsonPrimitive(case.head),
                    runId = "iroh-run-1", otid = "otid-tails", seqId = 4,
                ),
            ).next
            tl = reduce(
                prev = tl,
                frame = AssistantMessage(
                    id = "assistant-tails", contentRaw = JsonPrimitive(full),
                    runId = "iroh-run-1", otid = "otid-tails", seqId = 5,
                ),
            ).next
            tl = reduce(
                prev = tl,
                frame = AssistantMessage(
                    id = "assistant-tails", contentRaw = JsonPrimitive(case.head),
                    runId = "run-real-9", otid = "otid-tails", seqId = 3,
                ),
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
            frame = AssistantMessage(
                id = "assistant-rec", contentRaw = JsonPrimitive("Filing it now and waiting was the"),
                runId = "iroh-run-1", otid = "otid-rec", seqId = 4,
            ),
        ).next
        tl = reduce(
            prev = tl,
            frame = AssistantMessage(
                id = "assistant-rec", contentRaw = JsonPrimitive(" right call."),
                runId = "iroh-run-1", otid = "otid-rec", seqId = 5,
            ),
        ).next

        val (mergedTl, _) = tl.mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "assistant-rec",
                    contentRaw = JsonPrimitive("Filing it now and waiting was the"),
                    runId = "run-real-9",
                    // Reconciled finals carry their own (or no) otid — sharing
                    // the live row's otid would trip containsIdentityFor and
                    // never reach the settlement branch.
                    otid = null,
                    seqId = 3,
                ),
            )
        )
        tl = mergedTl

        assertEquals(fullBody, row(tl, "assistant-rec").content)
    }

    @Test
    fun `mergeServerMessages stale superset final still replaces and grows`() {
        var tl = reduce(
            frame = AssistantMessage(
                id = "assistant-grow", contentRaw = JsonPrimitive("partial view"),
                runId = "iroh-run-1", otid = "otid-grow", seqId = 5,
            ),
        ).next

        val (grownTl, _) = tl.mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "assistant-grow",
                    contentRaw = JsonPrimitive("partial view of the complete answer"),
                    runId = "run-real-9",
                    otid = null,
                    seqId = 3,
                ),
            )
        )
        tl = grownTl

        assertEquals("partial view of the complete answer", row(tl, "assistant-grow").content)
    }
}
