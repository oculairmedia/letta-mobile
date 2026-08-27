package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimelineReducerContractsTest {
    private val instant = parseTimelineInstant("2026-01-01T00:00:00Z")

    @Test
    fun localTransitionsPreserveCurrentDuplicateAndRetrySemantics() {
        val initial = TimelineReducerState(Timeline("conversation"))
        val append = reduceLocalAppend(initial, LocalAppendPayload("otid", "hello", sentAt = instant))
        assertTrue(append.result.changed)
        assertEquals(DeliveryState.SENDING, (append.next.timeline.events.single() as TimelineEvent.Local).deliveryState)
        assertIs<TimelineReductionEffect.Send>(append.effects.first())

        val duplicate = reduceLocalAppend(append.next, LocalAppendPayload("otid", "other", sentAt = instant))
        assertFalse(duplicate.result.changed)
        assertSame(append.next, duplicate.next)

        val failed = reduceMarkLocalFailed(append.next, "otid")
        val retry = reduceRetryLocal(failed.next, "otid")
        assertEquals(DeliveryState.SENDING, (retry.next.timeline.events.single() as TimelineEvent.Local).deliveryState)
        val send = assertIs<TimelineReductionEffect.Send>(retry.effects.single())
        assertEquals("hello", send.pending.content)
        assertEquals(DeliveryState.SENT, (reduceMarkLocalSent(retry.next, "otid").next.timeline.events.single() as TimelineEvent.Local).deliveryState)
    }

    @Test
    fun snapshotEnrichmentAttachesReturnAndExplicitApproval() {
        val tool = toolEvent()
        val state = TimelineReducerState(Timeline("conversation", persistentListOf(tool)))
        val snapshot = listOf(
            ApprovalResponseMessage("response", approvalRequestId = "request", approve = false, runId = "run"),
            ToolReturnMessage(
                id = "return",
                toolCallId = "call",
                toolReturnRaw = JsonPrimitive("result"),
                status = "success",
                runId = "run",
            ),
        )

        val reduction = reduceSnapshotEnrichment(state, snapshot)
        val enriched = reduction.next.timeline.events.single() as TimelineEvent.Confirmed
        assertTrue(enriched.approvalDecided)
        assertEquals(ApprovalDecision.REJECTED, enriched.approvalDecision)
        assertEquals("result", enriched.toolReturnContentByCallId["call"])
        assertEquals(tool.attachments, enriched.attachments)
        assertFalse(reduceSnapshotEnrichment(reduction.next, snapshot).result.changed)
    }

    @Test
    fun cleanupReportsNoOpAndChangeUsingTimelineCleanupSemantics() {
        val full = assistant("full", "Hello", 1.0)
        val fragment = assistant("fragment", "Hi", 2.0)
        val state = TimelineReducerState(Timeline("conversation", persistentListOf(full, fragment)))

        assertFalse(reduceCleanup(state, "other", null, "test").result.changed)
        val cleanup = reduceCleanup(state, "run", "turn", "test")
        assertTrue(cleanup.result.changed)
        assertEquals(listOf("full"), cleanup.next.timeline.events.map { (it as TimelineEvent.Confirmed).serverId })
    }

    @Test
    fun postSendReconcileReplacesLocalMergesMissingAndAdvancesCursor() {
        val local = TimelineEvent.Local(1.0, "client", "hello", Role.USER, instant, DeliveryState.SENT)
        val state = TimelineReducerState(Timeline("conversation", persistentListOf(local)))
        val server = listOf(
            UserMessage("user-server", JsonPrimitive("hello"), otid = "client", date = "2026-01-01T00:00:01Z"),
            UserMessage("other-server", JsonPrimitive("other"), date = "2026-01-01T00:00:02Z"),
        )

        val reduction = reducePostSendReconcile(state, "client", server)
        val events = reduction.next.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(listOf("user-server", "other-server"), events.map { it.serverId })
        assertEquals(1.0, events.first().position)
        assertEquals("other-server", reduction.next.timeline.liveCursor)
        assertTrue(reduction.effects.any { it is TimelineReductionEffect.DeletePendingLocal })
        assertTrue(reduction.effects.any { it is TimelineReductionEffect.AdvanceCursor && it.cursor == "other-server" })
    }

    @Test
    fun postSendContentFallbackRejectsOlderAndConflictingMatches() {
        val local = TimelineEvent.Local(1.0, "client", "OK", Role.USER, instant, DeliveryState.SENT)
        val state = TimelineReducerState(Timeline("conversation", persistentListOf(local)))
        val server = listOf(
            UserMessage("old", JsonPrimitive("OK"), date = "2025-12-31T23:50:00Z"),
            UserMessage("conflict", JsonPrimitive("OK"), otid = "another-client", date = "2026-01-01T00:00:30Z"),
            UserMessage("recent", JsonPrimitive("OK"), date = "2026-01-01T00:01:00Z"),
        )

        val reduction = reducePostSendReconcile(state, "client", server)

        assertEquals("recent", reduction.effects.filterIsInstance<TimelineReductionEffect.EmitSyncEvent>()
            .mapNotNull { (it.event as? TimelineSyncEvent.LocalConfirmed)?.serverId }
            .single())
        assertFalse(reduction.next.timeline.events.any {
            it is TimelineEvent.Confirmed && it.serverId == "conflict" && it.otid == "client"
        })
    }

    private fun toolEvent() = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "tool-otid",
        content = "tool",
        serverId = "request",
        messageType = TimelineMessageType.TOOL_CALL,
        date = instant,
        runId = "run",
        stepId = null,
        toolCalls = persistentListOf(ToolCall(toolCallId = "call", name = "tool", arguments = "{}")),
        approvalRequestId = "request",
    )

    private fun assistant(serverId: String, content: String, position: Double) = TimelineEvent.Confirmed(
        position = position,
        otid = "otid-$serverId",
        content = content,
        serverId = serverId,
        messageType = TimelineMessageType.ASSISTANT,
        date = instant,
        runId = "run",
        stepId = null,
    )
}
