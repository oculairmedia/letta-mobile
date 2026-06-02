package com.letta.mobile.feature.chat

import com.letta.mobile.data.a2ui.A2uiAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.jupiter.api.Tag
import com.letta.mobile.feature.chat.a2ui.A2uiToolApprovalSubmission

@Tag("unit")
class A2uiToolApprovalSubmissionTest {
    @Test
    fun `maps A2UI approval action to REST approval submission`() {
        val submission = action(
            approvalRequestId = "approval-1",
            callId = "call-1",
            decision = "approve",
            scope = "once",
        ).toToolApprovalSubmission()

        assertEquals(
            A2uiToolApprovalSubmission(
                approvalRequestId = "approval-1",
                callId = "call-1",
                approve = true,
                scope = "once",
                reason = null,
            ),
            submission,
        )
    }

    @Test
    fun `maps A2UI deny action to REST rejection submission`() {
        val submission = action(
            approvalRequestId = "approval-1",
            callId = "call-1",
            decision = "deny",
            scope = "deny",
            reason = "unsafe",
        ).toToolApprovalSubmission()

        assertEquals(
            A2uiToolApprovalSubmission(
                approvalRequestId = "approval-1",
                callId = "call-1",
                approve = false,
                scope = "deny",
                reason = "unsafe",
            ),
            submission,
        )
    }

    @Test
    fun `returns null without approval request id so legacy shim falls back to WS`() {
        val submission = action(
            approvalRequestId = null,
            callId = "call-1",
            decision = "approve",
            scope = "once",
        ).toToolApprovalSubmission()

        assertNull(submission)
    }

    private fun action(
        approvalRequestId: String?,
        callId: String,
        decision: String,
        scope: String,
        reason: String? = null,
    ): A2uiAction {
        val context = buildJsonObject {
            approvalRequestId?.let { put("approvalRequestId", it) }
            put("callId", callId)
            put("decision", decision)
            put("scope", scope)
            reason?.let { put("reason", it) }
        }
        return A2uiAction(
            name = "tool_approval_response",
            surfaceId = "surface-1",
            context = context,
            conversationId = "conv-1",
            runId = "run-1",
            turnId = "turn-1",
            actionId = callId,
            raw = buildJsonObject {
                put("name", "tool_approval_response")
                put("context", context)
            },
        )
    }
}
