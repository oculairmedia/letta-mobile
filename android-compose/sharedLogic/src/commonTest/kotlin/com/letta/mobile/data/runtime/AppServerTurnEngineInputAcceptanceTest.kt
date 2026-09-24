package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerQueueRemoval
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        val client = AckingClient(ack(accepted = true, disposition = "started"))
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        val sent = client.acknowledgedInputs.single()
        assertEquals("req-1", sent.requestId, "create_message must carry a request_id so input_accepted comes back")
        assertTrue(client.plainInputs.isEmpty(), "acknowledged send must not also go out fire-and-forget")

        client.emit(streamDelta("assistant_message"))
        client.emit(streamDelta("stop_reason"))
        advanceUntilIdle()
        turn.join()

        assertNull(drafts.lifecycleReasons().firstOrNull { it == INPUT_QUEUED_REASON })
        assertEquals(RuntimeRunStatus.Completed, drafts.lastLifecycle()?.status)
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun rejectedInputFailsFastWithServerErrorAndReleasesLease() = runTest {
        val client = AckingClient(ack(accepted = false, error = "Runtime is no longer active"))
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()
        turn.join()

        val last = drafts.lastLifecycle()
        assertEquals(RuntimeRunStatus.Failed, last?.status)
        assertEquals("Runtime is no longer active", last?.reason)
        assertTrue(testScheduler.currentTime < IDLE_TIMEOUT_MS, "a rejected input must not wait for the watchdog")
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun rejectedInputWithoutErrorTextUsesFallbackReason() = runTest {
        val client = AckingClient(ack(accepted = false))
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        launch { engine.runTurn(command).collect { drafts += it } }.join()

        assertEquals(INPUT_REJECTED_FALLBACK_ERROR, drafts.lastLifecycle()?.reason)
    }

    @Test
    fun connectionLossBeforeAckFailsFast() = runTest {
        val client = AckingClient(ack(accepted = true)).apply {
            ackFailure = AppServerRequestFailedException(IllegalStateException("transport disconnected"))
        }
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        launch { engine.runTurn(command).collect { drafts += it } }.join()

        assertEquals(RuntimeRunStatus.Failed, drafts.lastLifecycle()?.status)
        assertEquals(INPUT_CONNECTION_LOST_REASON, drafts.lastLifecycle()?.reason)
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun queuedInputPausesWatchdogUntilFirstStreamFrame() = runTest {
        val client = AckingClient(ack(accepted = true, disposition = "queued"))
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        assertTrue(INPUT_QUEUED_REASON in drafts.lifecycleReasons(), "queued input must be visible")
        assertEquals(RuntimeRunStatus.Running, drafts.lastLifecycle()?.status)

        // Far past the idle window with no frames: a queued turn must stay alive.
        advanceTimeBy(IDLE_TIMEOUT_MS * 5)
        runCurrent()
        assertTrue(engine.isBusy("agent-1", "conv-1"), "watchdog must be paused while queued")
        assertEquals(RuntimeRunStatus.Running, drafts.lastLifecycle()?.status)

        // The turn starts: the watchdog is armed again and trips on fresh silence.
        client.emit(streamDelta("assistant_message"))
        runCurrent()
        advanceTimeBy(IDLE_TIMEOUT_MS + 1)
        advanceUntilIdle()
        turn.join()

        assertEquals(RuntimeRunStatus.Failed, drafts.lastLifecycle()?.status)
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun queuedInputThenStreamCompletesNormally() = runTest {
        val client = AckingClient(ack(accepted = true, disposition = "queued"))
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        client.emit(updateQueue(removed = listOf(AppServerQueueRemoval("local-1", "dequeued"))))
        client.emit(streamDelta("assistant_message"))
        client.emit(streamDelta("stop_reason"))
        advanceUntilIdle()
        turn.join()

        assertEquals(RuntimeRunStatus.Completed, drafts.lastLifecycle()?.status)
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun dequeuedRemovalResumesWatchdog() = runTest {
        val client = AckingClient(ack(accepted = true, disposition = "queued"))
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        client.emit(updateQueue(removed = listOf(AppServerQueueRemoval("local-1", "dequeued"))))
        runCurrent()
        advanceTimeBy(IDLE_TIMEOUT_MS + 1)
        advanceUntilIdle()
        turn.join()

        assertEquals(RuntimeRunStatus.Failed, drafts.lastLifecycle()?.status)
    }

    @Test
    fun updateQueueCancelledRemovalSettlesQueuedLeaseCancelled() = runTest {
        val client = AckingClient(ack(accepted = true, disposition = "queued"))
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        // Still queued: the snapshot lists this input and nothing was removed.
        client.emit(updateQueue(queued = listOf("local-1")))
        runCurrent()
        assertTrue(engine.isBusy("agent-1", "conv-1"))

        // Another input's removal must not touch this lease.
        client.emit(updateQueue(removed = listOf(AppServerQueueRemoval("someone-else", "cancelled"))))
        runCurrent()
        assertTrue(engine.isBusy("agent-1", "conv-1"))

        client.emit(updateQueue(removed = listOf(AppServerQueueRemoval("local-1", "cancelled"))))
        advanceUntilIdle()
        turn.join()

        val passthrough = drafts.count { it.payload is RuntimeEventPayload.ExternalTransportFrame }
        assertEquals(3, passthrough, "update_queue frames still reach viewers")
        val last = drafts.lastLifecycle()
        assertEquals(RuntimeRunStatus.Cancelled, last?.status)
        assertEquals(QUEUED_INPUT_CANCELLED_REASON, last?.reason)
        assertTrue(testScheduler.currentTime < IDLE_TIMEOUT_MS)
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun approvalResponseInputAwaitsAcceptance() = runTest {
        // letta-mobile-qygvv.5: approval responses carry a request_id and await the ack too.
        val client = AckingClient(ack(accepted = true))
        val engine = engineFor(client)
        val turn = launch { engine.runTurn(approvalCommand).collect { } }
        runCurrent()

        assertTrue(client.plainInputs.isEmpty())
        val sent = client.acknowledgedInputs.single()
        assertEquals("req-1", sent.requestId)
        assertEquals("approval-1", (sent.payload as AppServerInputPayload.ApprovalResponse).requestId)
        turn.cancel()
    }

    @Test
    fun rejectedApprovalResponseInputFailsFastWithServerError() = runTest {
        val client = AckingClient(ack(accepted = false, error = "Approval request is no longer pending"))
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(approvalCommand).collect { drafts += it } }
        runCurrent()
        turn.join()

        val last = drafts.lastLifecycle()
        assertEquals(RuntimeRunStatus.Failed, last?.status)
        assertEquals("Approval request is no longer pending", last?.reason)
        assertTrue(testScheduler.currentTime < IDLE_TIMEOUT_MS, "a rejected decision must not wait for the watchdog")
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun clientWithoutAckSupportFallsBackToPlainInput() = runTest {
        val client = AckingClient(ack(accepted = true)).apply { supportsAck = false }
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        val sent = client.plainInputs.single()
        assertNull(sent.requestId, "the fallback keeps the pre-ack wire shape")
        client.emit(streamDelta("stop_reason"))
        advanceUntilIdle()
        turn.join()
        assertEquals(RuntimeRunStatus.Completed, drafts.lastLifecycle()?.status)
    }

    @Test
    fun startEvidenceBeforeQueuedAckKeepsWatchdogArmed() = runTest {
        val gate = CompletableDeferred<AppServerInboundFrame.InputAccepted>()
        val client = AckingClient(ack(accepted = true, disposition = "queued")).apply { ackGate = gate }
        val engine = engineFor(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        // The collector sees the turn start before the send coroutine resumes on the ack.
        client.emit(streamDelta("assistant_message"))
        runCurrent()
        gate.complete(ack(accepted = true, disposition = "queued"))
        runCurrent()

        assertFalse(INPUT_QUEUED_REASON in drafts.lifecycleReasons(), "a started turn must not regress to queued")
        advanceTimeBy(IDLE_TIMEOUT_MS + 1)
        advanceUntilIdle()
        turn.join()
        assertEquals(RuntimeRunStatus.Failed, drafts.lastLifecycle()?.status)
    }

    @Test
    fun queuedAckMapsToQueuedAcceptance() {
        assertEquals(InputAcceptance.Queued, ack(accepted = true, disposition = "queued").toInputAcceptance())
        assertEquals(InputAcceptance.Started, ack(accepted = true, disposition = "started").toInputAcceptance())
        assertEquals(InputAcceptance.Started, ack(accepted = true).toInputAcceptance())
        val rejected = ack(accepted = false, error = "Input was rejected by the queue").toInputAcceptance()
        assertEquals(InputAcceptance.Rejected("Input was rejected by the queue"), rejected)
        assertNotNull(rejected)
    }

    private fun TestScope.engineFor(client: AppServerClient) = AppServerTurnEngine(
        client = client,
        requestIdFactory = { "req-1" },
        turnIdleTimeoutMs = IDLE_TIMEOUT_MS,
        terminalSettleQuietMs = 10,
        nowMs = { testScheduler.currentTime },
    )

    private fun List<RuntimeEventDraft>.lifecycles() =
        mapNotNull { it.payload as? RuntimeEventPayload.RunLifecycleChanged }

    private fun List<RuntimeEventDraft>.lastLifecycle() = lifecycles().lastOrNull()

    private fun List<RuntimeEventDraft>.lifecycleReasons() = lifecycles().mapNotNull { it.reason }

    private companion object {
        const val IDLE_TIMEOUT_MS = 1_000L
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
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

        fun ack(accepted: Boolean, disposition: String? = null, error: String? = null) =
            AppServerInboundFrame.InputAccepted(
                requestId = "req-1",
                runtime = runtime,
                accepted = accepted,
                disposition = disposition,
                error = error,
            )

        private var seq = 0L

        fun streamDelta(messageType: String): AppServerInboundFrame.StreamDelta {
            seq += 1
            return AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = seq,
                emittedAt = "2026-09-24T00:00:00Z",
                idempotencyKey = "evt-$messageType-$seq",
                delta = buildJsonObject {
                    put("message_type", messageType)
                    put("run_id", "run-1")
                },
            )
        }

        fun updateQueue(
            queued: List<String> = emptyList(),
            removed: List<AppServerQueueRemoval> = emptyList(),
        ): AppServerInboundFrame.UpdateQueue {
            seq += 1
            return AppServerInboundFrame.UpdateQueue(
                runtime = runtime,
                eventSeq = seq,
                emittedAt = "2026-09-24T00:00:00Z",
                idempotencyKey = "queue-$seq",
                queue = queued.map { id -> buildJsonObject { put("client_message_id", id) } },
                removed = removed,
            )
        }
    }

    private class AckingClient(private val response: AppServerInboundFrame.InputAccepted) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
        val acknowledgedInputs = mutableListOf<AppServerCommand.Input>()
        val plainInputs = mutableListOf<AppServerCommand.Input>()
        var supportsAck = true
        var ackFailure: Throwable? = null
        var ackGate: CompletableDeferred<AppServerInboundFrame.InputAccepted>? = null

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) {
            plainInputs += command
        }

        override suspend fun inputAwaitingAcceptance(
            command: AppServerCommand.Input,
        ): AppServerInboundFrame.InputAccepted {
            if (!supportsAck) throw UnsupportedOperationException("no ack")
            acknowledgedInputs += command
            ackFailure?.let { throw it }
            return ackGate?.await() ?: response
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emit(frame: AppServerInboundFrame) {
            val raw: JsonObject = buildJsonObject {
                put("type", frame.type ?: "unknown")
                if (frame is AppServerInboundFrame.StreamDelta) {
                    put("idempotency_key", frame.idempotencyKey)
                    put("delta", frame.delta)
                }
                if (frame is AppServerInboundFrame.UpdateQueue) put("idempotency_key", frame.idempotencyKey)
            }
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(channel = AppServerChannel.Stream, frame = frame, raw = raw),
            )
        }
    }
}
