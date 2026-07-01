package com.letta.mobile.data.controller

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerCreatedRuntimeEntities
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AppServerControllerTest {
    @Test
    fun startRuntimeReturnsAndStoresCanonicalRuntime() = runTest {
        val client = FakeAppServerClient()
        val controller = DefaultAppServerController(
            client = client,
            requestIdFactory = { "req-1" },
        )

        val runtime = controller.startRuntime(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            cwd = "/workspace",
            mode = AppServerPermissionMode.Standard,
        )

        assertEquals("agent-1", runtime.scope.agentId)
        assertEquals("conv-1", runtime.scope.conversationId)
        assertNotNull(runtime.agent)
        assertEquals("agent-1", runtime.agent?.get("id")?.toString()?.trim('"'))

        val command = client.runtimeStartCommands.single()
        assertEquals("req-1", command.requestId)
        assertEquals("agent-1", command.agentId)
        assertEquals("conv-1", command.conversationId)
        assertEquals("/workspace", command.cwd)
        assertEquals(AppServerPermissionMode.Standard, command.mode)
    }

    @Test
    fun startRuntimeReturnsCachedRuntimeForSameAgentAndConversation() = runTest {
        val client = FakeAppServerClient()
        val controller = DefaultAppServerController(
            client = client,
            requestIdFactory = { "req-${client.runtimeStartCommands.size + 1}" },
        )

        // Start runtime first time
        val runtime1 = controller.startRuntime(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        // Start runtime again for same agent+conversation
        val runtime2 = controller.startRuntime(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        // Should return same cached runtime
        assertEquals(runtime1, runtime2)

        // Should only have called runtime_start once
        assertEquals(1, client.runtimeStartCommands.size)
    }

    @Test
    fun startRuntimeStartsNewRuntimeForDifferentAgentOrConversation() = runTest {
        val client = FakeAppServerClient()
        val controller = DefaultAppServerController(
            client = client,
            requestIdFactory = { "req-${client.runtimeStartCommands.size + 1}" },
        )

        // Start runtime for agent-1/conv-1
        val runtime1 = controller.startRuntime(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        // Start runtime for agent-2/conv-1 (different agent)
        val runtime2 = controller.startRuntime(
            agentId = AgentId("agent-2"),
            conversationId = ConversationId("conv-1"),
        )

        // Start runtime for agent-1/conv-2 (different conversation)
        val runtime3 = controller.startRuntime(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-2"),
        )

        assertEquals("agent-1", runtime1.scope.agentId)
        assertEquals("conv-1", runtime1.scope.conversationId)

        assertEquals("agent-2", runtime2.scope.agentId)
        assertEquals("conv-1", runtime2.scope.conversationId)

        assertEquals("agent-1", runtime3.scope.agentId)
        assertEquals("conv-2", runtime3.scope.conversationId)

        // Should have called runtime_start three times
        assertEquals(3, client.runtimeStartCommands.size)
        assertEquals(
            listOf("agent-1", "agent-2", "agent-1"),
            client.runtimeStartCommands.map { it.agentId },
        )
        assertEquals(
            listOf("conv-1", "conv-1", "conv-2"),
            client.runtimeStartCommands.map { it.conversationId },
        )
    }

    @Test
    fun startRuntimeThrowsWhenRuntimeStartFails() = runTest {
        val client = FakeAppServerClient(
            runtimeStartResponse = { command ->
                AppServerInboundFrame.RuntimeStartResponse(
                    requestId = command.requestId,
                    success = false,
                    error = "Agent not found",
                )
            },
        )
        val controller = DefaultAppServerController(client = client)

        val exception = assertFailsWith<AppServerControllerException> {
            controller.startRuntime(
                agentId = AgentId("unknown-agent"),
                conversationId = ConversationId("conv-1"),
            )
        }

        assertEquals("Runtime start failed for RuntimeKey(agentId=unknown-agent, conversationId=conv-1): Agent not found", exception.message)

        // State should be Error
        controller.state.test {
            val state = awaitItem()
            assertIs<AppServerControllerState.Error>(state)
            assertEquals("Runtime start failed: Agent not found", state.message)
        }
    }

    @Test
    fun runTurnStartsRuntimeAndCompletesOnStopReason() = runTest {
        val client = FakeAppServerClient()
        val controller = DefaultAppServerController(client = client)

        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(
                localMessageId = "local-1",
                text = "hello",
            ),
        )

        controller.runTurn(command).test {
            // Should emit Started lifecycle event
            val started = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Started, started.status)

            // Controller should have started the runtime
            assertEquals(1, client.runtimeStartCommands.size)

            // Client should have sent input command
            val input = assertIs<AppServerCommand.Input>(client.sentCommands.single())
            assertEquals("agent-1", input.runtime.agentId)
            assertEquals("conv-1", input.runtime.conversationId)

            // Emit stop_reason to complete the turn
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))

            // Should emit Completed lifecycle event
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)

            awaitComplete()
        }
    }

    @Test
    fun syncIssuesSyncCommandAndReturnsResponse() = runTest {
        val client = FakeAppServerClient()
        val controller = DefaultAppServerController(
            client = client,
            requestIdFactory = { "sync-req-1" },
        )

        val runtime = AppServerRuntimeScope("agent-1", "conv-1")

        val response = controller.sync(
            runtime = runtime,
            recoverApprovals = true,
            forceDeviceStatus = false,
        )

        assertEquals("sync-req-1", response.requestId)
        assertEquals(runtime, response.runtime)
        assertEquals(true, response.success)

        val command = client.syncCommands.single()
        assertEquals(runtime, command.runtime)
        assertEquals("sync-req-1", command.requestId)
        assertEquals(true, command.recoverApprovals)
        assertEquals(false, command.forceDeviceStatus)
    }

    @Test
    fun abortIssuesAbortCommandAndReturnsResponse() = runTest {
        val client = FakeAppServerClient()
        val controller = DefaultAppServerController(
            client = client,
            requestIdFactory = { "abort-req-1" },
        )

        val runtime = AppServerRuntimeScope("agent-1", "conv-1")

        val response = controller.abort(
            runtime = runtime,
            runId = "run-123",
        )

        assertEquals("abort-req-1", response.requestId)
        assertEquals(runtime, response.runtime)
        assertEquals(true, response.aborted)
        assertEquals(true, response.success)

        val command = client.abortCommands.single()
        assertEquals(runtime, command.runtime)
        assertEquals("abort-req-1", command.requestId)
        assertEquals("run-123", command.runId)
    }

    @Test
    fun connectionStateInitiallyConnected() = runTest {
        val client = FakeAppServerClient()
        val controller = DefaultAppServerController(client = client)

        controller.state.test {
            val state = awaitItem()
            assertEquals(AppServerControllerState.Connected, state)
        }
    }

    @Test
    fun connectionStateTransitionsToErrorOnRuntimeStartFailure() = runTest {
        val client = FakeAppServerClient(
            runtimeStartResponse = { command ->
                AppServerInboundFrame.RuntimeStartResponse(
                    requestId = command.requestId,
                    success = false,
                    error = "Connection timeout",
                )
            },
        )
        val controller = DefaultAppServerController(client = client)

        assertFailsWith<AppServerControllerException> {
            controller.startRuntime(
                agentId = AgentId("agent-1"),
                conversationId = ConversationId("conv-1"),
            )
        }

        controller.state.test {
            val state = awaitItem()
            assertIs<AppServerControllerState.Error>(state)
            assertEquals("Runtime start failed: Connection timeout", state.message)
            assertNull(state.cause)
        }
    }
}

