package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerRequestFailedException
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecision
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalScope
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-qygvv.1: the engine sends `create_message` with a `request_id`,
 * awaits `input_accepted`, fails fast on `accepted=false`, pauses the idle
 * watchdog while the input is queued, and settles on an `update_queue`
 * cancellation instead of hanging silently until the idle watchdog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineInputAcceptanceTest {

    @Test
    fun acceptedStartedInputCarriesRequestIdAndCompletes() = runTest {
        val turn = startTurn(InputAckFixture.Started)

        val sent = turn.client.acknowledgedInputs.single()
        assertEquals(TEST_INPUT_REQUEST_ID, sent.requestId, "create_message must carry a request_id so input_accepted comes back")
        assertTrue(turn.client.plainInputs.isEmpty(), "acknowledged send must not also go out fire-and-forget")

        turn.client.emitStreamDelta("assistant_message")
        turn.client.emitStreamDelta("stop_reason")
        finish(turn)

        assertFalse(INPUT_QUEUED_REASON in turn.drafts.lifecycleReasons())
        assertEquals(RuntimeRunStatus.Completed, turn.drafts.lastLifecycle()?.status)
        assertFalse(turn.isBusy)
    }

    @Test
    fun rejectedInputFailsFastWithServerErrorAndReleasesLease() = runTest {
        val turn = startTurn(InputAckFixture.rejected("Runtime is no longer active"))
        turn.job.join()

        val last = turn.drafts.lastLifecycle()
        assertEquals(RuntimeRunStatus.Failed, last?.status)
        assertEquals("Runtime is no longer active", last?.reason)
        assertTrue(testScheduler.currentTime < IDLE_TIMEOUT_MS, "a rejected input must not wait for the watchdog")
        assertFalse(turn.isBusy)
    }

    @Test
    fun rejectedInputWithoutErrorTextUsesFallbackReason() = runTest {
        val turn = startTurn(InputAckFixture.RejectedWithoutError)
        turn.job.join()

        assertEquals(INPUT_REJECTED_FALLBACK_ERROR, turn.drafts.lastLifecycle()?.reason)
    }

    @Test
    fun connectionLossBeforeAckFailsFast() = runTest {
        val lost = AppServerRequestFailedException(IllegalStateException("transport disconnected"))
        val turn = startTurn(InputAckFixture.NoDisposition) { ackFailure = lost }
        turn.job.join()

        assertEquals(RuntimeRunStatus.Failed, turn.drafts.lastLifecycle()?.status)
        assertEquals(INPUT_CONNECTION_LOST_REASON, turn.drafts.lastLifecycle()?.reason)
        assertFalse(turn.isBusy)
    }

    @Test
    fun queuedInputPausesWatchdogUntilFirstStreamFrame() = runTest {
        val turn = startTurn(InputAckFixture.Queued)

        assertTrue(INPUT_QUEUED_REASON in turn.drafts.lifecycleReasons(), "queued input must be visible")
        assertEquals(RuntimeRunStatus.Running, turn.drafts.lastLifecycle()?.status)

        // Far past the idle window with no frames: a queued turn must stay alive.
        advanceTimeBy(IDLE_TIMEOUT_MS * 5)
        runCurrent()
        assertTrue(turn.isBusy, "watchdog must be paused while queued")
        assertEquals(RuntimeRunStatus.Running, turn.drafts.lastLifecycle()?.status)

        // A stream frame from the earlier turn must not unpause the watchdog while queued.
        turn.client.emitStreamDelta("assistant_message")
        runCurrent()
        advanceTimeBy(IDLE_TIMEOUT_MS * 5)
        runCurrent()
        assertTrue(turn.isBusy, "stream frame from earlier turn must not trip watchdog while queued")

        // Once dequeued, fresh silence trips the armed watchdog.
        turn.client.emitUpdateQueue(QueueUpdateFixture.dequeued(LOCAL_MESSAGE_ID))
        runCurrent()
        idleOut(turn)

        assertEquals(RuntimeRunStatus.Failed, turn.drafts.lastLifecycle()?.status)
        assertFalse(turn.isBusy)
    }

    @Test
    fun earlierTurnFinishedArrivingWhileQueuedDoesNotCompleteTurn() = runTest {
        val turn = startTurn(InputAckFixture.Queued)
        assertTrue(INPUT_QUEUED_REASON in turn.drafts.lifecycleReasons(), "queued input must be visible")

        // An earlier turn's turn_finished arriving while queued must be ignored and not complete this turn.
        turn.client.emitTurnFinished(turnId = "prev-turn-1", runId = "prev-run-1")
        runCurrent()
        assertTrue(turn.isBusy, "turn must remain active when earlier turn finishes")
        assertEquals(RuntimeRunStatus.Running, turn.drafts.lastLifecycle()?.status)

        // Dequeue, then our own turn streams and completes normally.
        turn.client.emitUpdateQueue(QueueUpdateFixture.dequeued(LOCAL_MESSAGE_ID))
        turn.client.emitStreamDelta("assistant_message")
        turn.client.emitTurnFinished(turnId = "turn-1", runId = "run-1")
        finish(turn)

        assertEquals(RuntimeRunStatus.Completed, turn.drafts.lastLifecycle()?.status)
        assertFalse(turn.isBusy)
    }

    @Test
    fun queuedInputThenStreamCompletesNormally() = runTest {
        val turn = startTurn(InputAckFixture.Queued)

        turn.client.emitUpdateQueue(QueueUpdateFixture.dequeued(LOCAL_MESSAGE_ID))
        turn.client.emitStreamDelta("assistant_message")
        turn.client.emitStreamDelta("stop_reason")
        finish(turn)

        assertEquals(RuntimeRunStatus.Completed, turn.drafts.lastLifecycle()?.status)
        assertFalse(turn.isBusy)
    }

    @Test
    fun dequeuedRemovalResumesWatchdog() = runTest {
        val turn = startTurn(InputAckFixture.Queued)

        turn.client.emitUpdateQueue(QueueUpdateFixture.dequeued(LOCAL_MESSAGE_ID))
        runCurrent()
        idleOut(turn)

        assertEquals(RuntimeRunStatus.Failed, turn.drafts.lastLifecycle()?.status)
    }

    @Test
    fun updateQueueCancelledRemovalSettlesQueuedLeaseCancelled() = runTest {
        val turn = startTurn(InputAckFixture.Queued)

        // Still queued: the snapshot lists this input and nothing was removed.
        turn.client.emitUpdateQueue(QueueUpdateFixture.stillQueued(LOCAL_MESSAGE_ID))
        runCurrent()
        assertTrue(turn.isBusy)

        // Another input's removal must not touch this lease.
        turn.client.emitUpdateQueue(QueueUpdateFixture.cancelled("someone-else"))
        runCurrent()
        assertTrue(turn.isBusy)

        turn.client.emitUpdateQueue(QueueUpdateFixture.cancelled(LOCAL_MESSAGE_ID))
        finish(turn)

        val passthrough = turn.drafts.count { it.payload is RuntimeEventPayload.ExternalTransportFrame }
        assertEquals(3, passthrough, "update_queue frames still reach viewers")
        val last = turn.drafts.lastLifecycle()
        assertEquals(RuntimeRunStatus.Cancelled, last?.status)
        assertEquals(QUEUED_INPUT_CANCELLED_REASON, last?.reason)
        assertTrue(testScheduler.currentTime < IDLE_TIMEOUT_MS)
        assertFalse(turn.isBusy)
    }

    @Test
    fun approvalResponseInputStaysFireAndForget() = runTest {
        val turn = startTurn(InputAckFixture.NoDisposition, approvalCommand)

        assertTrue(turn.client.acknowledgedInputs.isEmpty())
        val sent = turn.client.plainInputs.single()
        assertNull(sent.requestId)
        assertTrue(sent.payload is AppServerInputPayload.ApprovalResponse)
        turn.job.cancel()
    }

    @Test
    fun clientWithoutAckSupportFallsBackToPlainInput() = runTest {
        val turn = startTurn(InputAckFixture.NoDisposition) { supportsAck = false }

        val sent = turn.client.plainInputs.single()
        assertNull(sent.requestId, "the fallback keeps the pre-ack wire shape")
        turn.client.emitStreamDelta("stop_reason")
        finish(turn)
        assertEquals(RuntimeRunStatus.Completed, turn.drafts.lastLifecycle()?.status)
    }

    @Test
    fun startEvidenceBeforeQueuedAckKeepsWatchdogArmed() = runTest {
        val gate = CompletableDeferred<AppServerInboundFrame.InputAccepted>()
        val turn = startTurn(InputAckFixture.Queued) { ackGate = gate }

        // The collector sees the turn start before the send coroutine resumes on the ack.
        turn.client.emitStreamDelta("assistant_message")
        runCurrent()
        gate.complete(frames.inputAccepted(InputAckFixture.Queued))
        runCurrent()

        assertFalse(INPUT_QUEUED_REASON in turn.drafts.lifecycleReasons(), "a started turn must not regress to queued")
        idleOut(turn)
        assertEquals(RuntimeRunStatus.Failed, turn.drafts.lastLifecycle()?.status)
    }

    @Test
    fun queuedAckMapsToQueuedAcceptance() {
        assertEquals(InputAcceptance.Queued, frames.inputAccepted(InputAckFixture.Queued).toInputAcceptance())
        assertEquals(InputAcceptance.Started, frames.inputAccepted(InputAckFixture.Started).toInputAcceptance())
        assertEquals(InputAcceptance.Started, frames.inputAccepted(InputAckFixture.NoDisposition).toInputAcceptance())
        val rejected = frames.inputAccepted(InputAckFixture.rejected("Input was rejected by the queue")).toInputAcceptance()
        assertEquals(InputAcceptance.Rejected("Input was rejected by the queue"), rejected)
    }

    /** One running turn against a fake acknowledging App Server. */
    private class RunningTurn(
        val client: TurnEngineTestAckingClient,
        val engine: AppServerTurnEngine,
        val drafts: List<RuntimeEventDraft>,
        val job: Job,
    ) {
        val isBusy: Boolean get() = engine.isBusy("agent-1", "conv-1")
    }

    private fun TestScope.startTurn(
        ack: InputAckFixture,
        turnCommand: TurnCommand = command,
        configure: TurnEngineTestAckingClient.() -> Unit = {},
    ): RunningTurn {
        val client = TurnEngineTestAckingClient(frames, ack).apply(configure)
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { TEST_INPUT_REQUEST_ID },
            turnIdleTimeoutMs = IDLE_TIMEOUT_MS,
            terminalSettleQuietMs = 10,
            nowMs = { testScheduler.currentTime },
        )
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { engine.runTurn(turnCommand).collect { drafts += it } }
        runCurrent()
        return RunningTurn(client, engine, drafts, job)
    }

    private suspend fun TestScope.finish(turn: RunningTurn) {
        advanceUntilIdle()
        turn.job.join()
    }

    /** Lets the idle window lapse with no frames, then waits for the turn to end. */
    private suspend fun TestScope.idleOut(turn: RunningTurn) {
        advanceTimeBy(IDLE_TIMEOUT_MS + 1)
        finish(turn)
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 1_000L
        const val LOCAL_MESSAGE_ID = "local-1"
        val frames = TurnEngineTestFrames(AppServerRuntimeScope("agent-1", "conv-1"))
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = LOCAL_MESSAGE_ID, text = "hey"),
        )
        val approvalCommand = command.copy(
            input = TurnInput.ToolApprovalResponse(
                ToolApprovalDecision(
                    approvalId = ToolApprovalId("approval-1"),
                    callId = ToolCallId("call-1"),
                    decision = ToolApprovalDecisionValue.Approved,
                    scope = ToolApprovalScope.Once,
                ),
            ),
        )
    }
}
