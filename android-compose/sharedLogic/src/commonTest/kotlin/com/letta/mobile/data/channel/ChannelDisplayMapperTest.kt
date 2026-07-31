package com.letta.mobile.data.channel

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.RuntimeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        // wxy4s: every non-connected surface carries the staleness chip, so cached
        // data is never presented as if it were live.
        assertEquals(listOf("Disconnected", "Code 4401", "Auth", "Stale"), channel.metadataLabels)
    }

    // letta-mobile-wxy4s: a supervisor-driven redial must read as "reconnecting"
    // (recovery in progress) rather than a hard failure, and must be flagged stale
    // so no surface silently renders cached data as if it were live.
    @Test
    fun redialingConnectionReadsAsReconnectingAndStale() {
        val state = ChannelDisplayMapper.build(
            backendDescriptor = backend(),
            channelTransportState = ChannelTransportState.Disconnected(
                code = 0,
                reason = "liveness_probe_failed",
                willReconnect = true,
            ),
        )

        val channel = state.channels.single()
        assertEquals(ChannelDisplayStatus.Reconnecting, channel.status)
        assertTrue(channel.status.isStale)
        assertTrue(channel.subtitle.startsWith("Reconnecting"))
        assertTrue(channel.metadataLabels.contains("Stale"))
        assertTrue(channel.detailText.contains("stale"))
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
