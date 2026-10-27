package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun recordControlFrame(remoteEndpointId: String, frame: JsonObject) {
    Telemetry.event(
        "IrohNode", "control.frame",
        "remoteEndpointId" attr remoteEndpointId,
        "type" attr frame["type"]?.jsonPrimitive?.content,
        "requestId" attr frame["request_id"]?.jsonPrimitive?.content,
        "agentId" attr frame["agent_id"]?.jsonPrimitive?.content,
    )
}

internal fun recordAuthenticationRequired(remoteEndpointId: String, requestId: String) {
    Telemetry.event(
        "IrohNode", "auth.required",
        "remoteEndpointId" attr remoteEndpointId,
        "requestId" attr requestId,
        "reason" attr "missing_token",
    )
}
