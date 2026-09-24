package com.letta.mobile.data.runtime

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerLoopStatus
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-qygvv.8: with App Server 0.32.17's `client_message_ids_by_run_id`, a lease binds to
 * the run that consumed ITS `client_message_id`; frames of a run bound to another input can neither
 * mutate nor complete it. Without the mapping the engine keeps its scope-only behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineRunBindingTest {
    @Test
    fun earlierTurnCompletingLateDoesNotCompleteTheNextTurn() = runTest {
        val client = BindingClient()
        val engine = engine(client)
        engine.runTurn(commandFor("local-1")).test {
            awaitStarted()
            client.emit(loopStatus("PROCESSING_API_RESPONSE", mapOf("run-1" to listOf("local-1"))))
            client.emit(delta("assistant_message", "run-1"))
            client.emit(turnFinished("turn-1", "run-1"))
            assertEquals("run-1", awaitTerminal().runId?.value)
            awaitComplete()
        }
        engine.runTurn(commandFor("local-2")).test {
            awaitStarted()
            // A turn from another client on this conversation was queued ahead of ours and runs first.
            val mapping = mapOf(
                "run-1" to listOf("local-1"),
                "run-5" to listOf("other-device-1"),
                "run-6" to listOf("local-2"),
            )
            // The foreign run is the active one: its Running status must not reach this lease either.
            client.emit(loopStatus("PROCESSING_API_RESPONSE", mapping, activeRunIds = listOf("run-5")))
            client.emit(delta("assistant_message", "run-5"))
            client.emit(delta("stop_reason", "run-5", stopReason = "end_turn"))
            client.emit(turnFinished("turn-5", "run-5"))
            client.emit(delta("stop_reason", "run-1", stopReason = "end_turn"))
            client.emit(turnFinished("turn-1-replay", "run-1"))
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(delta("assistant_message", "run-6"))
            client.emit(turnFinished("turn-6", "run-6"))
            val terminal = awaitTerminal()
            assertEquals(RuntimeRunStatus.Completed, terminal.status())
            assertEquals("run-6", terminal.runId?.value)
            awaitComplete()
        }
    }

    @Test
    fun foreignRunPromotedBeforeTheMappingCannotFinishTheLease() = runTest {
        val client = BindingClient()
        engine(client).runTurn(commandFor("local-2")).test {
            awaitStarted()
            // The earlier run streams before any mapping arrives, so legacy promotion adopts it.
            client.emit(delta("assistant_message", "run-1"))
            awaitItem()
            client.emit(
                loopStatus(
                    "PROCESSING_API_RESPONSE",
                    mapOf("run-1" to listOf("local-1"), "run-2" to listOf("local-2")),
                    activeRunIds = listOf("run-1"),
                ),
            )
            client.emit(turnFinished("turn-1", "run-1"))
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(delta("assistant_message", "run-2"))
            client.emit(turnFinished("turn-2", "run-2"))
            assertEquals("run-2", awaitTerminal().runId?.value)
            awaitComplete()
        }
    }

    @Test
    fun runPromotedFromMapping() = runTest {
        val client = BindingClient()
        engine(client).runTurn(commandFor("local-1")).test {
            awaitStarted()
            // No active run and no run-bearing delta: only the mapping can name this lease's run.
            client.emit(loopStatus("PROCESSING_API_RESPONSE", mapOf("run-7" to listOf("local-1")), activeRunIds = emptyList()))
            client.emit(delta("assistant_message", runId = null))
            client.emit(loopStatus("WAITING_ON_INPUT", mapOf("run-7" to listOf("local-1")), activeRunIds = emptyList()))
            val terminal = awaitTerminal()
            assertEquals(RuntimeRunStatus.Completed, terminal.status())
            assertEquals("run-7", terminal.runId?.value)
            awaitComplete()
        }
    }

    @Test
    fun mappingAbsentKeepsLegacyBehaviour() = runTest {
        val client = BindingClient()
        engine(client).runTurn(commandFor("local-2")).test {
            awaitStarted()
            // An older server sends no mapping: any run on the scope still drives the lease.
            client.emit(loopStatus("PROCESSING_API_RESPONSE", emptyMap()))
            client.emit(delta("assistant_message", "run-1"))
            client.emit(turnFinished("turn-1", "run-1"))
            val terminal = awaitTerminal()
            assertEquals(RuntimeRunStatus.Completed, terminal.status())
            assertEquals("run-1", terminal.runId?.value)
            awaitComplete()
        }
    }

    private fun TestScope.engine(client: BindingClient) = AppServerTurnEngine(
        client = client,
        turnIdleTimeoutMs = 600_000,
        nowMs = { testScheduler.currentTime },
    )

    private suspend fun ReceiveTurbine<RuntimeEventDraft>.awaitStarted() {
        val started = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
        assertEquals(RuntimeRunStatus.Started, started.status)
    }

    /** Skips observable frames up to the first terminal lifecycle draft, which it returns. */
    private suspend fun ReceiveTurbine<RuntimeEventDraft>.awaitTerminal(): RuntimeEventDraft {
        while (true) {
            val item = awaitItem()
            val status = item.status() ?: continue
            if (status in terminalStatuses) return item
        }
    }

    private fun RuntimeEventDraft.status(): RuntimeRunStatus? =
        (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status

    private class BindingClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 32)

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

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = true, result = null)

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emit(frame: AppServerInboundFrame) {
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = frame,
                    raw = buildJsonObject {
                        put("type", frame.type ?: "unknown")
                        put("idempotency_key", "evt-${frame.type}")
                        if (frame is AppServerInboundFrame.StreamDelta) put("delta", frame.delta)
                    },
                ),
            )
        }
    }

    private companion object {
        const val SETTLE_WINDOW_PASSED_MS = 5_000L
        val terminalStatuses = setOf(RuntimeRunStatus.Completed, RuntimeRunStatus.Failed, RuntimeRunStatus.Cancelled)
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")

        fun commandFor(localMessageId: String) = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = localMessageId, text = "hello"),
        )

        fun delta(messageType: String, runId: String?, stopReason: String? = null) = AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = 1,
            emittedAt = "2026-09-24T00:00:00Z",
            idempotencyKey = "delta-$messageType-$runId",
            delta = buildJsonObject {
                put("message_type", messageType)
                runId?.let { put("run_id", it) }
                stopReason?.let { put("stop_reason", it) }
            },
        )

        fun turnFinished(turnId: String, runId: String) = AppServerInboundFrame.TurnFinished(
            runtime = runtime,
            eventSeq = 2,
            emittedAt = "2026-09-24T00:00:00Z",
            idempotencyKey = "turn_finished:$turnId",
            turnId = turnId,
            stopReason = "end_turn",
            runId = runId,
        )

        fun loopStatus(
            status: String,
            clientMessageIdsByRunId: Map<String, List<String>>,
            activeRunIds: List<String> = clientMessageIdsByRunId.keys.toList(),
        ) = AppServerInboundFrame.UpdateLoopStatus(
            runtime = runtime,
            eventSeq = 3,
            emittedAt = "2026-09-24T00:00:00Z",
            idempotencyKey = "loop:$status",
            loopStatus = AppServerLoopStatus(
                status = status,
                activeRunIds = activeRunIds,
                clientMessageIdsByRunId = clientMessageIdsByRunId,
            ),
        )
    }
}
