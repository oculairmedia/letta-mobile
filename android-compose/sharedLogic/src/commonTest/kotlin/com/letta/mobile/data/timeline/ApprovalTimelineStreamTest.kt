package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ApprovalTimelineStreamTest {
    @Test
    fun runlessLiveRejectionDecidesUniqueRequest() {
        val seeded = reduce(
            frame = ApprovalRequestMessage(
                id = "approval-1",
                toolCall = ToolCall(toolCallId = "call-1", name = "danger", arguments = "{}"),
            ),
        ).next

        val output = reduce(
            prev = seeded,
            frame = ApprovalResponseMessage(
                id = "response-1",
                approvalRequestId = "approval-1",
                approve = false,
            ),
        )

        assertEquals(true, (output.next.events.single() as TimelineEvent.Confirmed).approvalDecided)
    }

    @Test
    fun earlyPartialReturnDoesNotDecideMultiCallApproval() {
        val pending = persistentMapOf(
            "call-a" to ToolReturnMessage(
                "return-a",
                toolCallId = "call-a",
                runId = "run-1",
                toolReturnRaw = JsonPrimitive("read"),
            ),
        )
        val output = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline("conv-1"),
                frame = ApprovalRequestMessage(
                    id = "approval-1",
                    runId = "run-1",
                    toolCalls = listOf(
                        ToolCall(toolCallId = "call-a", name = "read", arguments = "a"),
                        ToolCall(toolCallId = "call-b", name = "write", arguments = "b"),
                    ),
                ),
                pendingToolReturnsByCallId = pending,
                source = "test",
            ),
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        assertEquals(false, event.approvalDecided)
        assertEquals("read", event.toolReturnContentByCallId["call-a"])
    }

    @Test
    fun earlyMismatchedRunReturnStaysPendingAndDoesNotAttach() {
        val pending = persistentMapOf(
            "call-a" to ToolReturnMessage(
                "return-a",
                toolCallId = "call-a",
                runId = "run-other",
                toolReturnRaw = JsonPrimitive("wrong"),
            ),
        )
        val output = reduceStreamFrame(
            TimelineReducerInput(
                prev = Timeline("conv-1"),
                frame = ApprovalRequestMessage(
                    id = "approval-1",
                    runId = "run-1",
                    toolCall = ToolCall(toolCallId = "call-a", name = "read", arguments = "a"),
                ),
                pendingToolReturnsByCallId = pending,
                source = "test",
            ),
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        assertEquals(false, event.approvalDecided)
        assertEquals(null, event.toolReturnContentByCallId["call-a"])
        assertEquals(setOf("call-a"), output.updatedPendingToolReturnsByCallId.keys)
    }

    @Test
    fun matchingResponseRunDecidesApproval() {
        val seeded = reduce(
            frame = ApprovalRequestMessage(
                id = "approval-1",
                toolCall = ToolCall(toolCallId = "call-approval", name = "danger", arguments = "{}"),
                runId = "run-1",
            ),
        ).next

        val output = reduce(
            prev = seeded,
            frame = ApprovalResponseMessage(
                id = "approval-response-1",
                approvalRequestId = "approval-1",
                approve = true,
                runId = "run-1",
            ),
        )

        assertEquals(true, (output.next.events.single() as TimelineEvent.Confirmed).approvalDecided)
    }

    @Test
    fun multiCallApprovalDecidesOnlyAfterEveryReturn() {
        val seeded = reduce(
            frame = ApprovalRequestMessage(
                id = "approval-1",
                runId = "run-1",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-a", name = "read", arguments = "a"),
                    ToolCall(toolCallId = "call-b", name = "write", arguments = "b"),
                ),
            ),
        ).next
        val partial = reduce(
            prev = seeded,
            frame = ToolReturnMessage("return-a", toolCallId = "call-a", runId = "run-1", toolReturnRaw = JsonPrimitive("read")),
        ).next
        assertEquals(false, (partial.events.single() as TimelineEvent.Confirmed).approvalDecided)

        val completed = reduce(
            prev = partial,
            frame = ToolReturnMessage("return-b", toolCallId = "call-b", runId = "run-1", toolReturnRaw = JsonPrimitive("written")),
        ).next
        assertEquals(true, (completed.events.single() as TimelineEvent.Confirmed).approvalDecided)
    }

    @Test
    fun mismatchedResponseRunLeavesApprovalPending() {
        val seeded = reduce(
            frame = ApprovalRequestMessage(
                id = "approval-1",
                toolCall = ToolCall(toolCallId = "call-approval", name = "danger", arguments = "{}"),
                runId = "run-1",
            ),
        ).next

        val output = reduce(
            prev = seeded,
            frame = ApprovalResponseMessage(
                id = "approval-response-1",
                approvalRequestId = "approval-1",
                approve = false,
                runId = "run-other",
            ),
        )

        assertEquals(false, (output.next.events.single() as TimelineEvent.Confirmed).approvalDecided)
    }

    private fun reduce(
        prev: Timeline = Timeline("conv-1"),
        frame: com.letta.mobile.data.model.LettaMessage,
    ) = reduceStreamFrame(TimelineReducerInput(prev, frame, persistentMapOf(), source = "test"))
}
