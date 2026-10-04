package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-dzt3g: the epic's fail-on-revert properties (letta-mobile-4vtng), each one replayed
 * from raw `stream_delta` wire lines through the phone's observer mapping, the real coordinator and
 * presentation, and checked on every frame by [timelineInvariantViolations].
 *
 * Frames without a host stamp get one from [ObserverFrameReplay], as the host does. The two-replies
 * tests were red until the stamped identity landed (C1 and S1, letta-mobile-ys9it and
 * letta-mobile-jdcoj) and are hard assertions now. The one test still ignored needs the text
 * sequence reducer (C2) and keeps its assertion intact.
 */
class TimelineIdentityInvariantsTest {
    /**
     * The owner's screenshot: one reply's cumulative snapshots A1..An alternate with a constant frame B
     * under ONE otid and rotating ids. Green on main only because of #1760's SNAPSHOT_REWRITE stopgap;
     * it must STAY green when D2 (letta-mobile-qhj9o) deletes that stopgap, where C2
     * (letta-mobile-nbha6) provides the real fix.
     */
    @Test fun stackedCopiesFixtureNeverDrawsAReplyTwice() = replay(STACKED_COPIES) { frames ->
        assertEquals(emptyList(), rowIdentityViolations(frames), frames.joinToString("\n"))
    }

    /**
     * What the stopgap cannot give: under it the two writers take the row in turns, so the text jumps
     * between A and B instead of stacking. Only a text sequence (C2, letta-mobile-nbha6) makes the
     * stale writer's frames lose, so the text only grows. Red until C2.
     */
    @Ignore("RED until C2: letta-mobile-nbha6")
    @Test fun stackedCopiesFixtureTextOnlyGrows() = replay(STACKED_COPIES) { frames ->
        assertEquals(emptyList(), textGrowthViolations(frames), frames.joinToString("\n"))
    }

    /**
     * Two replies in one conversation, neither carrying a message id or otid, a tool call between.
     * Before the stamped identity the observer's per-conversation otid merged them into one row.
     */
    @Test fun observerFramesOfTwoRepliesInOneConversationStayTwoRows() = replay(
        RawWireFrames.anonymousReply(1, FIRST_REPLY) + toolRound(runId = null) + RawWireFrames.anonymousReply(10, SECOND_REPLY),
    ) { frames -> assertTwoReplyRows(frames) }

    /**
     * The real-capture shape (grok-4.7 and any preamble before a tool call): two assistant messages
     * with distinct wire ids and no otid. The observer's per-conversation otid used to merge them, so
     * the row read preamble + every snapshot of the second message ("I run dateTheThe dateThe date
     * is Sat.", the owner's garbled screenshots, 2026-10-03).
     */
    @Test fun twoAssistantMessagesWithDistinctWireIdsStayTwoRows() = replay(
        listOf(
            RawDelta(1, "assistant_message", "I run date", id = "ui-msg-1"),
            RawDelta(2, "assistant_message", "The", id = "ui-msg-2:assistant:1"),
            RawDelta(3, "assistant_message", "The date", id = "ui-msg-2:assistant:1"),
            RawDelta(4, "assistant_message", "The date is Sat.", id = "ui-msg-2:assistant:1"),
        ).map(RawWireFrames::line),
    ) { frames ->
        val contents = frames.last().rows.flatMap { it.contents }
        assertEquals(setOf("I run date", "The date is Sat."), contents.toSet(), frames.joinToString("\n"))
    }

    /**
     * Two replies of one run, each streamed under rotating ids, a tool call between: the run id must
     * not merge them.
     */
    @Test fun runKeyedTwoAssistantMessagesStayTwoRows() = replay(
        RawWireFrames.rotatingReply(1, "run-7", "one", FIRST_REPLY) + toolRound(runId = "run-7") +
            RawWireFrames.rotatingReply(10, "run-7", "two", SECOND_REPLY),
    ) { frames -> assertTwoReplyRows(frames) }

    /**
     * A redial restarts the viewer's event_seq at 0 in the middle of a message. Text ordering must not
     * depend on it: the message keeps growing.
     */
    @Test fun textNeverShrinksAcrossRedialSeqReset() = replay(
        listOf(
            RawDelta(5, "assistant_message", "Omelette is", id = "ui-msg-7"),
            RawDelta(0, "assistant_message", "Omelette is the answer.", id = "ui-msg-7"),
        ).map(RawWireFrames::line),
    ) { frames ->
        assertEquals(emptyList(), timelineInvariantViolations(frames), frames.joinToString("\n"))
        assertEquals(listOf("Omelette is the answer."), frames.last().rows.flatMap { it.contents })
    }

    private fun assertTwoReplyRows(frames: List<TimelineFrameRecorder.Frame>) {
        val contents = frames.last().rows.flatMap { it.contents }
        assertTrue(contents.containsAll(listOf(FIRST_REPLY.last(), SECOND_REPLY.last())), "replies merged:\n" + frames.joinToString("\n"))
        assertEquals(emptyList(), timelineInvariantViolations(frames), frames.joinToString("\n"))
    }

    private fun replay(lines: List<String>, check: (List<TimelineFrameRecorder.Frame>) -> Unit) = runBlocking {
        val recorder = TimelineFrameRecorder.open(scope)
        try {
            recorder.streamRaw(lines)
            check(recorder.frames)
        } finally {
            recorder.close()
        }
    }

    private companion object {
        val scope = TimelineScope("backend", RawWireFrames.CONVERSATION, RawWireFrames.AGENT)
        val STACKED_COPIES = RawWireFrames.resource("/timeline-fixtures/raw-frames/stacked-copies-two-writers.jsonl")
        /** A tool call between two replies: the boundary that makes the second one a new message. */
        fun toolRound(runId: String?) = listOf(RawWireFrames.line(RawDelta(9, "tool_call_message", runId = runId)))
        val FIRST_REPLY = listOf("Omelette", "Omelette with", "Omelette with chives.")
        val SECOND_REPLY = listOf("Pancakes", "Pancakes with", "Pancakes with syrup.")
    }
}
