@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.servicecredentials.ServiceCredentialList
import com.letta.mobile.data.servicecredentials.ServiceCredentialStatus
import com.letta.mobile.data.servicecredentials.ServiceCredentialWire
import com.letta.mobile.data.servicecredentials.ServiceId
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.ui.modelcontrol.ServiceCredentialsBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Services page appears when the host connection becomes ready, without leaving the Providers page. */
class ServiceCredentialsBindingTest {
    private val connected = ChannelTransportState.Connected("iroh-app-server", "session", "device")

    /** A host that advertises the contract only while connected, and answers `service.credential.list` only then, as the real one does. */
    private class HostTransport : NoOpChannelTransport() {
        val connection = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Idle)
        override val state: StateFlow<ChannelTransportState> get() = connection
        override val hostCapabilities: Set<String>
            get() = if (connection.value is ChannelTransportState.Connected) setOf(ServiceCredentialWire.CAPABILITY) else emptySet()

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            check(connection.value is ChannelTransportState.Connected) { "not connected" }
            val jev = ServiceCredentialStatus(ServiceId("typesafe-jev"), "TypeSafe Jev")
            val list = ServiceCredentialList(canManage = true, services = listOf(jev))
            return AppServerInboundFrame.AdminRpcResponse(
                "request", true, LettaJson.wire.encodeToJsonElement(ServiceCredentialList.serializer(), list),
            )
        }
    }

    @Test
    fun servicesAppearOnceTheHostConnectionIsReadyNotOnlyWhenThePageIsEntered() = runComposeUiTest {
        val transport = HostTransport()
        var binding by mutableStateOf<ServiceCredentialsBinding?>(null)
        setContent { binding = rememberServiceCredentials(transport) }
        waitForIdle()
        assertNull(binding, "The host is not ready: no Services page yet")

        transport.connection.value = connected
        waitUntil(timeoutMillis = 5_000) { binding != null }
        assertEquals("TypeSafe Jev", assertNotNull(binding).state.services.single().displayName)
    }
}
