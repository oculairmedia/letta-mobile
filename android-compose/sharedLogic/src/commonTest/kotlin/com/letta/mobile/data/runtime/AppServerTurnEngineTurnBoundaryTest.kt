package com.letta.mobile.data.runtime

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-qygvv.2: `turn_finished` and an idle `update_loop_status` after evidence are the
 * App Server's authoritative turn boundaries; the `stop_reason` delta settle stays the fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineTurnBoundaryTest {
    @Test
    fun turnFinishedCompletesWithoutTheSettleDelay() = runTest {
        val client = TurnEngineTestStreamClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(run1.assistantDelta())
            client.emit(run1.stopDelta())
            client.emit(run1.turnFinished(1))
            val terminal = awaitTerminalDraft()
            assertEquals(RuntimeRunStatus.Completed, terminal.runLifecycleStatus())
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
        val client = TurnEngineTestStreamClient()
        val engine = engine(client)
        engine.runTurn(command).test {
            awaitStarted()
            client.emit(run1.assistantDelta())
            client.emit(run1.turnFinished(1))
            assertEquals(RuntimeRunStatus.Completed, awaitTerminalDraft().runLifecycleStatus())
            awaitComplete()
        }
        engine.runTurn(secondCommand).test {
            awaitStarted()
            // The first turn's late stop delta and a replayed turn_finished must not end this turn.
            client.emit(run1.stopDelta())
            client.emit(run1.turnFinished(1))
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(run2.assistantDelta())
            client.emit(run2.turnFinished(2))
            val terminal = awaitTerminalDraft()
            assertEquals(RuntimeRunStatus.Completed, terminal.runLifecycleStatus())
            assertEquals("run-2", terminal.runId?.value)
            awaitComplete()
        }
    }

    @Test
    fun stopDeltaStillSettlesTheTurnWithoutTurnFinished() = runTest {
        val client = TurnEngineTestStreamClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(run1.stopDelta())
            val terminal = awaitTerminalDraft()
            assertEquals(RuntimeRunStatus.Completed, terminal.runLifecycleStatus())
            awaitComplete()
        }
        assertTrue(testScheduler.currentTime >= AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS)
    }

    @Test
    fun idleLoopStatusAfterEvidenceCompletesTheTurn() = runTest {
        val client = TurnEngineTestStreamClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(run1.assistantDelta())
            client.emit(TestLoopState.WaitingOnInput.frame())
            val terminal = awaitTerminalDraft()
            assertEquals(RuntimeRunStatus.Completed, terminal.runLifecycleStatus())
            assertEquals("run-1", terminal.runId?.value)
            awaitComplete()
        }
        assertTrue(testScheduler.currentTime < AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS)
    }

    @Test
    fun idleLoopStatusBeforeEvidenceDoesNotCompleteTheTurn() = runTest {
        val client = TurnEngineTestStreamClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(TestLoopState.WaitingOnInput.frame())
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(run1.stopDelta())
            assertEquals(RuntimeRunStatus.Completed, awaitTerminalDraft().runLifecycleStatus())
            awaitComplete()
        }
    }

    @Test
    fun waitingOnApprovalLoopStatusDoesNotCompleteTheTurn() = runTest {
        val client = TurnEngineTestStreamClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(run1.stopDelta(TestStopReason.RequiresApproval))
            client.emit(TestLoopState.WaitingOnApproval.frame())
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun nonTerminalLoopErrorDoesNotEndTheTurn() = runTest {
        val client = TurnEngineTestStreamClient()
        engine(client).runTurn(command).test {
            awaitStarted()
            client.emit(run1.loopErrorDelta(terminal = false))
            val notice = assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload)
            assertEquals("loop_error", notice.messageType)
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(run1.turnFinished(1))
            assertEquals(RuntimeRunStatus.Completed, awaitTerminalDraft().runLifecycleStatus())
            awaitComplete()
        }
    }

    @Test
    fun settledRunDraftDoesNotPromoteRunOrCompleteNextLeaseOnIdle() = runTest {
        val client = TurnEngineTestStreamClient()
        val engine = engine(client)
        engine.runTurn(command).test {
            awaitStarted()
            client.emit(run1.assistantDelta())
            client.emit(run1.turnFinished(1))
            assertEquals(RuntimeRunStatus.Completed, awaitTerminalDraft().runLifecycleStatus())
            awaitComplete()
        }
        engine.runTurn(secondCommand).test {
            awaitStarted()
            // Lingering draft from old settled run-1 projects into the stream...
            client.emit(run1.assistantDelta())
            val lingeringDraft = awaitItem()
            assertEquals("run-1", lingeringDraft.runId?.value)
            // ...but an idle loop status right after must not complete this lease because run-1 draft is not evidence for this lease.
            client.emit(TestLoopState.WaitingOnInput.frame())
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            // Valid new-run frames proceed and are not dropped as superseded_run.
            client.emit(run2.assistantDelta())
            client.emit(run2.turnFinished(2))
            val terminal = awaitTerminalDraft()
            assertEquals(RuntimeRunStatus.Completed, terminal.runLifecycleStatus())
            assertEquals("run-2", terminal.runId?.value)
            awaitComplete()
        }
    }

    private fun TestScope.engine(client: TurnEngineTestStreamClient) = AppServerTurnEngine(
        client = client,
        turnIdleTimeoutMs = 600_000,
        nowMs = { testScheduler.currentTime },
    )

    private suspend fun ReceiveTurbine<RuntimeEventDraft>.awaitStarted() {
        val started = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
        assertEquals(RuntimeRunStatus.Started, started.status)
    }

    private companion object {
        const val SETTLE_WINDOW_PASSED_MS = 5_000L
        val run1 = TestRun("run-1")
        val run2 = TestRun("run-2")
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
        )
        val secondCommand = command.copy(input = TurnInput.UserMessage(localMessageId = "local-2", text = "again"))
    }
}
