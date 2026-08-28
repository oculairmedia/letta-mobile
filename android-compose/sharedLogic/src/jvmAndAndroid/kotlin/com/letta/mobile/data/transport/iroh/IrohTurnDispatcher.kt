package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Executes the engine-owned portion of an Iroh turn after [IrohTurnRegistry] has
 * atomically admitted it. Keeping dispatch outside the transport preserves the
 * registry as the lifecycle authority while preventing connection and stream
 * failure branches from accumulating in the transport facade.
 */
internal class IrohTurnDispatcher(
    private val dependencies: IrohTurnDispatcherDependencies,
) {
    private val scope get() = dependencies.scope
    private val registry get() = dependencies.registry
    private val ready get() = dependencies.ready
    private val emitTurnFrame get() = dependencies.emitTurnFrame
    private val emitDraft get() = dependencies.emitDraft
    private val emitBoth get() = dependencies.emitBoth
    private val currentGeneration get() = dependencies.currentGeneration

    fun submit(submission: IrohTurnSubmission): Boolean {
        val turn = admit(submission) ?: return true
        reportConcurrentTurns(turn)
        track(launch(IrohTurnDispatch(turn, submission.input)), turn)
        return true
    }

    fun launch(request: IrohTurnDispatch): Job = scope.launch { dispatch(request) }

    private fun admit(submission: IrohTurnSubmission): IrohActiveTurn? {
        val turnId = IrohTurnId("iroh-turn-${java.util.UUID.randomUUID()}")
        val request = IrohTurnRequest(
            token = IrohTurnToken(IrohConversationId(submission.conversationId), currentGeneration(), turnId),
            runId = IrohRunId("iroh-run-${java.util.UUID.randomUUID()}"),
            agentId = IrohAgentId(submission.agentId),
        )
        return when (val result = registry.tryStart(request)) {
            is IrohTryStartResult.Started -> result.turn
            is IrohTryStartResult.Busy -> {
                reportBusyAdmission(submission.conversationId, request, result)
                null
            }
        }
    }

    private fun reportBusyAdmission(
        conversationId: String,
        request: IrohTurnRequest,
        result: IrohTryStartResult.Busy,
    ) {
        Telemetry.event(
            "IrohTransport", "send.rejected_same_conversation_busy",
            "conversationId" to conversationId,
            "activeTurnId" to result.activeTurn.turnId,
            "rejectedTurnId" to request.token.turnId.value,
        )
        scope.launch {
            emitBoth(ServerFrame.Error(
                id = IrohTransportSupport.frameId("error"), ts = IrohTransportSupport.nowIso(),
                code = "iroh_turn_engine_busy", message = "a turn is already active for this conversation",
                conversationId = conversationId, turnId = request.token.turnId.value, runId = request.runId.value,
            ))
            emitBoth(ServerFrame.TurnDone(
                id = IrohTransportSupport.frameId("turn_done"), ts = IrohTransportSupport.nowIso(),
                turnId = request.token.turnId.value, runId = request.runId.value, status = "failed",
            ))
        }
    }

    private fun reportConcurrentTurns(turn: IrohActiveTurn) {
        val concurrent = registry.concurrentTurns(turn.token.conversationId)
        if (concurrent.isEmpty()) return
        Telemetry.event(
            "IrohTransport", "turn.concurrent_start", "conversationId" to turn.conversationId,
            "turnId" to turn.turnId,
            "concurrentConversations" to concurrent.joinToString(",") { it.conversationId },
            "concurrentTurnIds" to concurrent.joinToString(",") { it.turnId },
        )
    }

    private fun track(job: Job, turn: IrohActiveTurn) {
        turn.job = job
        registry.registerSendJob(IrohSendJobRegistration(turn.token.conversationId, job))
        job.invokeOnCompletion {
            val removed = registry.finish(turn.token)
            if (removed && !turn.hasTerminal) {
                Telemetry.event("IrohTransport", "turn.abandoned_nonterminal", "conversationId" to turn.conversationId, "turnId" to turn.turnId, "runId" to turn.runId)
            } else if (!removed) {
                Telemetry.event("IrohTransport", "turn.completion_after_eviction", "conversationId" to turn.conversationId, "turnId" to turn.turnId, "hasTerminal" to turn.hasTerminal, "currentTurnId" to (registry.getActiveTurn(turn.token.conversationId)?.turnId ?: ""))
            }
            registry.unregisterSendJob(IrohSendJobRegistration(turn.token.conversationId, job))
        }
    }

    private suspend fun dispatch(request: IrohTurnDispatch) {
        Telemetry.event("IrohTrace", "transport.send.job_start", "turnId" to request.turn.turnId, "runId" to request.turn.runId)
        val handle = readyHandle(request) ?: return
        val engine = handle.turnEngine ?: error("Iroh send requested without turn engine")
        if (engine.isBusy(request.agentId, request.conversationId)) {
            reportBusyTurn(request, engine)
            return
        }
        emitStarted(request)
        collectTurn(request, handle, engine)
    }

    private suspend fun readyHandle(request: IrohTurnDispatch): IrohConnectionHandle? =
        runCatching { ready() }.getOrElse { error ->
            Telemetry.event(
                "IrohTransport", "turn.ready_failed",
                "error" to (error.message ?: error.toString()),
                "class" to error::class.simpleName,
            )
            emitFailure(request, "iroh_connection_not_ready", error.message ?: error.toString())
            null
        }

    private suspend fun reportBusyTurn(request: IrohTurnDispatch, engine: AppServerTurnEngine) {
        val owner = engine.activeTurnOwnerFor(request.agentId, request.conversationId)
        val ownerAcquiredAtMs = owner?.acquiredAtMs
        Telemetry.event(
            "IrohTransport", "turn.busy",
            "turnId" to request.turn.turnId,
            "runId" to request.turn.runId,
            "sendAgentId" to request.agentId,
            "sendConversationId" to request.conversationId,
            "sendOtid" to request.input.localMessageId,
            "ownerRunId" to owner?.runId,
            "ownerRuntimeId" to owner?.runtimeId,
            "ownerAgentId" to owner?.agentId,
            "ownerConversationId" to owner?.conversationId,
            "ownerAcquiredAtMs" to ownerAcquiredAtMs,
            "ownerHeldForMs" to ownerAcquiredAtMs?.let { System.currentTimeMillis() - it },
            "ownerLastTerminal" to owner?.lastTerminal,
            "ownerLastTerminalSource" to owner?.lastTerminalSource,
            "ownerLastTerminalAtMs" to owner?.lastTerminalAtMs,
            "ownerLastTerminalSeq" to owner?.lastTerminalSeq,
            "ownerLastTerminalScopeMatched" to owner?.lastTerminalScopeMatched,
            "ownerSettleDeadlineMs" to owner?.settleDeadlineMs,
            "ownerWatchdogDeadlineMs" to owner?.watchdogDeadlineMs,
            "ownerProcessRole" to owner?.processRole,
            "ownerReleaseReason" to owner?.releaseReason,
            "otherBusyKeys" to engine.busyRuntimeKeys()
                .filter { it.conversationId != request.conversationId || it.agentId != request.agentId }
                .joinToString(",") { it.toString() },
        )
        emitFailure(request, "iroh_turn_engine_busy", "Iroh App Server turn engine is already busy.")
    }

    private suspend fun emitStarted(request: IrohTurnDispatch) {
        emitTurnFrame(request.turn, turnStarted(request, request.turn.runId))
    }

    private suspend fun collectTurn(
        request: IrohTurnDispatch,
        handle: IrohConnectionHandle,
        engine: AppServerTurnEngine,
    ) {
        runCatching {
            engine.runTurn(request.command(handle.sessionId)).collect { draft ->
                draft.runId?.value?.let { realRunId -> publishRunPromotion(request, realRunId) }
                emitDraft(draft, request.turn).forEach { emitTurnFrame(request.turn, it) }
            }
        }.onFailure { error -> reportCollectionFailure(request, error) }
    }

    private suspend fun publishRunPromotion(request: IrohTurnDispatch, realRunId: String) {
        if (!registry.promoteRunId(IrohRunPromotion(request.turn.token, IrohRunId(realRunId)))) return
        emitTurnFrame(request.turn, turnStarted(request, realRunId))
    }

    private fun turnStarted(request: IrohTurnDispatch, runId: String) = ServerFrame.TurnStarted(
        id = IrohTransportSupport.frameId("turn_started"),
        ts = IrohTransportSupport.nowIso(),
        agentId = request.agentId,
        conversationId = request.conversationId,
        turnId = request.turn.turnId,
        runId = runId,
    )

    private suspend fun reportCollectionFailure(request: IrohTurnDispatch, error: Throwable) {
        if (error is CancellationException) {
            Telemetry.event("IrohTransport", "turn.cancelled", "turnId" to request.turn.turnId, "runId" to request.turn.runId)
            return
        }
        Telemetry.event(
            "IrohTransport", "turn.failed",
            "error" to (error.message ?: error.toString()),
            "class" to error::class.simpleName,
        )
        emitFailure(request, "iroh_app_server_error", error.message ?: error.toString())
    }

    private suspend fun emitFailure(request: IrohTurnDispatch, code: String, message: String) {
        emitTurnFrame(
            request.turn,
            ServerFrame.Error(
                id = IrohTransportSupport.frameId("error"),
                ts = IrohTransportSupport.nowIso(),
                code = code,
                message = message,
                conversationId = request.conversationId,
                turnId = request.turn.turnId,
                runId = request.turn.runId,
            ),
        )
        emitTurnFrame(
            request.turn,
            ServerFrame.TurnDone(
                id = IrohTransportSupport.frameId("turn_done"),
                ts = IrohTransportSupport.nowIso(),
                turnId = request.turn.turnId,
                runId = request.turn.runId,
                status = "failed",
            ),
        )
    }
}

internal data class IrohTurnDispatcherDependencies(
    val scope: CoroutineScope,
    val registry: IrohTurnRegistry,
    val ready: suspend () -> IrohConnectionHandle,
    val emitTurnFrame: suspend (IrohActiveTurn, ServerFrame) -> Unit,
    val emitDraft: (RuntimeEventDraft, IrohActiveTurn) -> List<ServerFrame>,
    val emitBoth: suspend (ServerFrame) -> Unit,
    val currentGeneration: () -> Long,
)

internal data class IrohTurnSubmission(
    val agentId: String,
    val conversationId: String,
    val input: TurnInput.UserMessage,
)

/** Immutable input for one already-admitted Iroh engine turn. */
internal data class IrohTurnDispatch(
    val turn: IrohActiveTurn,
    val input: TurnInput.UserMessage,
) {
    val agentId: String get() = turn.agentId
    val conversationId: String get() = turn.conversationId

    fun command(sessionId: String): TurnCommand = TurnCommand(
        backendId = BackendId("iroh-app-server"),
        runtimeId = RuntimeId("iroh:$sessionId"),
        agentId = AgentId(agentId),
        conversationId = ConversationId(conversationId),
        input = input,
    )
}
