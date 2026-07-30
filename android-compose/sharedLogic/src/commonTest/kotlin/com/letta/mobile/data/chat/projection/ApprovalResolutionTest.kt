package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolReturnMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class ApprovalResolutionTest {
    @Test
    fun rejectedResponseResolvesWithoutToolReturn() {
        assertEquals(
            setOf("request-1"),
            resolvedApprovalRequestIds(listOf(request("request-1", "run-1", "call-1"), response("request-1", "run-1"))),
        )
    }

    @Test
    fun everyCallMustReturn() {
        assertEquals(
            emptySet(),
            resolvedApprovalRequestIds(listOf(request("request-1", "run-1", "call-1", "call-2"), returned("call-1", "run-1"))),
        )
    }

    @Test
    fun mismatchedReturnRunDoesNotResolve() {
        assertEquals(
            emptySet(),
            resolvedApprovalRequestIds(listOf(request("request-1", "run-1", "call-1"), returned("call-1", "run-other"))),
        )
    }

    @Test
    fun blankCallIdFailsOpen() {
        assertEquals(
            emptySet(),
            resolvedApprovalRequestIds(listOf(request("request-1", "run-1", "call-1", ""), returned("call-1", "run-1"))),
        )
    }

    @Test
    fun completeSameRunReturnsResolve() {
        assertEquals(
            setOf("request-1"),
            resolvedApprovalRequestIds(
                listOf(request("request-1", "run-1", "call-1", "call-2"), returned("call-1", "run-1"), returned("call-2", "run-1")),
            ),
        )
    }

    private fun request(id: String, runId: String?, vararg callIds: String) = ApprovalRequestMessage(
        id = id,
        runId = runId,
        toolCalls = callIds.map { ToolCall(toolCallId = it, name = "tool-$it", arguments = "{}") },
    )

    private fun response(requestId: String, runId: String?) = ApprovalResponseMessage(
        id = "response-$requestId",
        approvalRequestId = requestId,
        approve = false,
        runId = runId,
    )

    private fun returned(callId: String, runId: String?) = ToolReturnMessage(
        id = "return-$callId-$runId",
        toolCallId = callId,
        status = "success",
        runId = runId,
    )
}
