package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolReturnMessage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalTimelineResolutionTest {
    @Test
    fun rejectedResponseResolvesSameRunWithoutReturn() {
        val request = event("request-1", "run-1", "call-1")
        val evidence = approvalTimelineEvidence(
            listOf(ApprovalResponseMessage("response-1", approvalRequestId = "request-1", approve = false, runId = "run-1")),
        )

        assertTrue(request.hasExplicitApprovalResponse(evidence))
    }

    @Test
    fun responseWithoutDecisionDoesNotResolve() {
        val request = event("request-1", "run-1", "call-1")
        val evidence = approvalTimelineEvidence(
            listOf(ApprovalResponseMessage("response-1", approvalRequestId = "request-1", runId = "run-1")),
        )

        assertFalse(request.hasExplicitApprovalResponse(evidence))
    }

    @Test
    fun mismatchedRunFailsOpen() {
        val request = event("request-1", "run-1", "call-1")
        val evidence = approvalTimelineEvidence(
            listOf(
                ToolReturnMessage("other", toolCallId = "call-1", status = "success", runId = "run-other"),
            ),
        )

        assertFalse(request.allApprovalCallsReturned(request.matchingToolReturns(evidence)))
    }

    @Test
    fun uniqueLegacyReturnWithoutRunResolves() {
        val request = event("request-1", "run-1", "call-1")
        val evidence = approvalTimelineEvidence(
            listOf(ToolReturnMessage("legacy", toolCallId = "call-1", status = "success")),
        )

        assertTrue(request.allApprovalCallsReturned(request.matchingToolReturns(evidence)))
    }

    @Test
    fun partialMultiCallReturnDoesNotResolve() {
        val request = event("request-1", "run-1", "call-1", "call-2")
        val evidence = approvalTimelineEvidence(
            listOf(ToolReturnMessage("return-1", toolCallId = "call-1", status = "success", runId = "run-1")),
        )

        assertFalse(request.allApprovalCallsReturned(request.matchingToolReturns(evidence)))
    }

    @Test
    fun everySameRunCallReturnResolves() {
        val request = event("request-1", "run-1", "call-1", "call-2")
        val evidence = approvalTimelineEvidence(
            listOf(
                ToolReturnMessage("return-1", toolCallId = "call-1", status = "success", runId = "run-1"),
                ToolReturnMessage("return-2", toolCallId = "call-2", status = "success", runId = "run-1"),
            ),
        )

        assertTrue(request.allApprovalCallsReturned(request.matchingToolReturns(evidence)))
    }

    private fun event(requestId: String, runId: String, vararg callIds: String) = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "otid-$requestId",
        content = "",
        serverId = requestId,
        messageType = TimelineMessageType.TOOL_CALL,
        date = parseTimelineInstant("2026-01-01T00:00:00Z"),
        runId = runId,
        stepId = null,
        toolCalls = callIds.map { ToolCall(toolCallId = it, name = "tool", arguments = "{}") }.toTimelinePersistentList(),
        approvalRequestId = requestId,
    )
}