/**
 * Fake App Server client for testing.
 */
private class FakeAppServerClient(
    private val runtimeStartResponse: (AppServerCommand.RuntimeStart) -> AppServerInboundFrame.RuntimeStartResponse = { command ->
        AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(
                agentId = requireNotNull(command.agentId),
                conversationId = requireNotNull(command.conversationId),
            ),
            agent = buildJsonObject {
                put("id", command.agentId)
                put("name", "Test Agent")
            },
            conversation = buildJsonObject {
                put("id", command.conversationId)
                put("name", "Test Conversation")
            },
            created = AppServerCreatedRuntimeEntities(
                agent = false,
                conversation = false,
            ),
        )
    },
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 16)

    val runtimeStartCommands = mutableListOf<AppServerCommand.RuntimeStart>()
    val sentCommands = mutableListOf<AppServerCommand>()
    val syncCommands = mutableListOf<AppServerCommand.Sync>()
    val abortCommands = mutableListOf<AppServerCommand.AbortMessage>()

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
        runtimeStartCommands += command
        return runtimeStartResponse(command)
    }

    override suspend fun input(command: AppServerCommand.Input) {
        sentCommands += command
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse {
        syncCommands += command
        return AppServerInboundFrame.SyncResponse(
            requestId = requireNotNull(command.requestId),
            runtime = command.runtime,
            success = true,
        )
    }

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
        abortCommands += command
        return AppServerInboundFrame.AbortMessageResponse(
            requestId = requireNotNull(command.requestId),
            runtime = command.runtime,
            aborted = true,
            success = true,
        )
    }

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
        sentCommands += command
    }

    fun emit(frame: AppServerInboundFrame) {
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject {
                    put("type", frame.type ?: "unknown")
                    put("idempotency_key", "evt-1")
                },
            ),
        )
    }
}

private fun streamDelta(
    messageType: String,
    runId: String,
    runtime: AppServerRuntimeScope = AppServerRuntimeScope("agent-1", "conv-1"),
): AppServerInboundFrame.StreamDelta =
    AppServerInboundFrame.StreamDelta(
        runtime = runtime,
        eventSeq = 1,
        emittedAt = "2026-06-27T00:00:00Z",
        idempotencyKey = "evt-1",
        delta = buildJsonObject {
            put("message_type", messageType)
            put("run_id", runId)
        },
    )
