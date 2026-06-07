package com.letta.mobile.data.transport

import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ChannelTransportContractsCommonTest {
    private val conversationId = "conversation-1"

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

    @Test
    fun `self-todo sentinel detection stays in shared bridge layer`() {
        assertTrue(selfTodoFrame().isSelfTodoChipFrame())
        assertFalse(realToolCallFrame().isSelfTodoChipFrame())
    }

    private fun selfTodoFrame(): ServerFrame.ToolCallMessage = ServerFrame.ToolCallMessage(
        id = "toolcall-selftodo-$conversationId",
        ts = "2026-06-05T00:00:00Z",
        agentId = "agent-1",
        conversationId = conversationId,
        turnId = "selftodo-turn-$conversationId",
        runId = "selftodo-run-$conversationId",
        toolCall = ToolCallPayload(
            toolCallId = "selftodo-$conversationId",
            name = "TodoWrite",
            arguments = """{"todos":[{"content":"do it","status":"in_progress","activeForm":"Doing it"}]}""",
        ),
    )

    private fun realToolCallFrame(): ServerFrame.ToolCallMessage = ServerFrame.ToolCallMessage(
        id = "toolcall-real-1",
        ts = "2026-06-05T00:00:01Z",
        agentId = "agent-1",
        conversationId = conversationId,
        turnId = "turn-real-1",
        runId = "run-real-1",
        toolCall = ToolCallPayload(
            toolCallId = "tc-real-1",
            name = "Bash",
            arguments = """{"command":"ls"}""",
        ),
    )
}
