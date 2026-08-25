package com.letta.mobile.data.model.provider.rpc

import com.letta.mobile.data.model.HostId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProviderManagementRpcGateTest {

    private val activeHost = HostId("host-primary")
    private val otherHost = HostId("host-foreign")

    @Test
    fun unauthenticatedPeerIsDenied() {
        val unauthContext = ProviderRpcAuthContext(
            activeHostId = activeHost,
            peerId = null,
            grantedCapabilities = setOf(ProviderManagementCapability.Read),
        )

        val decision = ProviderManagementRpcGate.evaluate(
            method = ProviderRpcMethods.PROVIDER_DEFINITION_LIST,
            targetHostId = activeHost,
            auth = unauthContext,
        )

        assertIs<RpcGateDecision.Denied>(decision)
        assertEquals(RpcDenialReason.Unauthenticated, decision.reason)
    }

    @Test
    fun targetHostMismatchIsDeniedBeforeCapabilityCheck() {
        val authContext = ProviderRpcAuthContext(
            activeHostId = activeHost,
            peerId = "peer-123",
            grantedCapabilities = setOf(ProviderManagementCapability.Read, ProviderManagementCapability.Write),
        )

        val decision = ProviderManagementRpcGate.evaluate(
            method = ProviderRpcMethods.PROVIDER_DEFINITION_LIST,
            targetHostId = otherHost,
            auth = authContext,
        )

        assertIs<RpcGateDecision.Denied>(decision)
        assertIs<RpcDenialReason.HostMismatch>(decision.reason)
        val mismatch = decision.reason as RpcDenialReason.HostMismatch
        assertEquals(activeHost, mismatch.expectedHostId)
        assertEquals(otherHost, mismatch.actualHostId)
    }

    @Test
    fun readOnlyPeerCanAccessReadMethodsButDeniedWriteMethods() {
        val readOnlyContext = ProviderRpcAuthContext(
            activeHostId = activeHost,
            peerId = "peer-reader",
            grantedCapabilities = setOf(ProviderManagementCapability.Read),
        )

        // Read method is evaluated through gate -> returns Unimplemented (capability exists)
        val readDecision = ProviderManagementRpcGate.evaluate(
            method = ProviderRpcMethods.PROVIDER_INSTANCE_LIST,
            targetHostId = activeHost,
            auth = readOnlyContext,
        )
        assertIs<RpcGateDecision.Unimplemented>(readDecision)
        assertEquals(ProviderRpcMethods.PROVIDER_INSTANCE_LIST, readDecision.method)
        assertEquals("CAPABILITY_UNAVAILABLE", readDecision.errorCode)

        // Write method is denied at gate -> MissingCapability(Write)
        val writeDecision = ProviderManagementRpcGate.evaluate(
            method = ProviderRpcMethods.PROVIDER_INSTANCE_CREATE,
            targetHostId = activeHost,
            auth = readOnlyContext,
        )
        assertIs<RpcGateDecision.Denied>(writeDecision)
        assertIs<RpcDenialReason.MissingCapability>(writeDecision.reason)
        assertEquals(ProviderManagementCapability.Write, (writeDecision.reason as RpcDenialReason.MissingCapability).required)
    }

    @Test
    fun unknownCapabilityFailsClosed() {
        val unknownCapContext = ProviderRpcAuthContext(
            activeHostId = activeHost,
            peerId = "peer-custom",
            grantedCapabilities = setOf(ProviderManagementCapability.Unknown("provider_management:custom_probe")),
        )

        val readDecision = ProviderManagementRpcGate.evaluate(
            method = ProviderRpcMethods.PROVIDER_INSTANCE_LIST,
            targetHostId = activeHost,
            auth = unknownCapContext,
        )
        assertIs<RpcGateDecision.Denied>(readDecision)

        val writeDecision = ProviderManagementRpcGate.evaluate(
            method = ProviderRpcMethods.PROVIDER_INSTANCE_CREATE,
            targetHostId = activeHost,
            auth = unknownCapContext,
        )
        assertIs<RpcGateDecision.Denied>(writeDecision)
    }

    @Test
    fun unknownMethodIsDeniedAtGate() {
        val fullAuth = ProviderRpcAuthContext(
            activeHostId = activeHost,
            peerId = "peer-admin",
            grantedCapabilities = setOf(ProviderManagementCapability.Read, ProviderManagementCapability.Write),
        )

        val decision = ProviderManagementRpcGate.evaluate(
            method = "provider.unknown_action",
            targetHostId = activeHost,
            auth = fullAuth,
        )

        assertIs<RpcGateDecision.Denied>(decision)
        assertIs<RpcDenialReason.UnknownMethod>(decision.reason)
    }
}
