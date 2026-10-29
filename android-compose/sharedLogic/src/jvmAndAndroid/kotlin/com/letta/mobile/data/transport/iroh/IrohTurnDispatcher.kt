package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.TurnRuntimeKey
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

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
    private val currentGeneration get() = dependencies.currentGeneration

    /**
     * False when [submission]'s conversation already has a turn. That is not a
     * failure: the send coordinator queues the message behind the running turn, so no error or
     * failed terminal is published for it.
     */
    fun submit(submission: IrohTurnSubmission): Boolean {
        val turn = admit(submission) ?: return false
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
            "conversationId" attr conversationId,
            "activeTurnId" attr result.activeTurn.turnId,
            "rejectedTurnId" attr request.token.turnId.value,
        )
    }

    private fun reportConcurrentTurns(turn: IrohActiveTurn) {
        val concurrent = registry.concurrentTurns(turn.token.conversationId)
        if (concurrent.isEmpty()) return
        Telemetry.event(
            "IrohTransport", "turn.concurrent_start", "conversationId" attr turn.conversationId,
            "turnId" attr turn.turnId,
            "concurrentConversations" attr concurrent.joinToString(",") { it.conversationId },
            "concurrentTurnIds" attr concurrent.joinToString(",") { it.turnId },
        )
    }

    private fun track(job: Job, turn: IrohActiveTurn) {
        turn.job = job
        registry.registerSendJob(IrohSendJobRegistration(turn.token.conversationId, job))
        job.invokeOnCompletion {
            val removed = registry.finish(turn.token)
            if (removed && !turn.hasTerminal) {
                Telemetry.event(
                    "IrohTransport",
                    "turn.abandoned_nonterminal",
                    "conversationId" attr turn.conversationId,
                    "turnId" attr turn.turnId,
                    "runId" attr turn.runId,
                )
            } else if (!removed) {
                Telemetry.event(
                    "IrohTransport",
                    "turn.completion_after_eviction",
                    "conversationId" attr turn.conversationId,
                    "turnId" attr turn.turnId,
                    "hasTerminal" attr turn.hasTerminal,
                    "currentTurnId" attr (registry.getActiveTurn(turn.token.conversationId)?.turnId ?: ""),
                )
            }
            registry.unregisterSendJob(IrohSendJobRegistration(turn.token.conversationId, job))
        }
    }

    private suspend fun dispatch(request: IrohTurnDispatch) {
        Telemetry.event("IrohTrace", "transport.send.job_start", "turnId" attr request.turn.turnId, "runId" attr request.turn.runId)
        val handle = readyHandle(request) ?: return
        val engine = handle.turnEngine ?: error("Iroh send requested without turn engine")
        // letta-mobile-1n5py: the registry retires a turn as its terminal is published, a moment
        // before the engine releases that turn's lease. A queued send drained on that terminal
        // must wait out the release, not fail as busy.
        if (!awaitEngineRelease(engine, request)) {
            reportBusyTurn(request, engine)
            return
        }
        emitStarted(request)
        collectTurn(request, handle, engine)
    }

    private suspend fun awaitEngineRelease(engine: AppServerTurnEngine, request: IrohTurnDispatch): Boolean =
        withTimeoutOrNull(ENGINE_RELEASE_GRACE_MS.milliseconds) {
            while (engine.isBusy(request.agentId, request.conversationId)) delay(ENGINE_RELEASE_POLL_MS.milliseconds)
            true
        } ?: false

    private suspend fun readyHandle(request: IrohTurnDispatch): IrohConnectionHandle? =
        runCatching { ready() }.getOrElse { error ->
            Telemetry.event(
                "IrohTransport", "turn.ready_failed",
                "error" attr (error.message ?: error.toString()),
                "class" attr error::class.simpleName,
            )
            emitFailure(request, "iroh_connection_not_ready", error.message ?: error.toString())
            null
        }

    private suspend fun reportBusyTurn(request: IrohTurnDispatch, engine: AppServerTurnEngine) {
        val owner = engine.activeTurnOwnerFor(TurnRuntimeKey(request.agentId, request.conversationId))
        val ownerAcquiredAtMs = owner?.acquiredAtMs
        Telemetry.event(
            "IrohTransport", "turn.busy",
            "turnId" attr request.turn.turnId,
            "runId" attr request.turn.runId,
            "sendAgentId" attr request.agentId,
            "sendConversationId" attr request.conversationId,
            "sendOtid" attr request.input.localMessageId,
            "ownerRunId" attr owner?.runId,
            "ownerRuntimeId" attr owner?.runtimeId,
            "ownerAgentId" attr owner?.agentId,
            "ownerConversationId" attr owner?.conversationId,
            "ownerAcquiredAtMs" attr ownerAcquiredAtMs,
            "ownerHeldForMs" attr ownerAcquiredAtMs?.let { System.currentTimeMillis() - it },
            "ownerLastTerminal" attr owner?.lastTerminal,
            "ownerLastTerminalSource" attr owner?.lastTerminalSource,
            "ownerLastTerminalAtMs" attr owner?.lastTerminalAtMs,
            "ownerLastTerminalSeq" attr owner?.lastTerminalSeq,
            "ownerLastTerminalScopeMatched" attr owner?.lastTerminalScopeMatched,
            "ownerSettleDeadlineMs" attr owner?.settleDeadlineMs,
            "ownerWatchdogDeadlineMs" attr owner?.watchdogDeadlineMs,
            "ownerProcessRole" attr owner?.processRole,
            "ownerReleaseReason" attr owner?.releaseReason,
            "otherBusyKeys" attr engine.busyRuntimeKeys()
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
            Telemetry.event("IrohTransport", "turn.cancelled", "turnId" attr request.turn.turnId, "runId" attr request.turn.runId)
            return
        }
        Telemetry.event(
            "IrohTransport", "turn.failed",
            "error" attr (error.message ?: error.toString()),
            "class" attr error::class.simpleName,
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

private const val ENGINE_RELEASE_GRACE_MS = 2_000L
private const val ENGINE_RELEASE_POLL_MS = 20L

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
