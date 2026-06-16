package com.letta.mobile.data.channel

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.RuntimeId
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelDisplayMapperTest {
    @Test
    fun connectedStateIncludesTransportAndA2uiMetadata() {
        val state = ChannelDisplayMapper.build(
            backendDescriptor = backend(),
            channelTransportState = ChannelTransportState.Connected(
                serverId = "server-1",
                sessionId = "session-1",
                deviceId = "device-1",
                a2uiEnabled = true,
                a2uiVersion = "1.0",
                a2uiCatalog = "desktop",
                canonicalLiveTransport = "websocket",
            ),
        )

        val channel = state.channels.single()
        assertEquals("backend-1", channel.id)
        assertEquals("https://api.letta.com", channel.title)
        assertEquals("Connected via websocket", channel.subtitle)
        assertEquals(ChannelDisplayStatus.Connected, channel.status)
        assertEquals(
            listOf("Connected", "websocket", "A2UI", "A2UI 1.0", "Catalog desktop", "Device device-1"),
            channel.metadataLabels,
        )
        assertEquals("1 channel", state.summaryLabel)
    }

    @Test
    fun disconnectedStateIncludesCodeAndAuthMetadata() {
        val state = ChannelDisplayMapper.build(
            backendDescriptor = backend(),
            channelTransportState = ChannelTransportState.Disconnected(
                code = 4401,
                reason = "Unauthorized",
                isAuthFailure = true,
            ),
        )

        val channel = state.channels.single()
        assertEquals("Unauthorized", channel.subtitle)
        assertEquals(ChannelDisplayStatus.Disconnected, channel.status)
        assertEquals(listOf("Disconnected", "Code 4401", "Auth"), channel.metadataLabels)
    }

    private fun backend() = BackendDescriptor(
        backendId = BackendId("backend-1"),
        runtimeId = RuntimeId("runtime-1"),
        kind = BackendKind.RemoteLetta,
        label = "https://api.letta.com",
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
                supportsToolEvents = true,
                supportsToolExecution = true,
            supportsApprovals = true,
            supportsAgentFileImport = true,
            supportsAgentFileExport = true,
        ),
    )
}
