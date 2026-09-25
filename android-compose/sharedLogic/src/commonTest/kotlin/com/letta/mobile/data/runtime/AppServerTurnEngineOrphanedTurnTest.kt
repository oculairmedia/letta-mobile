package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.controller.fanout.RuntimeEventFanout
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * letta-mobile-qygvv.3: the engine never leaves a server turn running unobserved. A lease released
 * without a server terminal aborts its server turn; the busy path decides owner liveness from the
 * App Server's replayed loop status; an unleased approval gets the lease's policy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineOrphanedTurnTest {
    @Test
    fun watchdogReleaseSendsAbortForTheRun() = runTest {
        val client = TurnEngineTestRecordingClient()
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = backgroundScope.launch {
            runCatching { engine(client, idleTimeoutMs = IDLE_MS).runTurn(command).collect { drafts += it } }
        }
        runCurrent()
        client.emit(run1.assistantDelta())
        runCurrent()
        advanceTimeBy(IDLE_MS + 1_000)
        runCurrent()

        assertTrue(turn.isCompleted, "the idle watchdog must end the turn")
        assertEquals(RuntimeRunStatus.Failed, drafts.lastStatus())
        val abort = client.aborts.single()
        assertEquals("run-1", abort.runId)
        assertEquals(runtime, abort.runtime)
    }

    @Test
    fun cancelledCollectorSendsAbortBeforeReleasingTheLease() = runTest {
        val client = TurnEngineTestRecordingClient()
        val engine = engine(client)
        client.onAbort = {
            assertTrue(engine.isBusy("agent-1", "conv-1"), "the abort must go out while the lease is held")
        }
        val turn = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(run1.assistantDelta())
        runCurrent()

        turn.cancel()
        runCurrent()

        assertEquals(listOf("run-1"), client.aborts.map { it.runId })
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun handoverCancellationDoesNotAbort() = runTest {
        val client = TurnEngineTestRecordingClient()
        val engine = engine(client)
        val turn = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(run1.assistantDelta())
        runCurrent()

        turn.cancel(TurnHandoverCancellation())
        runCurrent()

        assertTrue(client.aborts.isEmpty(), "a handed-over turn keeps running under its new owner")
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun turnEndedByTheServerIsNotAborted() = runTest {
        val client = TurnEngineTestRecordingClient()
        val drafts = mutableListOf<RuntimeEventDraft>()
        // The fake ends this input's turn with turn_finished as soon as it is sent.
        engine(client).runTurn(autoFinishCommand).collect { drafts += it }
        assertEquals(RuntimeRunStatus.Completed, drafts.lastStatus())
        assertTrue(client.aborts.isEmpty())
    }

    @Test
    fun abortedRunsLateTurnFinishedDoesNotEndTheNextTurn() = runTest {
        val client = TurnEngineTestRecordingClient()
        val engine = engine(client)
        val first = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(run1.assistantDelta())
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(1, client.aborts.size)

        val drafts = mutableListOf<RuntimeEventDraft>()
        val second = backgroundScope.launch {
            engine.runTurn(nextCommand).collect { drafts += it }
        }
        runCurrent()
        // The aborted run's own turn_finished arrives after the next input was sent.
        client.emit(run1.turnFinished(1, TestStopReason.Cancelled))
        runCurrent()
        assertNull(drafts.lastStatus().takeIf { it in turnEngineTerminalStatuses }, "the aborted run ended the next turn")

        client.emit(run2.assistantDelta())
        client.emit(run2.turnFinished(2))
        runCurrent()
        assertEquals(RuntimeRunStatus.Completed, drafts.lastStatus())
        second.cancel()
    }

    @Test
    fun busySendAdmittedWhenSyncReplaysAnIdleLoop() = runTest {
        val client = TurnEngineTestRecordingClient(syncReplay = TestLoopState.WaitingOnInput.frame())
        val engine = engine(client)
        // The owner sent its input but the server never started it: no stream evidence arrives.
        val owner = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        assertTrue(engine.isBusy("agent-1", "conv-1"))

        var secondStarted = false
        backgroundScope.launch {
            runCatching {
                engine.runTurn(nextCommand).collect { secondStarted = true }
            }
        }
        runCurrent()

        val sync = client.syncs.single()
        assertEquals(true, sync.recoverApprovals)
        assertEquals(true, sync.forceDeviceStatus)
        assertTrue(owner.isCompleted, "the dead owner's job is cancelled and joined")
        assertTrue(secondStarted, "the successor turn is admitted")
        assertTrue(client.aborts.isEmpty(), "an idle server loop has no turn to abort")
        assertTrue(client.adminRpcs.isEmpty(), "the admin_rpc run probe is gone")
    }

    @Test
    fun busySendRejectedWhenSyncReplaysAnActiveRun() = runTest {
        val client = TurnEngineTestRecordingClient(syncReplay = TestLoopState.ProcessingApiResponse.frame(listOf(run1)))
        val engine = streamingOwner(client)
        val rejection = secondSend(engine)
        runCurrent()

        assertEquals(1, client.syncs.size)
        assertTrue(isTurnAlreadyActiveMessage(rejection.failureMessage()), "got ${rejection.failureMessage()}")
        assertTrue(engine.isBusy("agent-1", "conv-1"), "a live owner is never released")
    }

    @Test
    fun busySendRejectedWhenSyncReplaysNothing() = runTest {
        val engine = streamingOwner(TurnEngineTestRecordingClient(syncReplay = null))
        val rejection = secondSend(engine)
        advanceTimeBy(TurnOwnerLivenessProbe.PROBE_TIMEOUT_MS + 1_000)
        runCurrent()

        assertTrue(isTurnAlreadyActiveMessage(rejection.failureMessage()), "got ${rejection.failureMessage()}")
        assertTrue(engine.isBusy("agent-1", "conv-1"), "no evidence means no release")
    }

    @Test
    fun unleasedApprovalIsAutoAllowedUnderUnrestricted() = runTest {
        val client = TurnEngineTestRecordingClient()
        val engine = engine(client).also { it.ownRuntime() }

        val outcome = engine.answerUnleasedControlRequest(TestApprovalTool.Bash.controlRequest())

        assertEquals(UnleasedApprovalOutcome.AutoAllowed, outcome)
        val response = assertIs<AppServerInputPayload.ApprovalResponse>(client.approvalResponses.single())
        assertEquals("perm-1", response.requestId)
        assertIs<AppServerApprovalResponseDecision.Allow>(response.decision)
    }

    @Test
    fun unleasedApprovalForAnUnownedRuntimeIsNeverAutoAllowed() = runTest {
        // Another client's runtime on the shared App Server: this engine never ran a turn on it, so
        // the host default (approve-all) is not that client's policy.
        val client = TurnEngineTestRecordingClient()
        val engine = engine(client)

        assertEquals(UnleasedApprovalOutcome.NotOwned, engine.answerUnleasedControlRequest(TestApprovalTool.Bash.controlRequest()))
        assertTrue(client.inputs.isEmpty())
    }

    @Test
    fun unleasedInteractiveApprovalStaysPending() = runTest {
        val client = TurnEngineTestRecordingClient()
        val engine = engine(client).also { it.ownRuntime() }
        val outcome = engine.answerUnleasedControlRequest(TestApprovalTool.AskUserQuestion.controlRequest())

        assertEquals(UnleasedApprovalOutcome.LeftPending, outcome)
        assertTrue(client.approvalResponses.isEmpty())
    }

    @Test
    fun unleasedApprovalStaysPendingOutsideUnrestricted() = runTest {
        val client = TurnEngineTestRecordingClient()
        val engine = engine(client, mode = AppServerPermissionMode.Standard).also { it.ownRuntime() }

        assertEquals(UnleasedApprovalOutcome.LeftPending, engine.answerUnleasedControlRequest(TestApprovalTool.Bash.controlRequest()))
        assertTrue(client.approvalResponses.isEmpty())
    }

    @Test
    fun cancellingAQueuedLeaseNeverAbortsTheTurnAhead() = runTest {
        val client = TurnEngineTestRecordingClient(queuedAck = true)
        val engine = engine(client)
        val turn = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        // The turn ahead (another viewer's) streams while this input waits in the queue.
        client.emit(runAhead.assistantDelta())
        runCurrent()

        turn.cancel()
        runCurrent()

        assertTrue(client.aborts.isEmpty(), "a queued lease has no run of its own to abort")
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun cancellationBeforeTheRunIsKnownDoesNotAbortBlind() = runTest {
        val client = TurnEngineTestRecordingClient()
        val engine = engine(client)
        val turn = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()

        turn.cancel()
        runCurrent()

        assertTrue(client.aborts.isEmpty(), "an abort without a run id would abort whatever run is active")
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun leasedApprovalIsLeftToTheTurn() = runTest {
        val client = TurnEngineTestRecordingClient()
        val engine = engine(client)
        backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()

        assertEquals(UnleasedApprovalOutcome.Deferred, engine.answerUnleasedControlRequest(TestApprovalTool.Bash.controlRequest()))
        assertTrue(client.inputs.none { it.payload is AppServerInputPayload.ApprovalResponse })
    }

    @Test
    fun autoAnsweredApprovalIsNotRedeliveredFromTheFanoutBuffer() = runTest {
        val client = TurnEngineTestRecordingClient()
        val registry = InboundControlRequestRegistry()
        val fanout = RuntimeEventFanout(inboundControlRegistry = registry)
        val engine = engine(client, registry = registry).also { it.ownRuntime() }
        val frame = TestApprovalTool.Bash.controlRequest()
        fanout.route(frame.onStream())
        assertEquals(1, fanout.pendingControlFrameCount(), "no subscriber yet: the fanout buffers it")

        assertEquals(UnleasedApprovalOutcome.AutoAllowed, engine.answerUnleasedControlRequest(frame))

        val (_, events) = fanout.subscribe(AgentId("agent-1"), ConversationId("conv-1"))
        assertEquals(0, fanout.pendingControlFrameCount())
        assertNull(withTimeoutOrNull(FANOUT_WAIT_MS) { events.first() }, "an answered approval must not reach the next turn")
        assertEquals(1, client.approvalResponses.size, "answered exactly once")
    }

    @Test
    fun passiveObserverNeverTakesAControlRequest() = runTest {
        val fanout = RuntimeEventFanout()
        fanout.observe(AgentId("agent-1"), ConversationId("conv-1"))

        fanout.route(TestApprovalTool.Bash.controlRequest().onStream())

        assertEquals(1, fanout.pendingControlFrameCount(), "a probe must not swallow an approval")
    }

    private fun TestScope.engine(
        client: TurnEngineTestRecordingClient,
        idleTimeoutMs: Long = 600_000,
        mode: AppServerPermissionMode = AppServerPermissionMode.Unrestricted,
        registry: InboundControlRequestRegistry = InboundControlRequestRegistry(),
    ) = AppServerTurnEngine(
        client = client,
        permissionMode = mode,
        turnIdleTimeoutMs = idleTimeoutMs,
        inboundControlRegistry = registry,
        nowMs = { testScheduler.currentTime },
    )

    /** Runs one turn to completion so the runtime key is this engine's own. */
    private suspend fun AppServerTurnEngine.ownRuntime() {
        runTurn(autoFinishCommand).collect()
    }

    /** An owner turn on `agent-1/conv-1` that has streamed [run1], so its key is busy. */
    private fun TestScope.streamingOwner(client: TurnEngineTestRecordingClient): AppServerTurnEngine {
        val engine = engine(client)
        backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(run1.assistantDelta())
        runCurrent()
        return engine
    }

    /** A second send for the busy key; completes with its failure, if any. */
    private fun TestScope.secondSend(engine: AppServerTurnEngine): Deferred<Throwable?> =
        backgroundScope.async { runCatching { engine.runTurn(nextCommand).collect() }.exceptionOrNull() }

    /** The send's failure message once it has completed; empty while it is still running. */
    private fun Deferred<Throwable?>.failureMessage(): String =
        if (isCompleted) getCompleted()?.message.orEmpty() else ""

    private fun List<RuntimeEventDraft>.lastStatus(): RuntimeRunStatus? =
        mapNotNull { it.runLifecycleStatus() }.lastOrNull()

    private companion object {
        const val IDLE_MS = 10_000L
        const val FANOUT_WAIT_MS = 100L
        val run1 = TestRun("run-1")
        val run2 = TestRun("run-2")
        val runAhead = TestRun("run-ahead")
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
        )
        val nextCommand = command.copy(input = TurnInput.UserMessage(localMessageId = "local-2", text = "retry"))

        /** The fake ends this input's turn with `turn_finished` as soon as it is sent. */
        val autoFinishCommand = command.copy(input = TurnInput.UserMessage(localMessageId = AUTO_FINISH_MESSAGE_ID, text = "hi"))
    }
}
