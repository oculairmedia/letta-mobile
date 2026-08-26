package com.letta.mobile.data.model.provider.rpc

import com.letta.mobile.data.model.HostId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderManagementRpcTelemetryTest {

    private val secretSentinel = "sk-live-sentinel-xyz-987654321-DO-NOT-LEAK"

    @Test
    fun telemetryEventsNeverContainSecretValuesOrBodies() {
        val auth = ProviderRpcAuthContext(
            activeHostId = HostId("host-1"),
            peerId = "peer-1",
        )
        val event = ProviderManagementRpcTelemetry.createEvent(
            auth = auth,
            method = ProviderRpcMethods.PROVIDER_CREDENTIAL_REPLACE,
            outcome = "denied",
            errorCode = "UNAUTHORIZED",
        )

        val str = event.toString()
        assertFalse(str.contains(secretSentinel))
        assertFalse(str.contains("apiKey", ignoreCase = true))
        assertFalse(str.contains("body", ignoreCase = true))
        assertFalse(str.contains("peer-1"))
        assertTrue(str.contains("provider.credential.replace"))
        assertTrue(str.contains("UNAUTHORIZED"))
    }

    @Test
    fun untrustedTelemetryLabelsAreCanonicalizedBeforeLogging() {
        val event = ProviderManagementRpcTelemetry.createEvent(
            auth = ProviderRpcAuthContext(HostId("host-1"), peerId = secretSentinel),
            method = secretSentinel,
            outcome = secretSentinel,
            errorCode = secretSentinel,
        )

        val rendered = event.toString()
        assertFalse(rendered.contains(secretSentinel))
        assertTrue(rendered.contains("method=<unknown>"))
        assertTrue(rendered.contains("errorCode=UNKNOWN"))
    }
}
