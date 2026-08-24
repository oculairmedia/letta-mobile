package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class DualLaneAppServerClientTest {
    @Test
    fun `runtime ownership and management requests stay on separate lanes`() = runTest {
        val runtime = RecordingClient("runtime")
        val admin = RecordingClient("admin")
        val client = DualLaneAppServerClient(runtime = runtime, admin = admin)

        client.runtimeStart(
            AppServerCommand.RuntimeStart(
                requestId = "runtime-start",
                agentId = "agent-1",
                conversationId = "conv-1",
            ),
        )
        client.input(
            AppServerCommand.Input(
                runtime = AppServerRuntimeScope("agent-1", "conv-1"),
                payload = AppServerInputPayload.CreateMessage(listOf(AppServerInputMessage.userText("hello"))),
            ),
        )
        client.adminRpc(AppServerCommand.AdminRpc("admin-rpc", "conversation.cwd.set"))
        client.channelStart(AppServerCommand.ChannelStart("channel-start", "channel-1"))

        client.agentList(AppServerCommand.AgentList("agent-list"))
        client.conversationMessagesList(
            AppServerCommand.ConversationMessagesList("message-list", "conv-1"),
        )

        assertEquals(listOf("runtimeStart", "input", "adminRpc", "channelStart"), runtime.calls)
        assertEquals(listOf("agentList", "conversationMessagesList"), admin.calls)
        assertSame(runtime.events, client.events)
        assertSame(runtime.isConnected, client.isConnected)
    }

    @Test
    fun `admin failure does not poison subsequent runtime commands`() = runTest {
        val runtime = RecordingClient("runtime")
        val admin = RecordingClient("admin", failAgentList = true)
        val client = DualLaneAppServerClient(runtime = runtime, admin = admin)

        assertFailsWith<IllegalStateException> {
            client.agentList(AppServerCommand.AgentList("agent-list"))
        }
        client.input(
            AppServerCommand.Input(
                runtime = AppServerRuntimeScope("agent-1", "conv-1"),
                payload = AppServerInputPayload.CreateMessage(listOf(AppServerInputMessage.userText("still live"))),
            ),
        )

        assertEquals(listOf("input"), runtime.calls)
    }

    private class RecordingClient(
        private val lane: String,
        private val failAgentList: Boolean = false,
    ) : AppServerClient {
        val calls = mutableListOf<String>()
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override val isConnected: Flow<Boolean> = flowOf(true)

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
            calls += "runtimeStart"
            return AppServerInboundFrame.RuntimeStartResponse(command.requestId, success = true)
        }

        override suspend fun input(command: AppServerCommand.Input) {
            calls += "input"
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused on $lane")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused on $lane")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
            calls += "adminRpc"
            return AppServerInboundFrame.AdminRpcResponse(command.requestId, success = true)
        }

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
            error("external tool response unused on $lane")
        }

        override suspend fun agentList(command: AppServerCommand.AgentList): AppServerInboundFrame.AgentListResponse {
            calls += "agentList"
            if (failAgentList) error("admin lane failed")
            return AppServerInboundFrame.AgentListResponse(command.requestId, success = true)
        }

        override suspend fun conversationMessagesList(
            command: AppServerCommand.ConversationMessagesList,
        ): AppServerInboundFrame.ConversationMessagesListResponse {
            calls += "conversationMessagesList"
            return AppServerInboundFrame.ConversationMessagesListResponse(command.requestId, success = true)
        }

        override suspend fun channelStart(command: AppServerCommand.ChannelStart): AppServerInboundFrame.ChannelStartResponse {
            calls += "channelStart"
            return AppServerInboundFrame.ChannelStartResponse(command.requestId, success = true)
        }
    }
}
