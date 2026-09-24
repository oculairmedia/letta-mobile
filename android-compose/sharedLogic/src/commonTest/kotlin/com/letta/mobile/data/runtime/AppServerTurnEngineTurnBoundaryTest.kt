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
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-qygvv.2: `turn_finished` and an idle `update_loop_status` after evidence are the
 * App Server's authoritative turn boundaries; the `stop_reason` delta settle stays the fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineTurnBoundaryTest {
    @Test
    fun turnFinishedCompletesWithoutTheSettleDelay() = runTest {
        val client = BoundaryClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(delta("assistant_message", "run-1"))
            client.emit(delta("stop_reason", "run-1", stopReason = "end_turn"))
            client.emit(turnFinished("turn-1", "run-1"))
            val terminal = awaitTerminal()
            assertEquals(RuntimeRunStatus.Completed, terminal.status())
            assertEquals("run-1", terminal.runId?.value)
            awaitComplete()
        }
        assertTrue(
            testScheduler.currentTime < AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS,
            "turn_finished waited out the settle window: t=${testScheduler.currentTime}",
        )
    }

    @Test
    fun turnFinishedThenLateStopDeltaDoesNotDoubleTerminal() = runTest {
        val client = BoundaryClient()
        val engine = engine(client)
        engine.runTurn(command).test {
            awaitStarted()
            client.emit(delta("assistant_message", "run-1"))
            client.emit(turnFinished("turn-1", "run-1"))
            assertEquals(RuntimeRunStatus.Completed, awaitTerminal().status())
            awaitComplete()
        }
        engine.runTurn(command.copy(input = TurnInput.UserMessage(localMessageId = "local-2", text = "again"))).test {
            awaitStarted()
            // The first turn's late stop delta and a replayed turn_finished must not end this turn.
            client.emit(delta("stop_reason", "run-1", stopReason = "end_turn"))
            client.emit(turnFinished("turn-1", "run-1"))
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(delta("assistant_message", "run-2"))
            client.emit(turnFinished("turn-2", "run-2"))
            val terminal = awaitTerminal()
            assertEquals(RuntimeRunStatus.Completed, terminal.status())
            assertEquals("run-2", terminal.runId?.value)
            awaitComplete()
        }
    }

    @Test
    fun stopDeltaStillSettlesTheTurnWithoutTurnFinished() = runTest {
        val client = BoundaryClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(delta("stop_reason", "run-1", stopReason = "end_turn"))
            val terminal = awaitTerminal()
            assertEquals(RuntimeRunStatus.Completed, terminal.status())
            awaitComplete()
        }
        assertTrue(testScheduler.currentTime >= AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS)
    }

    @Test
    fun idleLoopStatusAfterEvidenceCompletesTheTurn() = runTest {
        val client = BoundaryClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(delta("assistant_message", "run-1"))
            client.emit(loopStatus("WAITING_ON_INPUT"))
            val terminal = awaitTerminal()
            assertEquals(RuntimeRunStatus.Completed, terminal.status())
            assertEquals("run-1", terminal.runId?.value)
            awaitComplete()
        }
        assertTrue(testScheduler.currentTime < AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS)
    }

    @Test
    fun idleLoopStatusBeforeEvidenceDoesNotCompleteTheTurn() = runTest {
        val client = BoundaryClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(loopStatus("WAITING_ON_INPUT"))
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(delta("stop_reason", "run-1", stopReason = "end_turn"))
            assertEquals(RuntimeRunStatus.Completed, awaitTerminal().status())
            awaitComplete()
        }
    }

    @Test
    fun waitingOnApprovalLoopStatusDoesNotCompleteTheTurn() = runTest {
        val client = BoundaryClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(delta("stop_reason", "run-1", stopReason = "requires_approval"))
            client.emit(loopStatus("WAITING_ON_APPROVAL"))
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun nonTerminalLoopErrorDoesNotEndTheTurn() = runTest {
        val client = BoundaryClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(
                delta("loop_error", "run-1") {
                    put("message", "retrying provider")
                    put("is_terminal", false)
                },
            )
            val notice = assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload)
            assertEquals("loop_error", notice.messageType)
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(turnFinished("turn-1", "run-1"))
            assertEquals(RuntimeRunStatus.Completed, awaitTerminal().status())
            awaitComplete()
        }
    }

    private fun TestScope.engine(client: BoundaryClient) = AppServerTurnEngine(
        client = client,
        turnIdleTimeoutMs = 600_000,
        nowMs = { testScheduler.currentTime },
    )

    private suspend fun ReceiveTurbine<RuntimeEventDraft>.awaitStarted() {
        val started = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
        assertEquals(RuntimeRunStatus.Started, started.status)
    }

    private typealias BoundaryClient = TurnEngineTestStreamClient

    private suspend fun ReceiveTurbine<RuntimeEventDraft>.awaitTerminal(): RuntimeEventDraft = awaitTerminalDraft()

    private fun RuntimeEventDraft.status(): RuntimeRunStatus? = runLifecycleStatus()

    private companion object {
        const val SETTLE_WINDOW_PASSED_MS = 5_000L
        val terminalStatuses = turnEngineTerminalStatuses
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
        )

        fun delta(
            messageType: String,
            runId: String,
            stopReason: String? = null,
            extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
        ) = AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = 1,
            emittedAt = "2026-09-24T00:00:00Z",
            idempotencyKey = "delta-$messageType-$runId",
            delta = buildJsonObject {
                put("message_type", messageType)
                put("run_id", runId)
                stopReason?.let { put("stop_reason", it) }
                extra()
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

        fun loopStatus(status: String) = AppServerInboundFrame.UpdateLoopStatus(
            runtime = runtime,
            eventSeq = 3,
            emittedAt = "2026-09-24T00:00:00Z",
            idempotencyKey = "loop:$status",
            loopStatus = AppServerLoopStatus(status = status),
        )
    }
}
