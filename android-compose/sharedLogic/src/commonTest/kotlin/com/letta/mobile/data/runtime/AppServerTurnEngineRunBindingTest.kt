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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-qygvv.8: with App Server 0.32.17's `client_message_ids_by_run_id`, a lease binds to
 * the run that consumed ITS `client_message_id`; frames of a run bound to another input can neither
 * mutate nor complete it. Without the mapping the engine keeps its scope-only behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineRunBindingTest {
    @Test
    fun earlierTurnCompletingLateDoesNotCompleteTheNextTurn() = runTest {
        val client = TurnEngineTestStreamClient()
        val engine = engine(client)
        engine.runTurn(TestInput.Local1.command()).test {
            awaitStarted()
            client.emit(TestLoopState.ProcessingApiResponse.bound(listOf(run1), run1 to TestInput.Local1))
            client.emit(run1.assistantDelta())
            client.emit(run1.turnFinished(1))
            assertEquals(run1.id, awaitTerminalDraft().runId?.value)
            awaitComplete()
        }
        engine.runTurn(TestInput.Local2.command()).test {
            awaitStarted()
            // A turn from another client on this conversation was queued ahead of ours and runs first.
            // The foreign run is the active one: its Running status must not reach this lease either.
            client.emit(
                TestLoopState.ProcessingApiResponse.bound(
                    listOf(run5),
                    run1 to TestInput.Local1,
                    run5 to TestInput.OtherDevice,
                    run6 to TestInput.Local2,
                ),
            )
            client.emit(run5.assistantDelta())
            client.emit(run5.stopDelta())
            client.emit(run5.turnFinished(5))
            client.emit(run1.stopDelta())
            client.emit(run1.turnFinished(REPLAYED_TURN))
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(run6.assistantDelta())
            client.emit(run6.turnFinished(6))
            val terminal = awaitTerminalDraft()
            assertEquals(RuntimeRunStatus.Completed, terminal.runLifecycleStatus())
            assertEquals(run6.id, terminal.runId?.value)
            awaitComplete()
        }
    }

    @Test
    fun foreignRunPromotedBeforeTheMappingCannotFinishTheLease() = runTest {
        val client = TurnEngineTestStreamClient()
        engine(client).runTurn(TestInput.Local2.command()).test {
            awaitStarted()
            // The earlier run streams before any mapping arrives, so legacy promotion adopts it.
            client.emit(run1.assistantDelta())
            awaitItem()
            client.emit(
                TestLoopState.ProcessingApiResponse.bound(
                    listOf(run1),
                    run1 to TestInput.Local1,
                    run2 to TestInput.Local2,
                ),
            )
            client.emit(run1.turnFinished(1))
            advanceTimeBy(SETTLE_WINDOW_PASSED_MS)
            expectNoEvents()
            client.emit(run2.assistantDelta())
            client.emit(run2.turnFinished(2))
            assertEquals(run2.id, awaitTerminalDraft().runId?.value)
            awaitComplete()
        }
    }

    @Test
    fun runPromotedFromMapping() = runTest {
        val client = TurnEngineTestStreamClient()
        engine(client).runTurn(TestInput.Local1.command()).test {
            awaitStarted()
            // No active run and no run-bearing delta: only the mapping can name this lease's run.
            client.emit(TestLoopState.ProcessingApiResponse.bound(emptyList(), run7 to TestInput.Local1))
            client.emit(TestRun(null).assistantDelta())
            client.emit(TestLoopState.WaitingOnInput.bound(emptyList(), run7 to TestInput.Local1))
            val terminal = awaitTerminalDraft()
            assertEquals(RuntimeRunStatus.Completed, terminal.runLifecycleStatus())
            assertEquals(run7.id, terminal.runId?.value)
            awaitComplete()
        }
    }

    @Test
    fun mappingAbsentKeepsLegacyBehaviour() = runTest {
        val client = TurnEngineTestStreamClient()
        engine(client).runTurn(TestInput.Local2.command()).test {
            awaitStarted()
            // An older server sends no mapping: any run on the scope still drives the lease.
            client.emit(TestLoopState.ProcessingApiResponse.frame())
            client.emit(run1.assistantDelta())
            client.emit(run1.turnFinished(1))
            val terminal = awaitTerminalDraft()
            assertEquals(RuntimeRunStatus.Completed, terminal.runLifecycleStatus())
            assertEquals(run1.id, terminal.runId?.value)
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

    /** The `client_message_id`s a turn sends; the server maps each run to the one it consumed. */
    private enum class TestInput(val clientMessageId: String) {
        Local1("local-1"),
        Local2("local-2"),
        OtherDevice("other-device-1"),
        ;

        fun command() = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = clientMessageId, text = "hello"),
        )
    }

    /** This loop state with [activeRuns] running and each run mapped to the input it consumed. */
    private fun TestLoopState.bound(activeRuns: List<TestRun>, vararg consumed: Pair<TestRun, TestInput>) =
        frame(activeRuns).let { status ->
            val mapping = consumed.associate { (run, input) -> requireNotNull(run.id) to listOf(input.clientMessageId) }
            status.copy(loopStatus = status.loopStatus.copy(clientMessageIdsByRunId = mapping))
        }

    private companion object {
        const val SETTLE_WINDOW_PASSED_MS = 5_000L
        const val REPLAYED_TURN = 11
        val run1 = TestRun("run-1")
        val run2 = TestRun("run-2")
        val run5 = TestRun("run-5")
        val run6 = TestRun("run-6")
        val run7 = TestRun("run-7")
    }
}
