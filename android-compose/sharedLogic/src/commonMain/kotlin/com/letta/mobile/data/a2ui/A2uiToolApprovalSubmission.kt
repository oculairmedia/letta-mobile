package com.letta.mobile.data.a2ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The A2UI action name a ToolApprovalCard answers with. */
const val A2UI_TOOL_APPROVAL_RESPONSE_ACTION = "tool_approval_response"

/**
 * An approval answer parsed from an A2UI `tool_approval_response` action. Parsing alone grants
 * nothing: every field is server-influenced, so only [PendingToolApprovalRegistry.admit] may turn
 * one into a submitted approval (letta-mobile-by4vm.2).
 */
data class A2uiToolApprovalSubmission(
    val ref: ToolApprovalRef,
    val approve: Boolean,
    /** The card's scope (`once`, `session`, `forever`, `deny`, `timeout`). */
    val scope: String?,
    val reason: String?,
)

val A2uiAction.isToolApprovalResponse: Boolean get() = name == A2UI_TOOL_APPROVAL_RESPONSE_ACTION

/** The approval this action asks for, or null when it is not a well-formed approval answer. */
fun A2uiAction.toToolApprovalSubmission(): A2uiToolApprovalSubmission? {
    if (!isToolApprovalResponse) return null

    val ref = ToolApprovalRef.of(
        approvalRequestId = context.stringValue("approvalRequestId", "approval_request_id")
            ?: raw.stringValue("approvalRequestId", "approval_request_id"),
        callId = context.stringValue("callId", "toolCallId", "tool_call_id") ?: actionId,
    ) ?: return null
    val approve = when (context.stringValue("decision")?.lowercase()) {
        "approve", "approved", "allow", "allowed", "accept", "accepted" -> true
        "deny", "denied", "reject", "rejected", "timeout" -> false
        else -> return null
    }

    return A2uiToolApprovalSubmission(
        ref = ref,
        approve = approve,
        scope = context.stringValue("scope"),
        reason = context.stringValue("reason"),
    )
}

// A non-primitive value (a forged object or array) reads as absent rather than throwing.
private fun JsonObject.stringValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
