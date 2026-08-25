package com.letta.mobile.data.model.provider.rpc

import com.letta.mobile.data.model.HostId

/** Reasons why an inbound RPC request was rejected before handler execution. */
sealed interface RpcDenialReason {
    data object Unauthenticated : RpcDenialReason
    data class HostMismatch(val expectedHostId: HostId, val actualHostId: HostId) : RpcDenialReason
    data class MissingCapability(val required: ProviderManagementCapability) : RpcDenialReason
    data object UnknownMethod : RpcDenialReason
    data class UnsupportedContractVersion(val requestedVersion: Int) : RpcDenialReason
    data class CapabilityUnavailable(val method: String) : RpcDenialReason
}

sealed interface RpcGateDecision {
    data class Denied(val reason: RpcDenialReason) : RpcGateDecision
    data class Allowed(val method: String, val contractVersion: Int) : RpcGateDecision
}

/** Pure authorization, version, capability, and handler-availability gate. */
object ProviderManagementRpcGate {

    fun evaluate(
        request: ProviderRpcGateRequest,
        auth: ProviderRpcAuthContext,
        availableMethods: Set<String>,
    ): RpcGateDecision {
        if (!auth.isAuthenticated) {
            return RpcGateDecision.Denied(RpcDenialReason.Unauthenticated)
        }
        if (request.targetHostId != auth.activeHostId) {
            return RpcGateDecision.Denied(
                RpcDenialReason.HostMismatch(
                    expectedHostId = auth.activeHostId,
                    actualHostId = request.targetHostId,
                ),
            )
        }
        if (request.method !in ProviderRpcMethods.ALL_METHODS) {
            return RpcGateDecision.Denied(RpcDenialReason.UnknownMethod)
        }
        if (request.contractVersion != ProviderRpcMethods.CONTRACT_VERSION) {
            return RpcGateDecision.Denied(
                RpcDenialReason.UnsupportedContractVersion(request.contractVersion),
            )
        }

        val requiredCapability = if (ProviderRpcMethods.isWriteMethod(request.method)) {
            ProviderManagementCapability.Write
        } else {
            ProviderManagementCapability.Read
        }
        if (!auth.hasCapability(requiredCapability)) {
            return RpcGateDecision.Denied(RpcDenialReason.MissingCapability(requiredCapability))
        }
        if (request.method !in availableMethods) {
            return RpcGateDecision.Denied(RpcDenialReason.CapabilityUnavailable(request.method))
        }
        return RpcGateDecision.Allowed(request.method, request.contractVersion)
    }
}

/** Secret-free gate metadata parsed before any request body is decoded or handled. */
data class ProviderRpcGateRequest(
    val method: String,
    val contractVersion: Int,
    val targetHostId: HostId,
)
