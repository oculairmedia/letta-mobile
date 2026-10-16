package com.letta.mobile.data.transport

import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ChannelTransportContractsCommonTest {

    @Test
    fun `no-op channel transport is commonMain instantiable`() = runTest {
        val transport = NoOpChannelTransport()

        assertEquals(ChannelTransportState.Idle, transport.state.value)
        assertFalse(
            transport.send(
                agentId = "agent-1",
                conversationId = "conversation-1",
                text = "hello",
            )
        )

        transport.connect(
            baseShimUrl = "http://localhost:8080",
            token = "token",
            deviceId = "desktop",
            clientVersion = "test",
        )

        assertEquals(
            ChannelTransportState.Disconnected(
                code = -1,
                reason = "No channel transport implementation is installed",
            ),
            transport.state.value,
        )
    }

    @Test
    fun `shared websocket bridge can wrap no-op transport`() = runTest {
        val bridge = WsChatBridge(NoOpChannelTransport())

        assertEquals(WsConnectionState.Idle, bridge.connection.first())
        assertFalse(bridge.isConnected())
        assertFalse(
            bridge.send(
                agentId = "agent-1",
                conversationId = "conversation-1",
                text = "hello",
            )
        )
    }
}
