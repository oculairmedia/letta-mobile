package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.SESSION_LOST_TERMINAL_REASON
import com.letta.mobile.data.runtime.SESSION_RECONNECT_GRACE_MS
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.55: the iroh-wrapper's controller runs over a generation-stamped
 * [ReconnectingAppServerClient] pipe. With turnsSurviveReconnect, an upstream socket drop carries
 * the in-flight turn into the next generation and interrupts the router instead of cancelling the
 * lease: the turn completes on the successor generation's frames, frames the dead generation left
 * in the pipe stay fenced out, and a drop that is never recovered fails the turn once the grace
 * window runs out. Without the flag the lgns8.22.4 behaviour stands: the disconnect ends the turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerTurnSurvivesReconnectTest {

    @Test
    fun aTurnInFlightAcrossADropCompletesOnTheSuccessorGeneration() = runTest {
        val turn = startTurn(turnsSurviveReconnect = true)
        try {
            turn.client.emit(assistantDelta(seq = 1), generation = 0)
            runCurrent()

            turn.controller.onTransportDisconnected("upstream socket closed")
            runCurrent()
            assertTrue(turn.job.isActive, "the turn is reconnecting, not cancelled")
            assertTrue(turn.drafts.none { it.isTerminal() })

            // A frame the dead generation left in the pipe is fenced out; the successor's are not.
            turn.client.emit(assistantDelta(seq = 2), generation = 0)
            turn.controller.markConnected()
            turn.client.emit(assistantDelta(seq = 3), generation = 1)
            turn.client.emit(stopReasonDelta(seq = 4), generation = 1)
            advanceUntilIdle()
            turn.job.join()

            assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal().status)
            assertEquals(listOf("letta-msg-1", "letta-msg-3"), turn.assistantMessageIds())
        } finally {
            turn.controller.close()
        }
    }

    @Test
    fun aDropThatIsNeverRecoveredFailsTheTurnWhenTheGraceWindowEnds() = runTest {
        val turn = startTurn(turnsSurviveReconnect = true)
        try {
            turn.controller.onTransportDisconnected("upstream socket closed")
            advanceTimeBy(SESSION_RECONNECT_GRACE_MS - 1)
            runCurrent()
            assertTrue(turn.job.isActive)
            assertTrue(turn.drafts.none { it.isTerminal() })

            advanceTimeBy(2)
            advanceUntilIdle()
            turn.job.join()

            val terminal = turn.singleTerminal()
            assertEquals(RuntimeRunStatus.Failed, terminal.status)
            assertEquals(SESSION_LOST_TERMINAL_REASON, terminal.reason)
        } finally {
            turn.controller.close()
        }
    }

    @Test
    fun aSecondDropDuringTheWindowKeepsTheTurnOnTheLatestGeneration() = runTest {
        val turn = startTurn(turnsSurviveReconnect = true)
        try {
            turn.controller.onTransportDisconnected("first drop")
            runCurrent()
            // The first redial failed during recovery: the owner reports that generation lost too.
            turn.controller.onTransportDisconnected("recovery failed")
            runCurrent()
            turn.controller.markConnected()
            turn.client.emit(stopReasonDelta(seq = 1), generation = 2)
            advanceUntilIdle()
            turn.job.join()

            assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal().status)
        } finally {
            turn.controller.close()
        }
    }

    @Test
    fun withoutTheFlagADisconnectStillEndsTheTurn() = runTest {
        val turn = startTurn(turnsSurviveReconnect = false)
        try {
            turn.controller.onTransportDisconnected("upstream socket closed")
            advanceUntilIdle()

            assertFalse(turn.job.isActive, "lgns8.22.4: the superseded lease is cancelled at once")
            assertEquals(0L, testScheduler.currentTime, "no grace window was waited out")
            assertTrue(turn.drafts.none { it.status() == RuntimeRunStatus.Completed })
        } finally {
            turn.controller.close()
        }
    }

    private class RunningTurn(
        val client: StampingClient,
        val controller: DefaultAppServerController,
        val drafts: MutableList<RuntimeEventDraft>,
        val job: Job,
    ) {
        fun singleTerminal(): RuntimeEventPayload.RunLifecycleChanged {
            val terminals = drafts.filter { it.isTerminal() }
            assertEquals(1, terminals.size, "exactly one terminal in ${drafts.map { it.payload }}")
            return terminals.single().payload as RuntimeEventPayload.RunLifecycleChanged
        }

        fun assistantMessageIds(): List<String?> = drafts
            .mapNotNull { it.payload as? RuntimeEventPayload.RemoteStreamFrame }
            .filter { it.messageType == "assistant_message" }
            .map { it.messageId }
    }

    private fun TestScope.startTurn(turnsSurviveReconnect: Boolean): RunningTurn {
        val client = StampingClient()
        val controller = DefaultAppServerController(
            client = client,
            parentCoroutineContext = StandardTestDispatcher(testScheduler),
            turnsSurviveReconnect = turnsSurviveReconnect,
        )
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { controller.runTurn(COMMAND).toList(drafts) }
        runCurrent()
        return RunningTurn(client, controller, drafts, job)
    }

    /** Stands in for a [ReconnectingAppServerClient] pipe: every frame carries its generation stamp. */
    private class StampingClient : AppServerClient {
        private val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = inbound

        fun emit(frame: AppServerInboundFrame.StreamDelta, generation: Long) {
            inbound.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = frame,
                    raw = buildJsonObject {
                        put("type", "stream_delta")
                        put("idempotency_key", frame.idempotencyKey)
                        put("delta", frame.delta)
                    },
                    connectionGeneration = generation,
                ),
            )
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
            AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = true, runtime = RUNTIME)

        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync) =
            AppServerInboundFrame.SyncResponse(requestId = command.requestId ?: "sync", runtime = command.runtime, success = true)
        override suspend fun abort(command: AppServerCommand.AbortMessage) =
            AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId ?: "abort",
                runtime = command.runtime,
                aborted = true,
                success = true,
            )
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
        val RUNTIME = AppServerRuntimeScope(AGENT, CONVERSATION)
        val COMMAND = TurnCommand(
            backendId = BackendId("app-server"),
            runtimeId = RuntimeId("app-server:test"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONVERSATION),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )

        fun assistantDelta(seq: Long) = streamDelta(seq, "evt-assistant-$seq") {
            put("id", "letta-msg-$seq")
            put("message_type", "assistant_message")
            put("content", "part $seq")
            put("run_id", "run-1")
        }

        fun stopReasonDelta(seq: Long) = streamDelta(seq, "evt-stop-$seq") {
            put("message_type", "stop_reason")
            put("stop_reason", "end_turn")
            put("run_id", "run-1")
        }

        fun streamDelta(seq: Long, key: String, body: JsonObjectBuilder.() -> Unit) = AppServerInboundFrame.StreamDelta(
            runtime = RUNTIME,
            eventSeq = seq,
            emittedAt = "2026-10-15T00:00:${seq.toString().padStart(2, '0')}Z",
            idempotencyKey = key,
            delta = buildJsonObject(body),
        )

        fun RuntimeEventDraft.status(): RuntimeRunStatus? =
            (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status

        fun RuntimeEventDraft.isTerminal(): Boolean =
            status() in setOf(RuntimeRunStatus.Completed, RuntimeRunStatus.Failed, RuntimeRunStatus.Cancelled)
    }
}
