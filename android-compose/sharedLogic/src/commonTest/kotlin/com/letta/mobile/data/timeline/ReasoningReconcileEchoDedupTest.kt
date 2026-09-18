package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-6bw72: the "echoed thought".
 *
 * Reported repro (Android, consistent): send a message, the agent streams a
 * thought and then its final response, the turn settles — and a moment later
 * the THOUGHT reappears. No disconnect and no resume are involved; the extra
 * row arrives on the post-send reconcile ([PostSendReconciler] fires once live
 * ingest goes quiet, which is exactly "after everything settles").
 *
 * Mechanism: the reconciled copy is a SUPERSET of the streamed row rather than
 * byte-identical (the documented first-word lag), so
 * `recentTailContainsEquivalent`'s exact-content match misses, and every other
 * fallback in [mergeServerMessages] was gated to `ASSISTANT` — a reconciled
 * REASONING row had nothing left to match against and appended as a second row.
 *
 * Assistant prose hid the same defect downstream: run-panel echo compaction in
 * `MessageGrouping.runPanelEchoKey` drops a duplicate reply, and it explicitly
 * returns null for `isReasoning`. So reasoning was the only class where this
 * reached the screen.
 *
 * These tests fail on unmodified main (2 reasoning rows) and pass once the
 * content-superset fallback accepts REASONING.
 */
class ReasoningReconcileEchoDedupTest {
    @AfterTest
    fun tearDown() {
        Telemetry.clear()
    }

    private fun streamedReasoningRow(
        content: String,
        runId: String? = "run-real-123",
        serverId: String = "letta-msg-reason-1",
    ) = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "cm-reason-turn-1",
        serverId = serverId,
        content = content,
        messageType = TimelineMessageType.REASONING,
        date = timelineNow(),
        runId = runId,
        stepId = null,
    )

    /**
     * The streamed thought lost its first word to stream lag; the reconcile
     * final carries the whole thing under a fresh `ui-msg-*` id with a NULL run
     * id. One row must survive, holding the fuller text.
     */
    @Test
    fun `reconciled thought superset collapses into the streamed thought row`() {
        val timeline = Timeline(
            conversationId = "conv-6bw72",
            events = persistentListOf(
                streamedReasoningRow("user is asking about the build gates, so let me check"),
            ),
        )

        val (merged, _) = timeline.mergeServerMessages(
            listOf(
                ReasoningMessage(
                    id = "ui-msg-reason-final",
                    reasoning = "The user is asking about the build gates, so let me check",
                    date = "2026-09-17T00:00:00Z",
                    runId = null,
                ),
            ),
        )

        val reasoningRows = merged.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.REASONING }
        assertEquals(1, reasoningRows.size, "the reconciled thought must not echo as a second row")
        assertEquals(
            "The user is asking about the build gates, so let me check",
            reasoningRows.single().content,
            "the surviving row keeps the fuller reconciled text",
        )
    }

    /**
     * The collapse is type-scoped: a reconciled REPLY whose text happens to
     * contain an earlier THOUGHT must never overwrite that thought.
     */
    @Test
    fun `reconciled assistant reply never collapses into a reasoning row`() {
        val timeline = Timeline(
            conversationId = "conv-6bw72",
            events = persistentListOf(
                streamedReasoningRow("let me check the build gates"),
            ),
        )

        val (merged, _) = timeline.mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "ui-msg-assistant-final",
                    contentRaw = JsonPrimitive("Sure — let me check the build gates for you."),
                    date = "2026-09-17T00:00:00Z",
                    runId = null,
                ),
            ),
        )

        val confirmed = merged.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(
            1,
            confirmed.count { it.messageType == TimelineMessageType.REASONING },
            "the thought row must survive untouched",
        )
        assertEquals(
            1,
            confirmed.count { it.messageType == TimelineMessageType.ASSISTANT },
            "the reply is a distinct row",
        )
    }

    /**
     * Guard (1) still applies to reasoning: a row with a NULL run id is itself a
     * reconciled/replayed copy, not a streamed thought awaiting its final, so it
     * is never a collapse target. Two distinct thoughts must both survive.
     */
    @Test
    fun `reconciled thought does not collapse into an already reconciled thought`() {
        val timeline = Timeline(
            conversationId = "conv-6bw72",
            events = persistentListOf(
                streamedReasoningRow("checking the gates", runId = null),
            ),
        )

        val (merged, _) = timeline.mergeServerMessages(
            listOf(
                ReasoningMessage(
                    id = "ui-msg-reason-other",
                    reasoning = "Now checking the gates and then the tests",
                    date = "2026-09-17T00:00:00Z",
                    runId = null,
                ),
            ),
        )

        assertEquals(
            2,
            merged.events.filterIsInstance<TimelineEvent.Confirmed>()
                .count { it.messageType == TimelineMessageType.REASONING },
            "a null-run row is not a streamed thought and must not be overwritten",
        )
    }
}
