package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
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
        source = "control_request",
    )

private fun RuntimeEventPayload.RemoteStreamFrame.toApprovalAutoAllowRequest(): ApprovalAutoAllowRequest? {
    if (messageType != "approval_request_message") return null
    val delta = parseStreamDelta(body) ?: return null
    val toolCall = delta["tool_call"] as? JsonObject
    return ApprovalAutoAllowRequest(
        requestId = approvalRequestId(delta),
        toolCallId = toolCall?.string("tool_call_id") ?: delta.string("tool_call_id"),
        toolName = toolCall?.string("name") ?: delta.toolName(),
        source = "approval_request_message",
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
