package com.letta.mobile.data.model.provider.rpc

import com.letta.mobile.data.model.HostId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProviderManagementRpcGateTest {

    private val activeHost = HostId("host-primary")
    private val otherHost = HostId("host-foreign")
    private val readMethod = ProviderRpcMethods.PROVIDER_INSTANCE_LIST

    @Test
    fun authenticationAndHostScopeFailClosed() {
        val request = gateRequest()
        val unauthenticated = auth(peerId = null, ProviderManagementCapability.Read)
        assertEquals(
            RpcDenialReason.Unauthenticated,
            assertIs<RpcGateDecision.Denied>(evaluate(request, unauthenticated)).reason,
        )

        val mismatch = assertIs<RpcGateDecision.Denied>(
            evaluate(request.copy(targetHostId = otherHost), auth("peer", ProviderManagementCapability.Read)),
        )
        assertIs<RpcDenialReason.HostMismatch>(mismatch.reason)
    }

    @Test
    fun exactVersionAndMountedHandlerAreRequired() {
        val authorized = auth("peer", ProviderManagementCapability.Read)
        val incompatible = assertIs<RpcGateDecision.Denied>(
            evaluate(gateRequest(version = 2), authorized),
        )
        assertEquals(RpcDenialReason.UnsupportedContractVersion(2), incompatible.reason)

        val unavailable = assertIs<RpcGateDecision.Denied>(
            ProviderManagementRpcGate.evaluate(gateRequest(), authorized, emptySet()),
        )
        assertEquals(RpcDenialReason.CapabilityUnavailable(readMethod), unavailable.reason)

        val allowed = assertIs<RpcGateDecision.Allowed>(evaluate(gateRequest(), authorized))
        assertEquals(ProviderRpcMethods.CONTRACT_VERSION, allowed.contractVersion)
    }

    @Test
    fun readAndWriteCapabilitiesAreIndependentAndUnknownFailsClosed() {
        val readOnly = auth("reader", ProviderManagementCapability.Read)
        assertIs<RpcGateDecision.Allowed>(evaluate(gateRequest(), readOnly))

        val deniedWrite = assertIs<RpcGateDecision.Denied>(
            evaluate(gateRequest(ProviderRpcMethods.PROVIDER_INSTANCE_CREATE), readOnly),
        )
        assertEquals(
            RpcDenialReason.MissingCapability(ProviderManagementCapability.Write),
            deniedWrite.reason,
        )

        val unknownOnly = auth(
            "peer-custom",
            ProviderManagementCapability.Unknown("provider_management:custom_probe"),
        )
        assertIs<RpcDenialReason.MissingCapability>(
            assertIs<RpcGateDecision.Denied>(evaluate(gateRequest(), unknownOnly)).reason,
        )
    }

    @Test
    fun unknownMethodIsDeniedBeforeAvailability() {
        val decision = ProviderManagementRpcGate.evaluate(
            gateRequest("provider.unknown_action"),
            auth("admin", ProviderManagementCapability.Read, ProviderManagementCapability.Write),
            availableMethods = setOf("provider.unknown_action"),
        )
        assertEquals(RpcDenialReason.UnknownMethod, assertIs<RpcGateDecision.Denied>(decision).reason)
    }

    private fun gateRequest(
        method: String = readMethod,
        version: Int = ProviderRpcMethods.CONTRACT_VERSION,
    ) = ProviderRpcGateRequest(method, version, activeHost)

    private fun auth(
        peerId: String?,
        vararg capabilities: ProviderManagementCapability,
    ) = ProviderRpcAuthContext(activeHost, peerId, capabilities.toSet())

    private fun evaluate(
        request: ProviderRpcGateRequest,
        auth: ProviderRpcAuthContext,
    ) = ProviderManagementRpcGate.evaluate(request, auth, ProviderRpcMethods.ALL_METHODS)
}
