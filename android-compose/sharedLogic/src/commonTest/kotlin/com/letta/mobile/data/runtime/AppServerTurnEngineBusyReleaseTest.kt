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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-kyqdt: authoritative busy-ownership release on terminal completion.
 *
 * Root cause: [AppServerTurnEngine]'s completed-lifecycle terminal was gated behind
 * a re-armable quiet timer ([rescheduleCompletedTerminal]) that ANY later matching
 * frame reset. On the shared server-side engine (one engine fanned out across every
 * Iroh connection viewing the conversation), a steady trickle of matching frames —
 * e.g. cross-device viewer traffic or late fanout deltas — kept resetting the timer,
 * so the completed run's terminal (and the [activeTurn] mutex unlock) was deferred
 * indefinitely. A second device's send then hit
 * "An App Server turn is already active ..." long after the prior run was terminal.
 *
 * Invariant under test: a terminal COMPLETED lifecycle releases busy ownership
 * atomically and is NOT deferrable by subsequent non-terminal frames.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineBusyReleaseTest {

    @Test
    fun completedTerminalReleasesBusyEvenWhileLaterFramesKeepArriving() = runTest {
        val client = FakeBusyClient()
        // Use the production-representative settle window so the regression is real.
        val engine = AppServerTurnEngine(client = client)

        val turn = backgroundScope.async {
            engine.runTurn(command).collect()
        }
        runCurrent()
        assertTrue(engine.isBusy, "engine must be busy once the turn is running")

        // Server reports the run terminal (stop_reason -> completed lifecycle).
        client.emit(streamDelta("stop_reason", "run-1"))
        runCurrent()

        // Now a steady trickle of later matching frames arrives (cross-device
        // viewer traffic / late fanout). Under the bug each one re-armed the
        // terminal quiet timer, deferring the unlock forever.
        repeat(50) {
            client.emit(streamDelta("assistant_message", "run-1"))
            advanceTimeBy(500) // shorter than the settle window, so the old code re-armed
            runCurrent()
        }

        // The completed run must have released busy ownership by now.
        assertFalse(
            engine.isBusy,
            "completed terminal must release busy ownership; a trickle of later frames must not defer it",
        )
        turn.cancel()
    }

    @Test
    fun completedTerminalReleasesBusyPromptlyForImmediateNextSend() = runTest {
        val client = FakeBusyClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIsStarted(awaitItem().payload)
            client.emit(streamDelta("stop_reason", "run-1"))
            assertEquals(
                "stop_reason",
                (awaitItem().payload as RuntimeEventPayload.RemoteStreamFrame).messageType,
            )
            val completed = awaitItem().payload as RuntimeEventPayload.RunLifecycleChanged
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }

        // Terminal has fully drained: the engine must be immediately re-sendable.
        assertFalse(engine.isBusy, "engine must be free right after the completed terminal drains")

        // The immediate next send (same conversation) must be accepted — no false busy.
        engine.runTurn(secondCommand).test {
            assertIsStarted(awaitItem().payload)
            client.emit(streamDelta("stop_reason", "run-2"))
            assertEquals(
                "stop_reason",
                (awaitItem().payload as RuntimeEventPayload.RemoteStreamFrame).messageType,
            )
            assertEquals(
                RuntimeRunStatus.Completed,
                (awaitItem().payload as RuntimeEventPayload.RunLifecycleChanged).status,
            )
            awaitComplete()
        }
    }

    @Test
    fun terminalThenImmediateNextSendRaceLosesNoTurnAndPreservesOtidOnce() = runTest {
        val client = FakeBusyClient()
        val engine = AppServerTurnEngine(client = client)

        // First turn runs to a completed terminal.
        val first = launch { engine.runTurn(command).collect() }
        runCurrent()
        client.emit(streamDelta("stop_reason", "run-1"))
        advanceUntilIdle()
        first.join()

        // Deterministic race: the next send is issued as soon as the terminal
        // drained. It must be accepted (busy released atomically with the terminal).
        var secondAccepted = false
        val second = launch {
            engine.runTurn(secondCommand).collect { secondAccepted = true }
        }
        runCurrent()
        client.emit(streamDelta("stop_reason", "run-2"))
        advanceUntilIdle()
        second.join()

        assertTrue(secondAccepted, "the immediate next send after a completed terminal must be accepted")

        // OTID (client_message_id) preserved exactly once per turn: one input per send.
        val otids = client.sentUserMessages.map { it.clientMessageId }
        assertEquals(2, otids.size, "each accepted send must issue exactly one user input")
        assertEquals(listOf("local-1", "local-2"), otids)
        assertEquals(
            otids.size,
            otids.filterNotNull().toSet().size,
            "no OTID may be duplicated across sends",
        )
    }

    private fun assertIsStarted(payload: RuntimeEventPayload) {
        val lifecycle = payload as RuntimeEventPayload.RunLifecycleChanged
        assertEquals(RuntimeRunStatus.Started, lifecycle.status)
    }

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
        )
        val secondCommand = command.copy(
            input = TurnInput.UserMessage(localMessageId = "local-2", text = "second"),
        )

        fun streamDelta(messageType: String, runId: String): AppServerInboundFrame.StreamDelta =
            AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = 1,
                emittedAt = "2026-07-11T00:00:00Z",
                idempotencyKey = "evt-$messageType-$runId",
                delta = buildJsonObject {
                    put("message_type", messageType)
                    put("run_id", runId)
                },
            )
    }

    private class FakeBusyClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
        val sentUserMessages = mutableListOf<com.letta.mobile.data.transport.appserver.AppServerInputMessage>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) {
            (command.payload as? com.letta.mobile.data.transport.appserver.AppServerInputPayload.CreateMessage)
                ?.messages
                ?.filter { it.role == "user" }
                ?.let { sentUserMessages += it }
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emit(frame: AppServerInboundFrame) {
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = frame,
                    raw = buildJsonObject {
                        put("type", frame.type ?: "unknown")
                        put("idempotency_key", "evt-1")
                        if (frame is AppServerInboundFrame.StreamDelta) put("delta", frame.delta)
                    },
                ),
            )
        }
    }
}
