package com.letta.mobile.data.runtime

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * P3 (real cancel): [AppServerTurnEngine.abort] must address an `abort_message`
 * to the cached runtime scope with the caller-supplied (canonical) run id, so
 * the transport can ask the server to tear down the exact in-flight run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineAbortTest {

    @Test
    fun abortBeforeAnyRuntimeReturnsNull() = runTest {
        val client = RecordingAbortClient()
        val engine = AppServerTurnEngine(client = client, requestIdFactory = { "req" })
        assertNull(engine.abort("run-1"), "abort must be a no-op before a runtime is started")
        assertTrue(client.abortCommands.isEmpty())
    }

    @Test
    fun abortAddressesCachedRuntimeWithGivenRunId() = runTest {
        val client = RecordingAbortClient()
        val engine = AppServerTurnEngine(client = client, requestIdFactory = { "runtime-start-1" })

        // Run a full turn so the engine caches the runtime scope.
        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitStopReason()
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }

        val response = engine.abort("real-run-42")
        assertTrue(response != null && response.success)
        assertEquals(1, client.abortCommands.size)
        val sent = client.abortCommands.single()
        assertEquals("agent-1", sent.runtime.agentId)
        assertEquals("conv-1", sent.runtime.conversationId)
        assertEquals("real-run-42", sent.runId)
    }

    private companion object {
        val command = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:test"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    }

    private class RecordingAbortClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 16)
        val abortCommands = mutableListOf<AppServerCommand.AbortMessage>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
            abortCommands += command
            return AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId ?: "",
                runtime = command.runtime,
                aborted = true,
                success = true,
            )
        }

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emitStopReason() {
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = AppServerInboundFrame.StreamDelta(
                        runtime = runtime,
                        eventSeq = 2,
                        emittedAt = "2026-07-01T00:00:01Z",
                        idempotencyKey = "evt-stop",
                        delta = buildJsonObject {
                            put("message_type", "stop_reason")
                            put("run_id", "run-1")
                        },
                    ),
                    raw = buildJsonObject {
                        put("type", "stream_delta")
                        put("idempotency_key", "evt-stop")
                        put(
                            "delta",
                            buildJsonObject {
                                put("message_type", "stop_reason")
                                put("run_id", "run-1")
                            },
                        )
                    },
                ),
            )
        }
    }
}
