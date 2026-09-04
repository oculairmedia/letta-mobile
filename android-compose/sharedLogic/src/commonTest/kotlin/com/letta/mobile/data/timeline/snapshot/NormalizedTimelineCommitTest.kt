package com.letta.mobile.data.timeline.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NormalizedTimelineCommitTest {
    private val scope = TimelineScope("backend", "conversation", "agent")

    @Test
    fun oneEventChangesEncodeOnlyChangedRowsAndReconstructExactly() {
        val store = InMemoryNormalizedTimelineStore()
        val initial = envelope(1L, fixtureEvents())
        val initialPlan = assertIs<NormalizedTimelineCommitPlan.Apply>(NormalizedTimelineCommitPlanner.plan(null, initial))
        assertEquals(2_000, initialPlan.commit.encodedRows)
        assertEquals(NormalizedTimelineWriteResult.Committed(TimelineRevision(1L)), store.apply(initialPlan))
        assertEquals(initial, store.read(scope))

        val appended = envelope(2L, initial.events + event(2_000))
        val appendPlan = assertIs<NormalizedTimelineCommitPlan.Apply>(NormalizedTimelineCommitPlanner.plan(initial, appended))
        assertEquals(1, appendPlan.commit.encodedRows)
        assertEquals(0, appendPlan.commit.fullEnvelopeEncodes)
        assertEquals(4_001, appendPlan.commit.comparisonEvents)
        assertEquals(NormalizedTimelineWriteResult.Committed(TimelineRevision(2L)), store.apply(appendPlan))
        assertEquals(appended, store.read(scope))

        val updatedEvent = appended.events[1_000].copy(
            approvalRequestId = "approval",
            approvalDecided = true,
            approvalDecision = "APPROVED",
            toolReturnContent = "updated-return",
            toolReturnIsError = true,
            toolReturnTruncationByCallId = mapOf("call-1000" to StoredToolReturnTruncation("ret", 42L)),
            attachments = listOf(StoredImageAttachmentPointer("image/png", 12L, "pointer://image")),
        )
        val updated = envelope(3L, appended.events.toMutableList().also { it[1_000] = updatedEvent })
        val updatePlan = assertIs<NormalizedTimelineCommitPlan.Apply>(NormalizedTimelineCommitPlanner.plan(appended, updated))
        assertEquals(1, updatePlan.commit.encodedRows)
        assertEquals(0, updatePlan.commit.fullEnvelopeEncodes)
        assertEquals(NormalizedTimelineWriteResult.Committed(TimelineRevision(3L)), store.apply(updatePlan))
        assertEquals(updated, store.read(scope))
    }

    @Test
    fun deleteReorderCursorNoOpAndStaleWriterAreTyped() {
        val store = InMemoryNormalizedTimelineStore()
        val initial = envelope(1L, fixtureEvents())
        store.apply(NormalizedTimelineCommitPlanner.plan(null, initial))

        val deleted = envelope(2L, initial.events.drop(25))
        val deletePlan = assertIs<NormalizedTimelineCommitPlan.Apply>(NormalizedTimelineCommitPlanner.plan(initial, deleted))
        assertEquals(25, deletePlan.commit.deletes.size)
        assertEquals(1_975, deletePlan.commit.upserts.size)
        store.apply(deletePlan)
        assertEquals(deleted, store.read(scope))

        val reordered = envelope(3L, deleted.events.toMutableList().also { it.add(it.removeAt(0)) })
        val reorderPlan = assertIs<NormalizedTimelineCommitPlan.Apply>(NormalizedTimelineCommitPlanner.plan(deleted, reordered))
        assertEquals(reordered.events.size, reorderPlan.commit.upserts.size)
        store.apply(reorderPlan)

        val cursorOnly = reordered.copy(revision = 4L, liveCursor = "cursor-next", writtenAtMillis = 4L)
        val cursorPlan = assertIs<NormalizedTimelineCommitPlan.Apply>(NormalizedTimelineCommitPlanner.plan(reordered, cursorOnly))
        assertEquals(0, cursorPlan.commit.encodedRows)
        store.apply(cursorPlan)
        assertEquals(cursorOnly, store.read(scope))

        val noOp = cursorOnly.copy(revision = 5L)
        val noOpPlan = assertIs<NormalizedTimelineCommitPlan.NoOp>(NormalizedTimelineCommitPlanner.plan(cursorOnly, noOp))
        assertEquals(NormalizedTimelineWriteResult.NoOp(TimelineRevision(5L)), store.apply(noOpPlan))

        val changedAfterNoOp = noOp.copy(revision = 6L, events = noOp.events + event(2_500), writtenAtMillis = 6L)
        val changedAfterNoOpPlan = assertIs<NormalizedTimelineCommitPlan.Apply>(
            NormalizedTimelineCommitPlanner.plan(noOp, changedAfterNoOp),
        )
        assertEquals(
            NormalizedTimelineWriteResult.Committed(TimelineRevision(6L)),
            store.apply(changedAfterNoOpPlan),
        )
        assertEquals(changedAfterNoOp, store.read(scope))

        val stale = envelope(5L, reordered.events + event(3_000))
        val stalePlan = assertIs<NormalizedTimelineCommitPlan.Apply>(NormalizedTimelineCommitPlanner.plan(reordered, stale))
        assertEquals(NormalizedTimelineWriteResult.Stale(TimelineRevision(6L)), store.apply(stalePlan))
        assertEquals(changedAfterNoOp, store.read(scope))
    }

    @Test
    fun missingDuplicateAndWrongScopeFailClosed() {
        val valid = envelope(1L, listOf(event(1)))
        val missingIdentity = envelope(2L, listOf(event(1).copy(serverId = "", otid = "")))
        assertEquals(
            NormalizedTimelineCommitPlan.Invalid(NormalizedTimelineCommitFailure.AMBIGUOUS_EVENT_IDENTITY),
            NormalizedTimelineCommitPlanner.plan(valid, missingIdentity),
        )
        val duplicate = envelope(2L, listOf(event(1), event(1).copy(content = "different")))
        assertEquals(
            NormalizedTimelineCommitPlan.Invalid(NormalizedTimelineCommitFailure.AMBIGUOUS_EVENT_IDENTITY),
            NormalizedTimelineCommitPlanner.plan(valid, duplicate),
        )
        assertEquals(
            NormalizedTimelineCommitPlan.Invalid(NormalizedTimelineCommitFailure.SCOPE_MISMATCH),
            NormalizedTimelineCommitPlanner.plan(valid, valid.copy(scope = TimelineScope("backend", "other"), revision = 2L)),
        )
        assertEquals("scope_mismatch", NormalizedTimelineCommitFailure.SCOPE_MISMATCH.toStorageValue())
        assertEquals(
            NormalizedTimelineCommitFailure.SCOPE_MISMATCH,
            normalizedTimelineCommitFailureFromStorage("scope_mismatch"),
        )
        assertEquals("unsupported_plan", NormalizedTimelineCommitFailure.UNSUPPORTED_PLAN.toStorageValue())
        assertEquals(
            NormalizedTimelineCommitFailure.UNSUPPORTED_PLAN,
            normalizedTimelineCommitFailureFromStorage("unsupported_plan"),
        )
        assertNull(normalizedTimelineCommitFailureFromStorage("future_failure"))
    }

    private fun envelope(revision: Long, events: List<StoredTimelineEvent>) = StoredTimelineEnvelope(
        scope = scope,
        revision = revision,
        liveCursor = "cursor",
        backfillCursor = "backfill",
        releasedOlderCount = 7,
        events = events,
        writtenAtMillis = revision,
    )

    private fun fixtureEvents() = List(2_000, ::event)

    private fun event(index: Int) = StoredTimelineEvent(
        position = index.toDouble(),
        otid = "otid-$index",
        content = "content-$index",
        serverId = "server-$index",
        messageType = "TOOL_CALL",
        dateIso = "2026-08-24T12:00:00Z",
        runId = "run-${index / 10}",
        stepId = "step-$index",
        agentId = "agent",
        seqId = index,
        toolCalls = listOf(StoredToolCall("call-$index", "tool", "{\"index\":$index}")),
        toolReturnContentByCallId = mapOf("call-$index" to "return-$index"),
    )
}
