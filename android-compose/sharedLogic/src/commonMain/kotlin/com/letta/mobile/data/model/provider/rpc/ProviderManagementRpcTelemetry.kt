package com.letta.mobile.data.model.provider.rpc

import com.letta.mobile.data.model.HostId

/**
 * Sanitized telemetry and audit record for provider management RPC events.
 *
 * Guaranteed to NEVER contain request bodies, URLs with embedded credentials,
 * or secret values.
 */
data class ProviderRpcTelemetryEvent(
    val method: String,
    val hostId: HostId,
    val peerId: String?,
    val outcome: String,
    val errorCode: String? = null,
) {
    override fun toString(): String =
        "ProviderRpcTelemetryEvent(method=$method, hostId=${hostId.value}, peerId=$peerId, " +
            "outcome=$outcome, errorCode=$errorCode)"
}

object ProviderManagementRpcTelemetry {

    fun createEvent(
        method: String,
        hostId: HostId,
        peerId: String?,
        outcome: String,
        errorCode: String? = null,
    ): ProviderRpcTelemetryEvent = ProviderRpcTelemetryEvent(
        method = method,
        hostId = hostId,
        peerId = peerId,
        outcome = outcome,
        errorCode = errorCode,
    )
}
