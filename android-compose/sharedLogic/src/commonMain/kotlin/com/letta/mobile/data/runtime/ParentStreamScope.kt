package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Admission boundary shared by turn projection, late-tail relay and durable feed writes. */
internal fun admitsParentStreamDelta(delta: JsonObject, scope: AppServerRuntimeScope): Boolean {
    val foreignField = listOf("agent_id", "session_id", "conversation_id").firstOrNull { field ->
        val identity = (delta[field] as? JsonPrimitive)?.contentOrNull
        val expected = if (field == "conversation_id") scope.conversationId else scope.agentId
        identity != null && identity != expected
    } ?: return true
    Telemetry.event(
        "ParentStreamScope", "stream_delta.foreign_identity_suppressed",
        "identityField" attr foreignField,
        "conversationId" attr scope.conversationId,
    )
    return false
}
