package com.letta.mobile.data.transport

import com.letta.mobile.testutil.FakeChannelTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WsChatBridgeTest {
    @Test
    fun `connection maps transport states without exposing ChannelTransport to consumers`() = runTest {
        val transport = FakeChannelTransport(initialState = ChannelTransport.State.Idle)
        val bridge = WsChatBridge(transport)

        assertEquals(WsConnectionState.Idle, bridge.connection.first())
        assertFalse(bridge.isConnected())

        transport.state.value = ChannelTransport.State.Connecting

        assertEquals(WsConnectionState.Connecting, bridge.connection.first())
        assertFalse(bridge.isConnected())

        transport.state.value = ChannelTransport.State.Connected(
            serverId = "server",
            sessionId = "session",
            deviceId = "device",
            a2uiEnabled = true,
            a2uiCatalog = "catalog-v1",
        )

        assertEquals(
            WsConnectionState.Connected(a2uiEnabled = true, catalog = "catalog-v1"),
            bridge.connection.first(),
        )
        assertTrue(bridge.isConnected())

        transport.state.value = ChannelTransport.State.Disconnected(
            code = 1008,
            reason = "unauthorized",
            isAuthFailure = true,
        )

        assertEquals(
            WsConnectionState.Disconnected(code = 1008, reason = "unauthorized", isAuthFailure = true),
            bridge.connection.first(),
        )
        assertFalse(bridge.isConnected())
    }

    @Test
    fun `awaitConnected resumes with semantic connected state`() = runTest {
        val transport = FakeChannelTransport(initialState = ChannelTransport.State.Connecting)
        val bridge = WsChatBridge(transport)

        val connected = async { bridge.awaitConnected() }
        transport.state.value = ChannelTransport.State.Connected(
            serverId = "server",
            sessionId = "session",
            deviceId = "device",
            a2uiEnabled = false,
            a2uiCatalog = null,
        )

        assertEquals(
            WsConnectionState.Connected(a2uiEnabled = false, catalog = null),
            connected.await(),
        )
    }
}
