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
    fun responseRunCorrelation() {
        listOf(
            ResponseScenario("matching run", requestRun = "run-1", responseRun = "run-1", approve = true, decided = true),
            ResponseScenario("mismatched run", requestRun = "run-1", responseRun = "run-other", approve = false, decided = false),
            ResponseScenario("runless rejection", requestRun = null, responseRun = null, approve = false, decided = true),
        ).forEach { scenario ->
            val seeded = reduce(
                frame = request(runId = scenario.requestRun, calls = listOf(call("call-approval", "danger"))),
            ).next
            val output = reduce(
                prev = seeded,
                frame = response(approve = scenario.approve, runId = scenario.responseRun),
            )

            assertEquals(
                scenario.decided,
                (output.next.events.single() as TimelineEvent.Confirmed).approvalDecided,
                scenario.name,
            )
        }
    }

    @Test
    fun earlyPartialReturnDoesNotDecideMultiCallApproval() {
        val output = reduceWithPending(
            request(calls = listOf(call("call-a", "read"), call("call-b", "write"))),
            returned("call-a", "run-1", "read"),
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        assertEquals(false, event.approvalDecided)
        assertEquals("read", event.toolReturnContentByCallId["call-a"])
    }

    @Test
    fun earlyMismatchedRunReturnStaysPendingAndDoesNotAttach() {
        val output = reduceWithPending(
            request(calls = listOf(call("call-a", "read"))),
            returned("call-a", "run-other", "wrong"),
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        assertEquals(false, event.approvalDecided)
        assertEquals(null, event.toolReturnContentByCallId["call-a"])
        assertEquals(setOf("call-a"), output.updatedPendingToolReturnsByCallId.keys)
    }

    @Test
    fun multiCallApprovalDecidesOnlyAfterEveryReturn() {
        val seeded = reduce(
            frame = request(calls = listOf(call("call-a", "read"), call("call-b", "write"))),
        ).next
        val partial = reduce(
            prev = seeded,
            frame = returned("call-a", "run-1", "read"),
        ).next
        assertEquals(false, (partial.events.single() as TimelineEvent.Confirmed).approvalDecided)

        val completed = reduce(
            prev = partial,
            frame = returned("call-b", "run-1", "written"),
        ).next
        assertEquals(true, (completed.events.single() as TimelineEvent.Confirmed).approvalDecided)
    }

    private fun reduce(
        prev: Timeline = Timeline("conv-1"),
        frame: com.letta.mobile.data.model.LettaMessage,
    ) = reduceStreamFrame(TimelineReducerInput(prev, frame, persistentMapOf(), source = "test"))

    private fun reduceWithPending(request: ApprovalRequestMessage, returned: ToolReturnMessage) = reduceStreamFrame(
        TimelineReducerInput(
            prev = Timeline("conv-1"),
            frame = request,
            pendingToolReturnsByCallId = persistentMapOf(returned.toolCallId!! to returned),
            source = "test",
        ),
    )

    private fun request(runId: String? = "run-1", calls: List<ToolCall>) = ApprovalRequestMessage(
        id = "approval-1",
        runId = runId,
        toolCalls = calls,
    )

    private fun response(approve: Boolean, runId: String? = "run-1") = ApprovalResponseMessage(
        id = "response-1",
        approvalRequestId = "approval-1",
        approve = approve,
        runId = runId,
    )

    private fun call(id: String, name: String) = ToolCall(toolCallId = id, name = name, arguments = "{}")

    private fun returned(callId: String, runId: String?, body: String) = ToolReturnMessage(
        id = "return-$callId",
        toolCallId = callId,
        runId = runId,
        toolReturnRaw = JsonPrimitive(body),
    )

    private data class ResponseScenario(
        val name: String,
        val requestRun: String?,
        val responseRun: String?,
        val approve: Boolean,
        val decided: Boolean,
    )
}
