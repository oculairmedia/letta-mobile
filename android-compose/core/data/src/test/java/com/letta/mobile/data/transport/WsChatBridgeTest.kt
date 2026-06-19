package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.testutil.FakeChannelTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WsChatBridgeTest {
    @Test
    fun `connection maps transport states without exposing ChannelTransport to consumers`() = runTest {
        val transport = FakeChannelTransport(initialState = ChannelTransportState.Idle)
        val bridge = WsChatBridge(transport)

        assertEquals(WsConnectionState.Idle, bridge.connection.first())
        assertFalse(bridge.isConnected())

        transport.state.value = ChannelTransportState.Connecting()

        assertEquals(WsConnectionState.Connecting, bridge.connection.first())
        assertFalse(bridge.isConnected())

        transport.state.value = ChannelTransportState.Connected(
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

        transport.state.value = ChannelTransportState.Disconnected(
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
    fun `message delta carries originating frame scope`() = runTest {
        val transport = FakeChannelTransport()
        val bridge = WsChatBridge(transport)
        val event = async { bridge.events.first { it is WsTimelineEvent.MessageDelta } }

        transport.events.emit(
            ServerFrame.AssistantMessage(
                id = "assistant-1",
                agentId = "agent-a",
                conversationId = "conv-a",
                turnId = "turn-a",
                runId = "run-a",
                content = "hello",
            )
        )

        val delta = event.await() as WsTimelineEvent.MessageDelta
        assertEquals("agent-a", delta.agentId)
        assertEquals("conv-a", delta.conversationId)
    }

    @Test
    fun `message deltas preserve two default conversations by agent scope`() = runTest {
        val transport = FakeChannelTransport()
        val bridge = WsChatBridge(transport)
        val first = async { bridge.events.first { it is WsTimelineEvent.MessageDelta } }

        transport.events.emit(
            ServerFrame.AssistantMessage(
                id = "assistant-a",
                agentId = "agent-a",
                conversationId = "default",
                turnId = "turn-a",
                runId = "run-a",
                content = "agent a",
            )
        )
        val deltaA = first.await() as WsTimelineEvent.MessageDelta
        val second = async { bridge.events.first { it is WsTimelineEvent.MessageDelta } }
        transport.events.emit(
            ServerFrame.AssistantMessage(
                id = "assistant-b",
                agentId = "agent-b",
                conversationId = "default",
                turnId = "turn-b",
                runId = "run-b",
                content = "agent b",
            )
        )
        val deltaB = second.await() as WsTimelineEvent.MessageDelta

        assertEquals("agent-a", deltaA.agentId)
        assertEquals("default", deltaA.conversationId)
        assertEquals("agent-b", deltaB.agentId)
        assertEquals("default", deltaB.conversationId)
    }

    @Test
    fun `awaitConnected resumes with semantic connected state`() = runTest {
        val transport = FakeChannelTransport(initialState = ChannelTransportState.Connecting())
        val bridge = WsChatBridge(transport)

        val connected = async { bridge.awaitConnected() }
        transport.state.value = ChannelTransportState.Connected(
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

    @Test
    fun `message deltas preserve replay metadata from transport frame events`() = runTest {
        val transport = FakeChannelTransport()
        val bridge = WsChatBridge(transport)
        val received = async { bridge.events.first { it is WsTimelineEvent.MessageDelta } as WsTimelineEvent.MessageDelta }
        runCurrent()

        transport.frameEvents.emit(
            TransportFrameEvent(
                frame = ServerFrame.AssistantMessage(
                    id = "cm-stream-old",
                    ts = "t",
                    agentId = "agent-1",
                    conversationId = "conv-1",
                    turnId = "turn-1",
                    runId = "run-1",
                    content = "old answer",
                ),
                isReplay = true,
            )
        )

        val delta = received.await()
        assertTrue(delta.isReplay)
        val message = delta.message as AssistantMessage
        assertEquals("cm-stream-old", message.id)
        assertEquals("old answer", message.content)
    }

}
