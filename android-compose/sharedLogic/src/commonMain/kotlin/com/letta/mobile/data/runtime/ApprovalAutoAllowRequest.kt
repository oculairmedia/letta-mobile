package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.ApprovalSubmission
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The approval a projected draft asks for, in the shape the auto-allow policy needs. */
internal data class ApprovalAutoAllowRequest(
    val requestId: String,
    val toolCallId: String?,
    val toolName: String?,
    val source: String,
)

private const val SOURCE_CONTROL_REQUEST = "control_request"
private const val SOURCE_STREAM_DELTA = "approval_request_message"

/**
 * letta-mobile-qygvv.13: an `approval_request_message` delta under Unrestricted is
 * informational. letta-code (0.32.17, `handleApprovalStop`) classifies every approval
 * with `checkModeOverride` -> `unrestricted` => allow and executes it without waiting;
 * only approvals that still need a human (alwaysAsk rules, mod tools, interactive tools)
 * wait, and those arrive as a real `control_request can_use_tool`. The only resolver an
 * `approval_response` can hit is keyed by that control request's `perm-<toolCallId>` id,
 * so a reply to the delta is always acked "Approval request is no longer pending".
 */
internal fun ApprovalAutoAllowRequest.isInformationalUnderUnrestricted(): Boolean =
    source == SOURCE_STREAM_DELTA

internal fun ApprovalAutoAllowRequest.autoAllowSubmission(scope: AppServerRuntimeScope) = ApprovalSubmission(
    runtime = scope,
    approvalRequestId = requestId,
    decision = AppServerApprovalResponseDecision.Allow(message = "Approved by default mobile policy."),
    source = "auto_allow",
    answersStreamDelta = source == SOURCE_STREAM_DELTA,
    toolName = toolName,
)

internal fun recordAutoAllowSkippedUnrestricted(approval: ApprovalAutoAllowRequest) {
    Telemetry.event(
        "IrohTurn", "approval.auto_allow_skipped_unrestricted",
        "approvalId" to approval.requestId,
        "toolCallId" to (approval.toolCallId ?: ""),
        "tool" to (approval.toolName ?: ""),
        "source" to approval.source,
    )
}

internal fun RuntimeEventDraft.toApprovalAutoAllowRequest(): ApprovalAutoAllowRequest? =
    when (val payload = this.payload) {
        is RuntimeEventPayload.ApprovalRequested -> payload.toApprovalAutoAllowRequest()
        is RuntimeEventPayload.RemoteStreamFrame -> payload.toApprovalAutoAllowRequest()
        else -> null
    }

private fun RuntimeEventPayload.ApprovalRequested.toApprovalAutoAllowRequest() =
    ApprovalAutoAllowRequest(
        requestId = request.approvalId.value,
        toolCallId = request.callId.value,
        toolName = request.toolName.value,
        source = SOURCE_CONTROL_REQUEST,
    )

private fun RuntimeEventPayload.RemoteStreamFrame.toApprovalAutoAllowRequest(): ApprovalAutoAllowRequest? {
    if (messageType != "approval_request_message") return null
    val delta = parseStreamDelta(body) ?: return null
    val toolCall = delta["tool_call"] as? JsonObject
    return ApprovalAutoAllowRequest(
        requestId = approvalRequestId(delta),
        toolCallId = toolCall?.string("tool_call_id") ?: delta.string("tool_call_id"),
        toolName = toolCall?.string("name") ?: delta.toolName(),
        source = SOURCE_STREAM_DELTA,
    )
}

private fun RuntimeEventPayload.RemoteStreamFrame.approvalRequestId(delta: JsonObject): String =
    delta.string("approval_request_id") ?: delta.string("id") ?: messageId ?: frameId

private fun JsonObject.toolName(): String? = string("tool_name") ?: string("name")

/** A stream frame body's `delta` object, or the body itself when it carries no delta. */
private fun parseStreamDelta(body: String): JsonObject? = runCatching {
    val raw = AppServerProtocol.json.parseToJsonElement(body).jsonObject
    raw["delta"]?.jsonObject ?: raw
}.getOrNull()

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
