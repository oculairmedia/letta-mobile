package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import computer.iroh.Connection
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun recordConnectionClosed(remoteEndpointId: String, connection: Connection) {
    Telemetry.event(
        "IrohNode", "connection.closed",
        attrs = arrayOf(
            "remoteEndpointId" attr remoteEndpointId,
            "closeReason" attr IrohDiagnostics.closeReason(connection),
        ) + IrohDiagnostics.connectionStatsAttributes(runCatching { connection.stats() }.getOrNull()).toTypedArray(),
    )
}

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
