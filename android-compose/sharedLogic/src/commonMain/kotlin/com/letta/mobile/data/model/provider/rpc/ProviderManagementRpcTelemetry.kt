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
    val outcome: String,
    val peerId: String? = null,
    val errorCode: String? = null,
) {
    override fun toString(): String =
        "ProviderRpcTelemetryEvent(method=$method, hostId=${hostId.value}, outcome=$outcome, " +
            "peerId=$peerId, errorCode=$errorCode)"
}

object ProviderManagementRpcTelemetry {

    fun createEvent(
        auth: ProviderRpcAuthContext,
        method: String,
        outcome: String,
        errorCode: String? = null,
    ): ProviderRpcTelemetryEvent = ProviderRpcTelemetryEvent(
        method = method,
        hostId = auth.activeHostId,
        peerId = auth.peerId,
        outcome = outcome,
        errorCode = errorCode,
    )
}
