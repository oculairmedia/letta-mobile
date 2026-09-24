package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.controller.fanout.RuntimeEventFanout
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerLoopStatus
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-qygvv.3: the engine never leaves a server turn running unobserved. A lease released
 * without a server terminal aborts its server turn; the busy path decides owner liveness from the
 * App Server's replayed loop status; an unleased approval gets the lease's policy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineOrphanedTurnTest {
    @Test
    fun watchdogReleaseSendsAbortForTheRun() = runTest {
        val client = OrphanClient()
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = backgroundScope.launch {
            runCatching { engine(client, idleTimeoutMs = IDLE_MS).runTurn(command).collect { drafts += it } }
        }
        runCurrent()
        client.emit(delta("assistant_message", "run-1"))
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
        val client = OrphanClient()
        val engine = engine(client)
        client.onAbort = {
            assertTrue(engine.isBusy("agent-1", "conv-1"), "the abort must go out while the lease is held")
        }
        val turn = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(delta("assistant_message", "run-1"))
        runCurrent()

        turn.cancel()
        runCurrent()

        assertEquals(listOf("run-1"), client.aborts.map { it.runId })
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun handoverCancellationDoesNotAbort() = runTest {
        val client = OrphanClient()
        val engine = engine(client)
        val turn = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(delta("assistant_message", "run-1"))
        runCurrent()

        turn.cancel(TurnHandoverCancellation())
        runCurrent()

        assertTrue(client.aborts.isEmpty(), "a handed-over turn keeps running under its new owner")
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun turnEndedByTheServerIsNotAborted() = runTest {
        val client = OrphanClient()
        val drafts = mutableListOf<RuntimeEventDraft>()
        // The fake ends this input's turn with turn_finished as soon as it is sent.
        val finishing = command.copy(input = TurnInput.UserMessage(localMessageId = AUTO_FINISH_ID, text = "hi"))
        engine(client).runTurn(finishing).collect { drafts += it }
        assertEquals(RuntimeRunStatus.Completed, drafts.lastStatus())
        assertTrue(client.aborts.isEmpty())
    }

    @Test
    fun abortedRunsLateTurnFinishedDoesNotEndTheNextTurn() = runTest {
        val client = OrphanClient()
        val engine = engine(client)
        val first = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(delta("assistant_message", "run-1"))
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(1, client.aborts.size)

        val drafts = mutableListOf<RuntimeEventDraft>()
        val second = backgroundScope.launch {
            engine.runTurn(command.copy(input = TurnInput.UserMessage(localMessageId = "local-2", text = "next")))
                .collect { drafts += it }
        }
        runCurrent()
        // The aborted run's own turn_finished arrives after the next input was sent.
        client.emit(turnFinished("turn-1", "run-1", stopReason = "cancelled"))
        runCurrent()
        assertNull(drafts.lastStatus().takeIf { it in terminalStatuses }, "the aborted run ended the next turn")

        client.emit(delta("assistant_message", "run-2"))
        client.emit(turnFinished("turn-2", "run-2"))
        runCurrent()
        assertEquals(RuntimeRunStatus.Completed, drafts.lastStatus())
        second.cancel()
    }

    @Test
    fun busySendAdmittedWhenSyncReplaysAnIdleLoop() = runTest {
        val client = OrphanClient(syncLoopStatus = AppServerLoopStatus(status = "WAITING_ON_INPUT"))
        val engine = engine(client)
        // The owner sent its input but the server never started it: no stream evidence arrives.
        val owner = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        assertTrue(engine.isBusy("agent-1", "conv-1"))

        var secondStarted = false
        backgroundScope.launch {
            runCatching {
                engine.runTurn(command.copy(input = TurnInput.UserMessage(localMessageId = "local-2", text = "retry")))
                    .collect { secondStarted = true }
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
        val client = OrphanClient(
            syncLoopStatus = AppServerLoopStatus(status = "PROCESSING_API_RESPONSE", activeRunIds = listOf("run-1")),
        )
        val engine = engine(client)
        backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(delta("assistant_message", "run-1"))
        runCurrent()

        var rejection: Throwable? = null
        backgroundScope.launch {
            rejection = runCatching {
                engine.runTurn(command.copy(input = TurnInput.UserMessage(localMessageId = "local-2", text = "retry")))
                    .collect()
            }.exceptionOrNull()
        }
        runCurrent()

        assertEquals(1, client.syncs.size)
        assertTrue(isTurnAlreadyActiveMessage(rejection?.message.orEmpty()), "got $rejection")
        assertTrue(engine.isBusy("agent-1", "conv-1"), "a live owner is never released")
    }

    @Test
    fun busySendRejectedWhenSyncReplaysNothing() = runTest {
        val client = OrphanClient(syncLoopStatus = null)
        val engine = engine(client)
        backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(delta("assistant_message", "run-1"))
        runCurrent()

        var rejection: Throwable? = null
        backgroundScope.launch {
            rejection = runCatching {
                engine.runTurn(command.copy(input = TurnInput.UserMessage(localMessageId = "local-2", text = "retry")))
                    .collect()
            }.exceptionOrNull()
        }
        advanceTimeBy(TurnOwnerLivenessProbe.PROBE_TIMEOUT_MS + 1_000)
        runCurrent()

        assertTrue(isTurnAlreadyActiveMessage(rejection?.message.orEmpty()), "got $rejection")
        assertTrue(engine.isBusy("agent-1", "conv-1"), "no evidence means no release")
    }

    @Test
    fun unleasedApprovalIsAutoAllowedUnderUnrestricted() = runTest {
        val client = OrphanClient()
        val engine = engine(client).also { it.ownRuntime() }

        val outcome = engine.answerUnleasedControlRequest(controlRequest("perm-1", "Bash"))

        assertEquals(UnleasedApprovalOutcome.AutoAllowed, outcome)
        val response = assertIs<AppServerInputPayload.ApprovalResponse>(client.approvalResponses().single())
        assertEquals("perm-1", response.requestId)
        assertIs<AppServerApprovalResponseDecision.Allow>(response.decision)
    }

    @Test
    fun unleasedApprovalForAnUnownedRuntimeIsNeverAutoAllowed() = runTest {
        // Another client's runtime on the shared App Server: this engine never ran a turn on it, so
        // the host default (approve-all) is not that client's policy.
        val client = OrphanClient()
        val engine = engine(client)

        assertEquals(UnleasedApprovalOutcome.NotOwned, engine.answerUnleasedControlRequest(controlRequest("perm-1", "Bash")))
        assertTrue(client.inputs.isEmpty())
    }

    @Test
    fun unleasedInteractiveApprovalStaysPending() = runTest {
        val client = OrphanClient()
        val engine = engine(client).also { it.ownRuntime() }
        val outcome = engine.answerUnleasedControlRequest(controlRequest("perm-1", "AskUserQuestion"))

        assertEquals(UnleasedApprovalOutcome.LeftPending, outcome)
        assertTrue(client.approvalResponses().isEmpty())
    }

    @Test
    fun unleasedApprovalStaysPendingOutsideUnrestricted() = runTest {
        val client = OrphanClient()
        val engine = engine(client, mode = AppServerPermissionMode.Standard).also { it.ownRuntime() }

        assertEquals(UnleasedApprovalOutcome.LeftPending, engine.answerUnleasedControlRequest(controlRequest("perm-1", "Bash")))
        assertTrue(client.approvalResponses().isEmpty())
    }

    @Test
    fun cancellingAQueuedLeaseNeverAbortsTheTurnAhead() = runTest {
        val client = OrphanClient(ackDisposition = "queued")
        val engine = engine(client)
        val turn = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        // The turn ahead (another viewer's) streams while this input waits in the queue.
        client.emit(delta("assistant_message", "run-ahead"))
        runCurrent()

        turn.cancel()
        runCurrent()

        assertTrue(client.aborts.isEmpty(), "a queued lease has no run of its own to abort")
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun cancellationBeforeTheRunIsKnownDoesNotAbortBlind() = runTest {
        val client = OrphanClient()
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
        val client = OrphanClient()
        val engine = engine(client)
        backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()

        assertEquals(UnleasedApprovalOutcome.Deferred, engine.answerUnleasedControlRequest(controlRequest("perm-1", "Bash")))
        assertTrue(client.inputs.none { it.payload is AppServerInputPayload.ApprovalResponse })
    }

    @Test
    fun autoAnsweredApprovalIsNotRedeliveredFromTheFanoutBuffer() = runTest {
        val client = OrphanClient()
        val registry = InboundControlRequestRegistry()
        val fanout = RuntimeEventFanout(inboundControlRegistry = registry)
        val engine = engine(client, registry = registry).also { it.ownRuntime() }
        val frame = controlRequest("perm-1", "Bash")
        fanout.route(received(frame))
        assertEquals(1, fanout.pendingControlFrameCount(), "no subscriber yet: the fanout buffers it")

        assertEquals(UnleasedApprovalOutcome.AutoAllowed, engine.answerUnleasedControlRequest(frame))

        val (_, events) = fanout.subscribe(AgentId("agent-1"), ConversationId("conv-1"))
        assertEquals(0, fanout.pendingControlFrameCount())
        assertNull(withTimeoutOrNull(FANOUT_WAIT_MS) { events.first() }, "an answered approval must not reach the next turn")
        assertEquals(1, client.approvalResponses().size, "answered exactly once")
    }

    @Test
    fun passiveObserverNeverTakesAControlRequest() = runTest {
        val fanout = RuntimeEventFanout()
        fanout.observe(AgentId("agent-1"), ConversationId("conv-1"))

        fanout.route(received(controlRequest("perm-1", "Bash")))

        assertEquals(1, fanout.pendingControlFrameCount(), "a probe must not swallow an approval")
    }

    private fun TestScope.engine(
        client: OrphanClient,
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
        runTurn(command.copy(input = TurnInput.UserMessage(localMessageId = AUTO_FINISH_ID, text = "hi"))).collect()
    }

    private fun OrphanClient.approvalResponses() = inputs.map { it.payload }.filterIsInstance<AppServerInputPayload.ApprovalResponse>()

    private fun List<RuntimeEventDraft>.lastStatus(): RuntimeRunStatus? =
        mapNotNull { (it.payload as? RuntimeEventPayload.RunLifecycleChanged)?.status }.lastOrNull()

    /**
     * Records every abort, sync and input. [syncLoopStatus] is what the App Server replays for a
     * `sync`; null replays nothing. A user input whose client message id is [AUTO_FINISH_ID] is
     * answered with a delta and `turn_finished`.
     */
    private class OrphanClient(
        private val syncLoopStatus: AppServerLoopStatus? = null,
        /** When set, `create_message` is acknowledged with this disposition; null keeps the pre-ack path. */
        private val ackDisposition: String? = null,
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
        val aborts = mutableListOf<AppServerCommand.AbortMessage>()
        val syncs = mutableListOf<AppServerCommand.Sync>()
        val inputs = mutableListOf<AppServerCommand.Input>()
        val adminRpcs = mutableListOf<AppServerCommand.AdminRpc>()
        var onAbort: () -> Unit = {}

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(requireNotNull(command.agentId), requireNotNull(command.conversationId)),
            )

        override suspend fun input(command: AppServerCommand.Input) {
            inputs += command
            val message = (command.payload as? AppServerInputPayload.CreateMessage)?.messages?.firstOrNull()
            if (message?.clientMessageId == AUTO_FINISH_ID) {
                emit(delta("assistant_message", "run-9"))
                emit(turnFinished("turn-9", "run-9"))
            }
        }

        override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted {
            val disposition = ackDisposition ?: throw UnsupportedOperationException("no ack")
            inputs += command
            return AppServerInboundFrame.InputAccepted(
                requestId = command.requestId.orEmpty(),
                runtime = command.runtime,
                accepted = true,
                disposition = disposition,
            )
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse {
            syncs += command
            syncLoopStatus?.let { status ->
                emit(
                    AppServerInboundFrame.UpdateLoopStatus(
                        runtime = command.runtime,
                        eventSeq = 50,
                        emittedAt = "2026-09-24T00:00:00Z",
                        idempotencyKey = "loop-replay-${syncs.size}",
                        loopStatus = status,
                    ),
                )
            }
            return AppServerInboundFrame.SyncResponse(requestId = command.requestId.orEmpty(), runtime = command.runtime, success = true)
        }

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
            onAbort()
            aborts += command
            return AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId.orEmpty(),
                runtime = command.runtime,
                aborted = true,
                success = true,
            )
        }

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
            adminRpcs += command
            return AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = false, error = "unexpected")
        }

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emit(frame: AppServerInboundFrame) {
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(received(frame))
        }
    }

    private companion object {
        const val IDLE_MS = 10_000L
        const val FANOUT_WAIT_MS = 100L
        const val AUTO_FINISH_ID = "auto-finish"
        val terminalStatuses = setOf(RuntimeRunStatus.Completed, RuntimeRunStatus.Failed, RuntimeRunStatus.Cancelled)
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
        )

        fun received(frame: AppServerInboundFrame) = AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = frame,
            raw = buildJsonObject {
                put("type", frame.type ?: "unknown")
                put("idempotency_key", "evt-${frame.type}-${frame.requestId}")
                if (frame is AppServerInboundFrame.StreamDelta) put("delta", frame.delta)
            },
        )

        fun delta(messageType: String, runId: String) = AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = 1,
            emittedAt = "2026-09-24T00:00:00Z",
            idempotencyKey = "delta-$messageType-$runId",
            delta = buildJsonObject {
                put("message_type", messageType)
                put("run_id", runId)
            },
        )

        fun turnFinished(turnId: String, runId: String, stopReason: String = "end_turn") =
            AppServerInboundFrame.TurnFinished(
                runtime = runtime,
                eventSeq = 2,
                emittedAt = "2026-09-24T00:00:00Z",
                idempotencyKey = "turn_finished:$turnId",
                turnId = turnId,
                stopReason = stopReason,
                runId = runId,
            )

        fun controlRequest(requestId: String, toolName: String) = AppServerInboundFrame.ControlRequest(
            requestId = requestId,
            request = buildJsonObject {
                put("subtype", "can_use_tool")
                put("tool_name", toolName)
                put("tool_call_id", "call-$requestId")
            },
            agentId = "agent-1",
            conversationId = "conv-1",
        )
    }
}
