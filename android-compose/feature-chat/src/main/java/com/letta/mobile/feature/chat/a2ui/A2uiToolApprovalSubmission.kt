package com.letta.mobile.feature.chat.a2ui

import com.letta.mobile.data.a2ui.A2uiAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class A2uiToolApprovalSubmission(
    val approvalRequestId: String,
    val callId: String,
    val approve: Boolean,
    val scope: String?,
    val reason: String?,
)

internal fun A2uiAction.toToolApprovalSubmission(): A2uiToolApprovalSubmission? {
    if (name != A2UI_TOOL_APPROVAL_RESPONSE_ACTION) return null

    val approvalRequestId = context.stringValue("approvalRequestId", "approval_request_id")
        ?: raw.stringValue("approvalRequestId", "approval_request_id")
        ?: return null
    val callId = context.stringValue("callId", "toolCallId", "tool_call_id")
        ?: actionId
        ?: return null
    val decision = context.stringValue("decision")?.lowercase() ?: return null
    val approve = when (decision) {
        "approve",
        "approved",
        "allow",
        "allowed",
        "accept",
        "accepted" -> true
        "deny",
        "denied",
        "reject",
        "rejected",
        "timeout" -> false
        else -> return null
    }

    return A2uiToolApprovalSubmission(
        approvalRequestId = approvalRequestId,
        callId = callId,
        approve = approve,
        scope = context.stringValue("scope"),
        reason = context.stringValue("reason")?.takeIf { it.isNotBlank() },
    )
}

private fun JsonObject.stringValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

private const val A2UI_TOOL_APPROVAL_RESPONSE_ACTION = "tool_approval_response"
