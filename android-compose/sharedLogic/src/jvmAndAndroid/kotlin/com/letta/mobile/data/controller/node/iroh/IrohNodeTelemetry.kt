package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import computer.iroh.Connection
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun recordConnectionClosed(remoteEndpoint: Pair<String, TelemetryValue>, connection: Connection) {
    Telemetry.event(
        "IrohNode", "connection.closed",
        attrs = arrayOf(
            remoteEndpoint,
            "closeReason" attr IrohDiagnostics.closeReason(connection),
        ) + IrohDiagnostics.connectionStatsAttributes(runCatching { connection.stats() }.getOrNull()).toTypedArray(),
    )
}

internal fun recordControlFrame(remoteEndpoint: Pair<String, TelemetryValue>, frame: JsonObject) {
    Telemetry.event(
        "IrohNode", "control.frame",
        remoteEndpoint,
        "type" attr frame["type"]?.jsonPrimitive?.content,
        "requestId" attr frame["request_id"]?.jsonPrimitive?.content,
        "agentId" attr frame["agent_id"]?.jsonPrimitive?.content,
    )
}

internal fun recordAuthenticationRequired(
    remoteEndpoint: Pair<String, TelemetryValue>,
    request: Pair<String, TelemetryValue>,
) {
    Telemetry.event(
        "IrohNode", "auth.required",
        remoteEndpoint,
        request,
        "reason" attr "missing_token",
    )
}
