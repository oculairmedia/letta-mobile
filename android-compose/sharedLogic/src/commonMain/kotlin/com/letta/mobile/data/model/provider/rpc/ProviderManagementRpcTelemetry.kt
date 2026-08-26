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
            "peerId=<redacted>, errorCode=$errorCode)"
}

object ProviderManagementRpcTelemetry {

    fun createEvent(
        auth: ProviderRpcAuthContext,
        method: String,
        outcome: String,
        errorCode: String? = null,
    ): ProviderRpcTelemetryEvent = ProviderRpcTelemetryEvent(
        method = method.takeIf(ProviderRpcMethods.ALL_METHODS::contains) ?: "<unknown>",
        hostId = auth.activeHostId,
        peerId = auth.peerId,
        outcome = outcome.takeIf(ALLOWED_OUTCOMES::contains) ?: "unknown",
        errorCode = errorCode?.takeIf(::isSafeErrorCode) ?: errorCode?.let { "UNKNOWN" },
    )

    private val ALLOWED_OUTCOMES = setOf("allowed", "denied", "unavailable", "failed", "succeeded")
    private val ALLOWED_ERROR_CODES = setOf(
        "UNAUTHORIZED",
        "HOST_MISMATCH",
        "UNKNOWN_METHOD",
        "UNSUPPORTED_CONTRACT_VERSION",
        "CAPABILITY_UNAVAILABLE",
        "CAPABILITY_DENIED",
        "VALIDATION_FAILED",
        "MALFORMED_RESPONSE",
        "TRANSPORT_FAILURE",
    )

    private fun isSafeErrorCode(value: String): Boolean = value in ALLOWED_ERROR_CODES
}
