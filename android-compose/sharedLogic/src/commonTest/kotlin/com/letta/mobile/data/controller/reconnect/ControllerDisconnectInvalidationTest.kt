package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.registry.InMemoryRuntimeRegistry
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ControllerDisconnectInvalidationTest {
    private class RecordingClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        val runtimeStarts = mutableListOf<AppServerCommand.RuntimeStart>()

        override suspend fun runtimeStart(
            command: AppServerCommand.RuntimeStart,
        ): AppServerInboundFrame.RuntimeStartResponse {
            runtimeStarts += command
            return AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )
        }

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            AppServerInboundFrame.SyncResponse(
                requestId = command.requestId ?: "sync",
                runtime = command.runtime,
                success = true,
            )

        override suspend fun abort(
            command: AppServerCommand.AbortMessage,
        ): AppServerInboundFrame.AbortMessageResponse =
            AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId ?: "abort",
                runtime = command.runtime,
                aborted = true,
                success = true,
            )

        override suspend fun adminRpc(
            command: AppServerCommand.AdminRpc,
        ): AppServerInboundFrame.AdminRpcResponse =
            AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = true)

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    @Test
    fun disconnectClearsCanonicalScopesSoTheNextStartReattaches() = runTest {
        val client = RecordingClient()
        val controller = DefaultAppServerController(client = client)

        controller.startRuntime(AgentId("agent-1"), ConversationId("conv-1"))
        controller.startRuntime(AgentId("agent-1"), ConversationId("conv-1"))
        assertEquals(1, client.runtimeStarts.size, "second start must hit the cache")

        controller.onTransportDisconnected("socket lost")
        assertIs<AppServerControllerState.Disconnected>(controller.state.first())

        // Canonical scopes are generation-local: after disconnect the next start
        // must re-issue runtime_start instead of serving the dead generation's scope.
        controller.startRuntime(AgentId("agent-1"), ConversationId("conv-1"))
        assertEquals(2, client.runtimeStarts.size)

        controller.markConnected()
        assertIs<AppServerControllerState.Connected>(controller.state.first())
    }

    @Test
    fun startedRuntimesAreRecordedDurablyForReconnect() = runTest {
        val client = RecordingClient()
        val registry = InMemoryRuntimeRegistry()
        val controller = DefaultAppServerController(client = client, runtimeRegistry = registry)

        controller.startRuntime(AgentId("agent-1"), ConversationId("conv-1"), cwd = "/workspace")
        controller.onTransportDisconnected("process restart")

        // The durable record survives the disconnect even though the in-memory
        // cache was cleared — this is what the reconnect flow reattaches from.
        val record = registry.load("agent-1:conv-1")
        assertNotNull(record)
        assertEquals(AgentId("agent-1"), record.agentId)
        assertEquals(ConversationId("conv-1"), record.conversationId)
        assertEquals("/workspace", record.cwd)
        assertNotNull(record.canonicalRuntime)
        assertNotNull(record.lastStartedAt)
    }
}
