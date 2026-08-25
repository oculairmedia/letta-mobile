package com.letta.mobile.data.model.provider.rpc

import com.letta.mobile.data.model.HostId

/**
 * Reasons why an RPC request was rejected at the authorization/capability gate.
 */
sealed interface RpcDenialReason {
    data object Unauthenticated : RpcDenialReason
    data class HostMismatch(val expectedHostId: HostId, val actualHostId: HostId) : RpcDenialReason
    data class MissingCapability(val required: ProviderManagementCapability) : RpcDenialReason
    data class UnknownMethod(val method: String) : RpcDenialReason
}

/**
 * Result of evaluating an RPC request at the gate.
 */
sealed interface RpcGateDecision {
    data class Denied(val reason: RpcDenialReason) : RpcGateDecision

    /**
     * Authorized peer and scope, but host has not yet mounted a concrete storage/execution handler.
     * Guaranteed to return typed capability-unavailable rather than fabricating empty/success.
     */
    data class Unimplemented(
        val method: String,
        val errorCode: String = "CAPABILITY_UNAVAILABLE",
        val message: String = "Method '$method' is declared in provider_management_v1 but unavailable on this host",
    ) : RpcGateDecision
}

/**
 * Pure authorization and scope gate for the provider_management_v1 RPC protocol.
 */
object ProviderManagementRpcGate {

    fun evaluate(
        method: String,
        targetHostId: HostId,
        auth: ProviderRpcAuthContext,
    ): RpcGateDecision {
        if (!auth.isAuthenticated) {
            return RpcGateDecision.Denied(RpcDenialReason.Unauthenticated)
        }

        if (targetHostId != auth.activeHostId) {
            return RpcGateDecision.Denied(
                RpcDenialReason.HostMismatch(
                    expectedHostId = auth.activeHostId,
                    actualHostId = targetHostId,
                ),
            )
        }

        if (!ProviderRpcMethods.ALL_METHODS.contains(method)) {
            return RpcGateDecision.Denied(RpcDenialReason.UnknownMethod(method))
        }

        val requiredCapability = if (ProviderRpcMethods.isWriteMethod(method)) {
            ProviderManagementCapability.Write
        } else {
            ProviderManagementCapability.Read
        }

        if (!auth.hasCapability(requiredCapability)) {
            return RpcGateDecision.Denied(RpcDenialReason.MissingCapability(requiredCapability))
        }

        return RpcGateDecision.Unimplemented(method = method)
    }
}
