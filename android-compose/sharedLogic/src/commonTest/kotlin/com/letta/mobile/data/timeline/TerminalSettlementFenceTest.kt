package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmInline
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

    @JvmInline
    private value class TestMessageId(val value: String)

    @JvmInline
    private value class TestContent(val value: String)

    @JvmInline
    private value class TestSequence(val value: Int)

    private data class TailCase(val head: TestContent, val tail: TestContent)

    private enum class FrameKind {
        LIVE,
        TERMINAL,
        RECONCILED;

        val runId: String
            get() = if (this == LIVE) LIVE_RUN_ID else TERMINAL_RUN_ID

        val carriesOtid: Boolean
            get() = this != RECONCILED
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

    private val String.id: TestMessageId get() = TestMessageId(this)
    private val String.content: TestContent get() = TestContent(this)
    private val Int.sequence: TestSequence get() = TestSequence(this)

    private fun frame(
        kind: FrameKind,
        id: TestMessageId,
        content: TestContent,
        sequence: TestSequence,
    ): AssistantMessage = AssistantMessage(
        id = id.value,
        contentRaw = JsonPrimitive(content.value),
        runId = kind.runId,
        otid = if (kind.carriesOtid) "otid-${id.value}" else null,
        seqId = sequence.value,
    )

    private fun liveFrame(id: TestMessageId, content: TestContent, sequence: TestSequence) =
        frame(FrameKind.LIVE, id, content, sequence)

    private fun terminalFrame(id: TestMessageId, content: TestContent, sequence: TestSequence) =
        frame(FrameKind.TERMINAL, id, content, sequence)

    private fun reconciledFrame(id: TestMessageId, content: TestContent, sequence: TestSequence) =
        frame(FrameKind.RECONCILED, id, content, sequence)

    private fun evaluate(
        accumulatedText: TestContent,
        terminalText: TestContent,
        accumulatedSeqId: TestSequence? = 5.sequence,
        terminalSeqId: TestSequence? = 3.sequence,
    ): TerminalSettlementFenceDecision {
        val id = "assistant-x".id
        val accumulated = liveFrame(id, accumulatedText, accumulatedSeqId ?: 0.sequence)
            .toTimelineEvent(position = 0.0) as TimelineEvent.Confirmed
        val terminal = terminalFrame(id, terminalText, terminalSeqId ?: 0.sequence)
            .toTimelineEvent(position = 0.0) as TimelineEvent.Confirmed
        return evaluateTerminalSettlementFence(
            conversationId = CONVERSATION_ID,
            accumulated = accumulated.copy(seqId = accumulatedSeqId?.value),
            terminal = terminal.copy(seqId = terminalSeqId?.value),
        )
    }

    // ---- direct fence unit tests -------------------------------------------

    @Test
    fun `fence passes when either sequence is absent`() {
        val d1 = evaluate("abc".content, "a".content, accumulatedSeqId = null)
        val d2 = evaluate("abc".content, "a".content, terminalSeqId = null)
        assertFalse(d1.blocked)
        assertFalse(d2.blocked)
        assertEquals("seq_absent", d1.reason)
    }

    @Test
    fun `fence passes when terminal seq is forward or equal`() {
        val newer = evaluate("abc".content, "xyz".content, terminalSeqId = 6.sequence)
        val equal = evaluate("abc".content, "xyz".content, terminalSeqId = 5.sequence)
        assertFalse(newer.blocked)
        assertFalse(equal.blocked)
    }

    @Test
    fun `fence blocks stale non-superset terminal`() {
        val decision = evaluate("waiting was the right call.".content, "waiting was the".content)
        assertTrue(decision.blocked)
        assertEquals("stale_terminal_not_superset", decision.reason)
    }

    @Test
    fun `fence allows stale terminal that is a content superset`() {
        val decision = evaluate("waiting was the".content, "waiting was the right call.".content)
        assertFalse(decision.blocked)
        assertEquals("stale_seq_but_superset", decision.reason)
    }

    @Test
    fun `fence preserves meaningful leading and trailing whitespace`() {
        val missingIndent = evaluate("    code()\n".content, "code()".content)
        val exactFormatting = evaluate("    code()\n".content, "prefix\n    code()\n".content)

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

    private fun row(timeline: Timeline, serverId: TestMessageId): TimelineEvent.Confirmed =
        timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.serverId == serverId.value && it.messageType == TimelineMessageType.ASSISTANT }

    @Test
    fun `stale real-final snapshot cannot truncate synthetic-live accumulator`() {
        // Live stream: synthetic-run row accumulates deltas under seq ids.
        val messageId = "assistant-x".id
        val fullBody = "Filing it now and waiting was the right call."
        var tl = reduce(
            frame = liveFrame(messageId, "Filing it now and waiting was the".content, 4.sequence),
        ).next
        tl = reduce(
            prev = tl,
            frame = liveFrame(messageId, fullBody.content, 5.sequence),
        ).next
        assertEquals(fullBody, row(tl, messageId).content)

        // Terminal settlement: real run id, but its snapshot is OLDER (seq 3)
        // and carries only the pre-final prefix. Must not shrink the body.
        Telemetry.clear()
        tl = reduce(
            prev = tl,
            frame = terminalFrame(messageId, "Filing it now and waiting was the".content, 3.sequence),
        ).next

        assertEquals(fullBody, row(tl, messageId).content)
        assertEquals(TERMINAL_RUN_ID, row(tl, messageId).runId, "id promotion must survive the fence")
        assertEquals(5, row(tl, messageId).seqId, "stale settlement must retain the newest sequence")
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
        val messageId = "assistant-cvg".id
        val fullBody = "Filing it now and waiting was the right call."
        val prefix = "Filing it now and waiting was the"

        // Schedule A: deltas first (seq 4 prefix, seq 5 tail), then stale terminal (seq 3).
        var a = reduce(
            frame = liveFrame(messageId, prefix.content, 4.sequence),
        ).next
        a = reduce(
            prev = a,
            frame = liveFrame(messageId, " right call.".content, 5.sequence),
        ).next
        a = reduce(
            prev = a,
            frame = terminalFrame(messageId, prefix.content, 3.sequence),
        ).next

        // Schedule B: terminal snapshot first (seq 3), then the two deltas.
        var b = reduce(
            frame = terminalFrame(messageId, prefix.content, 3.sequence),
        ).next
        b = reduce(
            prev = b,
            frame = terminalFrame(messageId, (prefix + " right call.").content, 4.sequence),
        ).next
        b = reduce(
            prev = b,
            frame = terminalFrame(messageId, " right call.".content, 5.sequence),
        ).next

        assertEquals(fullBody, row(a, messageId).content)
        assertEquals(fullBody, row(b, messageId).content)
    }

    @Test
    fun `duplicate stale terminal delivery stays idempotent`() {
        val messageId = "assistant-idem".id
        val fullBody = "done ✓"
        var tl = reduce(
            frame = liveFrame(messageId, "don".content, 4.sequence),
        ).next
        tl = reduce(
            prev = tl,
            frame = liveFrame(messageId, fullBody.content, 5.sequence),
        ).next
        val staleTerminal = terminalFrame(messageId, "don".content, 3.sequence)
        tl = reduce(prev = tl, frame = staleTerminal).next
        tl = reduce(prev = tl, frame = staleTerminal).next
        assertEquals(fullBody, row(tl, messageId).content)
    }

    // criterion 7 tails: no UTF-8 / code-point / delimiter boundary may be cut
    @Test
    fun `tail shapes survive stale terminal for 1char word emoji markdown newline`() {
        val messageId = "assistant-tails".id
        val cases = listOf(
            TailCase("waiting was the right cal".content, "l.".content),
            TailCase("waiting was the ".content, "right.".content),
            TailCase("status update ".content, "all good 🚀🔥".content),
            TailCase("summary follows\n\n".content, "**bold conclusion**".content),
            TailCase("complete answer".content, "\n\nthanks".content),
        )
        for (case in cases) {
            val full = case.head.value + case.tail.value
            var tl = reduce(
                frame = liveFrame(messageId, case.head, 4.sequence),
            ).next
            tl = reduce(
                prev = tl,
                frame = liveFrame(messageId, full.content, 5.sequence),
            ).next
            tl = reduce(
                prev = tl,
                frame = terminalFrame(messageId, case.head, 3.sequence),
            ).next
            assertEquals(
                full, row(tl, messageId).content,
                "stale terminal must not cut the ${case.tail.value} tail",
            )
        }
    }

    // ---- reconcile-side fence ----------------------------------------------

    @Test
    fun `mergeServerMessages stale collapse cannot regress synthetic live row`() {
        val messageId = "assistant-rec".id
        val fullBody = "Filing it now and waiting was the right call."
        var tl = reduce(
            frame = liveFrame(messageId, "Filing it now and waiting was the".content, 4.sequence),
        ).next
        tl = reduce(
            prev = tl,
            frame = liveFrame(messageId, " right call.".content, 5.sequence),
        ).next

        val (mergedTl, _) = tl.mergeServerMessages(
            listOf(
                // Reconciled finals carry their own (or no) otid; sharing the
                // live otid would dedupe before settlement.
                reconciledFrame(messageId, "Filing it now and waiting was the".content, 3.sequence),
            )
        )
        tl = mergedTl

        assertEquals(fullBody, row(tl, messageId).content)
    }

    @Test
    fun `mergeServerMessages stale superset final still replaces and grows`() {
        val messageId = "assistant-grow".id
        var tl = reduce(
            frame = liveFrame(messageId, "partial view".content, 5.sequence),
        ).next

        val (grownTl, _) = tl.mergeServerMessages(
            listOf(
                reconciledFrame(messageId, "partial view of the complete answer".content, 3.sequence),
            )
        )
        tl = grownTl

        assertEquals("partial view of the complete answer", row(tl, messageId).content)
    }
}
