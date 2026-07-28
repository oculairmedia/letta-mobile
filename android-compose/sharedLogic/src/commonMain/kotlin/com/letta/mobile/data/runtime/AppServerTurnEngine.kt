package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerExternalToolResult
import com.letta.mobile.data.transport.appserver.AppServerExternalToolResultContent
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeUserInputTools
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.runtime.RunId
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
/**
 * TurnEngine backed by one App Server client/control owner.
 *
 * This class serializes turns per engine instance and caches a started runtime
 * only for the same agent/conversation pair. Hosts that share one App Server
 * process across several UI clients still need an external fanout controller.
 */
class AppServerTurnEngine(
    private val client: AppServerClient,
    private val mapper: AppServerRuntimeEventMapper = AppServerRuntimeEventMapper(),
    private val clientInfo: AppServerRuntimeStartClientInfo = AppServerRuntimeStartClientInfo(
        name = "letta-mobile",
        version = "0.1",
    ),
    private val permissionMode: AppServerPermissionMode = AppServerPermissionMode.Standard,
    private val permissionModeProvider: (TurnCommand) -> AppServerPermissionMode = { permissionMode },
    private val requestIdFactory: () -> String = ::defaultRequestId,
    /**
     * Idle-liveness window (ms). If NO event frame for the current turn arrives
     * within this window, the turn is force-completed with a Failed lifecycle so
     * the engine's single-turn lock is released and subsequent sends are not
     * permanently jammed. This is progress-based (reset on every matching frame),
     * NOT a total-duration cap — a long but actively-streaming turn is fine.
     * Guards the c0qm0 jam: a real App Server that never emits a terminal
     * stop_reason would otherwise block client.events.collect forever, leaving
     * activeTurn locked so every later send() silently no-ops ("Thinking..." hang).
     */
    private val turnIdleTimeoutMs: Long = DEFAULT_TURN_IDLE_TIMEOUT_MS,
    private val terminalSettleQuietMs: Long = DEFAULT_TERMINAL_SETTLE_QUIET_MS,
    private val turnContextPreflight: TurnContextPreflight = TurnContextPreflight.None,
    /**
     * lgns8.17: controller-owned external tools. letta-code's App-Server (WS)
     * route does NOT self-execute tool calls — it emits external_tool_call_request
     * and BLOCKS the turn until it receives a matched external_tool_call_response
     * (matched by request_id; content irrelevant). If a request goes unanswered
     * the turn hangs until the idle watchdog force-fails it. This registry (when
     * wired) executes controller-owned tools; either way the engine GUARANTEES a
     * matched response for every request (a synthesized is_error response when no
     * handler exists), which is the machinery lettashim used to provide. Null =
     * no controller tools, so every request still gets a benign error response.
     */
    private val externalToolRegistry: ExternalToolRegistry? = null,
) : TurnEngine {
    /** Owner-token lease — never force-unlocked by a competing send (lgns8.22.2). */
    private val activeLeaseRef = atomic<TurnLease?>(null)
    private val leaseTokenSeq = atomic(0L)
    private var runtime: AppServerRuntimeScope? = null

    /**
     * Drops the cached runtime scope so the next turn re-issues runtime_start.
     * Called on transport disconnect/generation rollover (lgns8.5): a scope
     * minted by a dead generation must never be reused against the next one.
     */
    fun invalidateRuntime() {
        runtime = null
    }

    /**
     * lgns8.17: answer an external_tool_call_request so the App Server unblocks
     * the turn. Executes the tool via the wired [externalToolRegistry] when it
     * advertises it; otherwise (no registry, unknown tool, or a thrown handler)
     * synthesizes a matched is_error response. Matching is by request_id — the
     * ONLY correlation key the App Server uses (the response carries request_id,
     * not tool_call_id). Fire-and-forget one-way send: any send failure is logged,
     * never rethrown, so it can't break the turn's event collector. If the
     * connection has since dropped the send is lost, but the App Server re-emits
     * the still-blocking request on reconnect/sync, so the next collect re-answers.
     */
    private suspend fun guaranteeExternalToolResponse(request: AppServerInboundFrame.ExternalToolCallRequest) {
        val result: AppServerExternalToolResult = try {
            when (val outcome = externalToolRegistry?.invoke(request.toolName, request.input)) {
                is ExternalToolResult.Success -> toolResult(outcome.content, isError = false)
                is ExternalToolResult.Error -> toolResult(outcome.error, isError = true)
                null -> toolResult(
                    "external tool '${request.toolName}' is not handled by this controller",
                    isError = true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toolResult(
                "external tool '${request.toolName}' failed: ${e.message ?: e::class.simpleName}",
                isError = true,
            )
        }
        Telemetry.event(
            "AppServerTurnEngine", "externalTool.responded",
            "requestId" to request.requestId,
            "toolCallId" to request.toolCallId,
            "toolName" to request.toolName,
            "isError" to (result.isError == true).toString(),
            "handled" to (externalToolRegistry != null).toString(),
        )
        runCatching {
            client.sendExternalToolResponse(
                AppServerCommand.ExternalToolCallResponse(requestId = request.requestId, result = result),
            )
        }.onFailure { Telemetry.error("AppServerTurnEngine", "externalTool.responseSendFailed", it) }
    }

    private fun toolResult(text: String, isError: Boolean) = AppServerExternalToolResult(
        content = listOf(AppServerExternalToolResultContent(type = "text", text = text)),
        isError = isError,
    )

    /**
     * letta-mobile-kyqdt: TELEMETRY snapshot of who owns the active lease.
     * Derived from [activeLeaseRef] — never force-unlocks exclusion.
     */
    private val activeTurnOwnerRef = atomic<ActiveTurnOwner?>(null)

    /**
     * letta-mobile-vilsn: tool_call_id -> real approval id (the can_use_tool
     * control-request request_id, e.g. `perm-call_…`) for surfaced runtime
     * user-input approvals. Populated when the approval is emitted; consumed by
     * [userInputApprovalId] when the client submits the answer, so the
     * ApprovalResponse targets the gate letta-code actually parked on (the id is
     * NOT derivable from the tool_call_id — `call_…` vs `toolu_…`).
     */
    private val userInputApprovalIdsRef = atomic<Map<String, String>>(emptyMap())

    /**
     * READ the recorded real approval id for [toolCallId] without removing it, or
     * null if none was recorded (non-interactive tool, or already consumed).
     *
     * Deliberately not consume-on-read. If `client.input` fails on a transient
     * disconnect, the id must survive so the user's retry still targets the real
     * gate; dropping it here would fall back to `perm-<toolCallId>`, which is
     * invalid for providers whose ids look like `toolu_…`, leaving the question
     * permanently unanswerable. [clearUserInputApprovalId] removes it only after
     * the response is actually sent.
     */
    fun userInputApprovalId(toolCallId: String): String? = userInputApprovalIdsRef.value[toolCallId]

    fun clearUserInputApprovalId(toolCallId: String, requestId: String) {
        userInputApprovalIdsRef.update { current ->
            if (current[toolCallId] == requestId) current - toolCallId else current
        }
    }

    /**
     * Pure read accessor for the current active-turn owner (telemetry).
     * Null when idle.
     */
    val activeTurnOwner: ActiveTurnOwner? get() = activeTurnOwnerRef.value

    /**
     * true when a turn lease is held (Preparing through Streaming/Retiring).
     */
    val isBusy: Boolean
        get() {
            val lease = activeLeaseRef.value ?: return false
            return lease.phase != TurnLeasePhase.Terminal
        }

    /**
     * Sends an `abort_message` for the active runtime so the server tears down
     * the in-flight run and emits its own terminal frame. Returns null when no
     * runtime has been started yet (nothing to abort). [runId] should be the
     * canonical (promoted) run id of the turn being cancelled; a null run id asks
     * the server to abort whatever run is currently active for the runtime.
     */
    suspend fun abort(runId: String?): AppServerInboundFrame.AbortMessageResponse? {
        val scope = runtime ?: return null
        return client.abort(
            AppServerCommand.AbortMessage(
                runtime = scope,
                requestId = requestIdFactory(),
                runId = runId,
            ),
        )
    }

    /**
     * letta-mobile-c4igq.3 / lgns8.22.2: causal liveness recovery.
     * Clears a dead owner ONLY after authoritative evidence, and only by
     * cancelling+joining that owner's job — never by Mutex.force-unlock.
     * Preparing/Starting leases without a run_id are locally alive: idle
     * run.list must not steal them.
     */
    private suspend fun reconcileOwnerLivenessAndMaybeRelease(): Boolean {
        val ownerLease = activeLeaseRef.value ?: return false
        if (ownerLease.phase == TurnLeasePhase.Retiring || ownerLease.phase == TurnLeasePhase.Terminal) {
            return false
        }
        val runId = ownerLease.runId?.takeIf { it.isNotBlank() }
            ?: activeTurnOwnerRef.value?.runId?.takeIf { it.isNotBlank() }
        val dead = try {
            withTimeout(LIVENESS_PROBE_TIMEOUT_MS.milliseconds) {
                when {
                    runId != null -> probeRunDead(runId)
                    ownerLease.isLocallyAliveWithoutRun -> {
                        // Preflight / starting — do not treat empty run.list as death.
                        false
                    }
                    else -> conversationHasNoActiveRun(ownerLease.agentId, ownerLease.conversationId)
                }
            }
        } catch (t: TimeoutCancellationException) {
            Telemetry.event(
                "AppServerTurnEngine",
                "activeTurn.reconcileLivenessTimedOut",
                "runId" to (runId ?: "<none>"),
                "timeoutMs" to LIVENESS_PROBE_TIMEOUT_MS,
                level = Telemetry.Level.WARN,
            )
            return false
        } catch (t: Throwable) {
            Telemetry.error("AppServerTurnEngine", "activeTurn.reconcileLivenessFailed", t, "runId" to (runId ?: "<none>"))
            return false
        }
        if (!dead) {
            Telemetry.event("AppServerTurnEngine", "activeTurn.reconciledAlive", "runId" to (runId ?: "<none>"))
            return false
        }
        return releaseDeadOwnerLease(ownerLease, runId)
    }

    private suspend fun releaseDeadOwnerLease(owner: TurnLease, runId: String?): Boolean {
        val retiring = owner.copy(phase = TurnLeasePhase.Retiring)
        if (!activeLeaseRef.compareAndSet(owner, retiring)) return false
        // Cancel and join the owning structured scope before admitting a successor.
        runCatching { owner.ownerJob?.cancelAndJoin() }
        // Owner's finally may already have cleared the retiring lease via token match.
        activeLeaseRef.update { cur ->
            when {
                cur == null -> null
                cur.token == owner.token -> null
                else -> cur // successor already installed
            }
        }
        activeTurnOwnerRef.update { telemetry ->
            if (telemetry == null) null
            else if (
                telemetry.runtimeId == owner.runtimeId &&
                    telemetry.conversationId == owner.conversationId &&
                    telemetry.acquiredAtMs == owner.acquiredAtMs
            ) {
                null
            } else {
                telemetry
            }
        }
        val admitted = activeLeaseRef.value?.token != owner.token
        Telemetry.event(
            "AppServerTurnEngine", "activeTurn.reconciledDead",
            "runId" to (runId ?: "<none>"),
            "agentId" to (owner.agentId ?: ""),
            "conversationId" to (owner.conversationId ?: ""),
            "leaseToken" to owner.token,
            "reason" to if (runId != null) "run_provably_dead" else "conversation_has_no_active_run",
        )
        return admitted
    }

    private suspend fun probeRunDead(runId: String): Boolean {
        val resp = client.adminRpc(
            AppServerCommand.AdminRpc(
                requestId = requestIdFactory(),
                method = "run.get",
                params = buildJsonObject { put("run_id", runId) },
            ),
        )
        return when {
            resp.success -> runResultIsDead(resp.result)
            resp.error?.let {
                it.contains("not found", ignoreCase = true) ||
                    it.contains("no such run", ignoreCase = true)
            } == true -> true
            else -> false
        }
    }

    /** True iff a run.get result body proves the run is terminal/dead. */
    private fun runResultIsDead(result: JsonElement?): Boolean = runObjIsTerminal(result as? JsonObject)

    /** True iff a single run JSON object shows a terminal/dead run. */
    private fun runObjIsTerminal(obj: JsonObject?): Boolean {
        if (obj == null) return false
        val status = obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase()
        if (status != null && (status == "completed" || status == "failed" || status == "cancelled" || status == "error" || status == "expired")) return true
        // completed_at set is also terminal.
        if (obj["completed_at"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) return true
        return false
    }

    /**
     * Conversation-scoped liveness probe used when the stuck-lock owner has no
     * run_id. Queries run.list (server returns the run set) and returns true ONLY
     * when it can prove there is no NON-terminal run for this agent+conversation.
     * Any inconclusive result (unsuccessful call, unparseable body) returns false
     * so a genuinely live run is never interrupted.
     */
    private suspend fun conversationHasNoActiveRun(agentId: String?, conversationId: String?): Boolean {
        if (agentId.isNullOrBlank() && conversationId.isNullOrBlank()) return false
        val resp = client.adminRpc(
            AppServerCommand.AdminRpc(requestId = requestIdFactory(), method = "run.list"),
        )
        if (!resp.success) return false
        val runs = runListArray(resp.result) ?: return false
        // Match this owner's runs: prefer conversation id (always present in
        // /v1/runs); a run is "active" if it exists for this conversation and is
        // not terminal.
        val hasActive = runs.any { element ->
            val obj = element as? JsonObject ?: return@any false
            val runConv = obj["conversation_id"]?.jsonPrimitive?.contentOrNull
                ?: obj["conversationId"]?.jsonPrimitive?.contentOrNull
            val runAgent = obj["agent_id"]?.jsonPrimitive?.contentOrNull
                ?: obj["agentId"]?.jsonPrimitive?.contentOrNull
            val matchesOwner = when {
                !conversationId.isNullOrBlank() && runConv != null -> runConv == conversationId
                !agentId.isNullOrBlank() && runAgent != null -> runAgent == agentId
                else -> false
            }
            matchesOwner && !runObjIsTerminal(obj)
        }
        return !hasActive
    }

    /** Extract the runs array from a run.list result (bare array or {runs|data:[…]}). */
    private fun runListArray(result: JsonElement?): JsonArray? = when (result) {
        is JsonArray -> result
        is JsonObject -> (result["runs"] ?: result["data"])?.let { it as? JsonArray }
        else -> null
    }

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = channelFlow {
        val acquiredAtMs = currentTimeMs()
        val ownerProcessRole = permissionModeProvider(command).name
        val leaseToken = leaseTokenSeq.incrementAndGet()
        var lease = TurnLease(
            token = leaseToken,
            runtimeId = command.runtimeId.value,
            agentId = command.agentId.value,
            conversationId = command.conversationId.value,
            acquiredAtMs = acquiredAtMs,
            phase = TurnLeasePhase.Preparing,
            ownerJob = coroutineContext[Job],
            processRole = ownerProcessRole,
            settleDeadlineMs = terminalSettleQuietMs,
            watchdogDeadlineMs = turnIdleTimeoutMs,
        )
        if (!activeLeaseRef.compareAndSet(null, lease)) {
            if (reconcileOwnerLivenessAndMaybeRelease() && activeLeaseRef.compareAndSet(null, lease)) {
                // Reconciled a dead owner; acquired successor lease.
            } else {
                throw IllegalStateException("An App Server turn is already active for ${command.runtimeId.value}.")
            }
        }
        // Re-bind ownerJob after CAS in case a recovery path raced.
        activeLeaseRef.update { cur ->
            if (cur?.token == leaseToken) cur.copy(ownerJob = coroutineContext[Job]) else cur
        }
        lease = activeLeaseRef.value?.takeIf { it.token == leaseToken } ?: lease

        activeTurnOwnerRef.value = ActiveTurnOwner(
            runId = null,
            runtimeId = command.runtimeId.value,
            agentId = command.agentId.value,
            conversationId = command.conversationId.value,
            acquiredAtMs = acquiredAtMs,
            lastTerminal = null,
            processRole = ownerProcessRole,
            settleDeadlineMs = terminalSettleQuietMs,
            watchdogDeadlineMs = turnIdleTimeoutMs,
        )
        Telemetry.event(
            "AppServerTurnEngine", "activeTurn.acquired",
            "runtimeId" to command.runtimeId.value,
            "agentId" to command.agentId.value,
            "conversationId" to command.conversationId.value,
            "acquiredAtMs" to acquiredAtMs,
            "processRole" to ownerProcessRole,
            "leaseToken" to leaseToken,
            "settleDeadlineMs" to terminalSettleQuietMs,
            "watchdogDeadlineMs" to turnIdleTimeoutMs,
        )

        var collector: Job? = null
        var releaseReason = "normal_completion"
        try {
            val turnPermissionMode = permissionModeProvider(command)
            prepareContextIfNeeded(command)
            activeLeaseRef.update { cur ->
                if (cur?.token == leaseToken) cur.copy(phase = TurnLeasePhase.Starting) else cur
            }
            Telemetry.event("IrohTurn", "ensureRuntime.begin", "agent" to command.agentId.value)
            val scope = ensureRuntime(command, turnPermissionMode)
            Telemetry.event("IrohTurn", "ensureRuntime.ok", "scopeAgent" to scope.agentId, "scopeConv" to scope.conversationId)
            send(command.startedDraft())

            val collectorReady = CompletableDeferred<Unit>()
            collector = launch {
                try {
                    activeLeaseRef.update { cur ->
                        if (cur?.token == leaseToken) cur.copy(phase = TurnLeasePhase.Streaming) else cur
                    }
                    collectTurnWithIdleWatchdog(
                        scope,
                        command,
                        turnPermissionMode,
                        collectorReady,
                        leaseToken,
                    ) { draft -> send(draft) }
                } catch (completed: TurnCompletedMarker) {
                    releaseReason = "normal_completion"
                } catch (idle: TurnIdleTimedOutMarker) {
                    releaseReason = "watchdog_timeout"
                    Telemetry.event(
                        "IrohTurn", "turn.idle_timeout", "agent" to command.agentId.value, "idleMs" to turnIdleTimeoutMs,
                    )
                    noteOwnerTerminal(RuntimeRunStatus.Failed, source = "idle_timeout", leaseToken = leaseToken)
                    send(command.failedDraft("App Server turn idle for ${turnIdleTimeoutMs}ms (no terminal stop_reason)"))
                } catch (cancellation: CancellationException) {
                    releaseReason = "cancellation"
                    throw cancellation
                } catch (error: Throwable) {
                    releaseReason = "stream_error"
                    throw error
                }
            }
            collectorReady.await()
            client.input(command.toInputCommand(scope))
            Telemetry.event("IrohTurn", "input.sent")
            collector.join()
        } finally {
            withContext(NonCancellable) {
                collector?.cancelAndJoin()
                // Token-validated release: a successor lease is never cleared by us.
                val releasedOwner = activeTurnOwnerRef.value
                val releasedLease = activeLeaseRef.value
                if (releasedLease?.token == leaseToken) {
                    activeLeaseRef.compareAndSet(releasedLease, null)
                    activeTurnOwnerRef.compareAndSet(releasedOwner, null)
                }
                Telemetry.event(
                    "AppServerTurnEngine", "activeTurn.released",
                    "runtimeId" to command.runtimeId.value,
                    "agentId" to command.agentId.value,
                    "conversationId" to command.conversationId.value,
                    "leaseToken" to leaseToken,
                    "acquiredAtMs" to (releasedOwner?.acquiredAtMs),
                    "heldMs" to (releasedOwner?.acquiredAtMs?.let { currentTimeMs() - it }),
                    "lastTerminal" to (releasedOwner?.lastTerminal),
                    "lastTerminalSource" to (releasedOwner?.lastTerminalSource),
                    "lastTerminalAtMs" to (releasedOwner?.lastTerminalAtMs),
                    "lastTerminalSeq" to (releasedOwner?.lastTerminalSeq),
                    "lastTerminalScopeMatched" to (releasedOwner?.lastTerminalScopeMatched),
                    "settleDeadlineMs" to (releasedOwner?.settleDeadlineMs),
                    "watchdogDeadlineMs" to (releasedOwner?.watchdogDeadlineMs),
                    "processRole" to (releasedOwner?.processRole),
                    "releaseReason" to releaseReason,
                )
            }
        }
    }

    private suspend fun prepareContextIfNeeded(command: TurnCommand) {
        if (command.input !is TurnInput.UserMessage) return
        val result = runPreflightOrInvalidate(command) ?: return
        if (!result.configuredContextLimit && !result.compacted) return

        invalidateRuntime()
        Telemetry.event(
            "AppServerTurnEngine",
            "context.preflightApplied",
            "agentId" to command.agentId.value,
            "conversationId" to command.conversationId.value,
            "configuredContextLimit" to result.configuredContextLimit.toString(),
            "compacted" to result.compacted.toString(),
        )
    }

    private suspend fun runPreflightOrInvalidate(command: TurnCommand): TurnContextPreflightResult? {
        return try {
            // Bound the whole preflight while activeTurn is held — individual
            // RPCs have their own timeouts, but the sum must not wedge the engine.
            withTimeout(PREFLIGHT_TIMEOUT_MS.milliseconds) {
                turnContextPreflight.prepare(
                    agentId = command.agentId.value,
                    conversationId = command.conversationId.value,
                )
            }
        } catch (e: TimeoutCancellationException) {
            // withTimeout wraps expiry as TimeoutCancellationException (a
            // CancellationException subclass). Treat it as a failed preflight —
            // partial mutations (e.g. persisted context limit) must not leave a
            // stale runtime cached — while still propagating genuine parent cancel.
            recordPreflightFailure(command, "TimeoutCancellationException")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Preflight may have already mutated agent/conversation state (e.g.
            // persisted a context limit) before failing on message list/compact.
            // Drop any cached runtime so the next turn reseeds from the update.
            recordPreflightFailure(command, e::class.simpleName ?: "Exception")
            throw e
        }
    }

    private fun recordPreflightFailure(command: TurnCommand, errorClass: String) {
        invalidateRuntime()
        Telemetry.event(
            "AppServerTurnEngine",
            "context.preflightFailed",
            "agentId" to command.agentId.value,
            "conversationId" to command.conversationId.value,
            "errorClass" to errorClass,
        )
    }

    /**
     * Collects turn events, resetting a [turnIdleTimeoutMs] watchdog on every
     * matching frame. A parallel watchdog job throws [TurnIdleTimedOut] (by
     * cancelling the collect scope) if the connection is silent for longer than
     * the window. Throws [TurnCompleted] on a terminal lifecycle frame.
     *
     * The watchdog runs CONCURRENTLY so a fully-silent turn (no frames at all)
     * still trips — checking only inside `collect` would never fire during
     * silence, which is exactly the c0qm0 hang.
     */
    private suspend fun collectTurnWithIdleWatchdog(
        scope: AppServerRuntimeScope,
        command: TurnCommand,
        turnPermissionMode: AppServerPermissionMode,
        collectorReady: CompletableDeferred<Unit>,
        leaseToken: Long,
        emitDraft: suspend (RuntimeEventDraft) -> Unit,
    ) = coroutineScope {
        val lastFrameAt = atomic(currentTimeMs())
        // letta-mobile-vilsn.6: the idle watchdog is PAUSED while ANY surfaced
        // (non-auto-approved) runtime user-input approval gate is outstanding
        // (AskUserQuestion / ExitPlanMode parked awaiting the user's answer). An
        // unanswered question legitimately parks the turn far longer than the idle
        // window, so the watchdog MUST NOT fail it. The outstanding gates are the
        // keys of [userInputApprovalIdsRef] (keyed by tool_call_id): a gate is
        // ADDED when the approval is surfaced (below) and cleared ONLY when THAT
        // specific gate is genuinely resolved — the submit path consumes it via
        // [clearUserInputApprovalId], a matching tool_return is observed, or a
        // terminal/settle path clears everything. Crucially it is NOT lifted by an
        // arbitrary inbound frame: a side-channel status frame (UpdateDeviceStatus /
        // UpdateQueue / UpdateSubagentState) that merely passes matches(scope) must
        // never resume the watchdog while the user still owes an answer.
        fun hasOutstandingUserInputGate(): Boolean = userInputApprovalIdsRef.value.isNotEmpty()
        val watchdog = this.launch {
            // Short recheck cadence while paused so the watchdog resumes PROMPTLY
            // once the last gate clears (from ANY source — collect-loop tool_return,
            // the submit path's consume, or a terminal), never sleeping a full stale
            // window. Re-stamp lastFrameAt on each paused tick so a resumed watchdog
            // starts a FULL idle window instead of instantly firing on a stale
            // timestamp left over from when the approval was surfaced.
            val pauseRecheckMs = minOf(turnIdleTimeoutMs, WATCHDOG_PAUSE_RECHECK_MS)
            while (true) {
                if (hasOutstandingUserInputGate()) {
                    lastFrameAt.value = currentTimeMs()
                    delay(pauseRecheckMs.milliseconds)
                    continue
                }
                val idleFor = currentTimeMs() - lastFrameAt.value
                val remaining = turnIdleTimeoutMs - idleFor
                if (remaining <= 0) {
                    throw TurnIdleTimedOut
                }
                delay(remaining.milliseconds)
            }
        }
        var pendingCompleted: RuntimeEventDraft? = null
        var pendingStop: RuntimeEventDraft? = null
        var pendingUsage: RuntimeEventDraft? = null
        var terminalSettleJob: Job? = null
        // letta-mobile-kyqdt: once a completed lifecycle is observed and the
        // settle timer is armed, it must NOT be re-armed by subsequent frames.
        // The prior code cancel+rescheduled the quiet window on EVERY later
        // matching frame, so on the shared server-side engine a steady trickle
        // of matching frames (cross-device viewer traffic / late fanout deltas)
        // deferred the completed terminal — and the activeTurn unlock that fires
        // with it — indefinitely, leaving the run "busy" long after it was
        // terminal and rejecting the next cross-device send. Arm-once makes the
        // terminal release bounded and monotonic: the completed run always
        // frees busy ownership within terminalSettleQuietMs of the completion.
        var terminalArmed = false
        var speculativeCompletionArmed = false
        var sawToolReturn = false
        var sawAssistantAfterToolReturn = false
        // letta-mobile-kyqdt: TELEMETRY-ONLY. Seq of the frame that produced
        // the pending completed terminal, so the delayed settle can record it.
        var pendingCompletedSeq: Long? = null
        
        // letta-mobile-oqfbj: track emitted and returned tool_call_ids for settlement
        val emittedToolCallIds = mutableSetOf<String>()
        val returnedToolCallIds = mutableSetOf<String>()

        suspend fun flushTail() {
            // letta-mobile-vilsn.6: a terminal/settle path is a definitive end to
            // ANY parked user-input approval gate — clear every outstanding gate so
            // the watchdog resumes normal behavior and no stale gate leaks into a
            // later turn.
            userInputApprovalIdsRef.update { emptyMap() }
            pendingStop?.let { emitDraft(it) }
            pendingStop = null
            pendingUsage?.let { emitDraft(it) }
            pendingUsage = null
        }

        // letta-mobile-kyqdt: arm the completed-terminal quiet timer AT MOST ONCE.
        // Formerly this cancelled + rescheduled the settle job on every later
        // matching frame, so any post-completion frame trickle deferred the
        // terminal (and the activeTurn unlock) without bound. Arming once anchors
        // the settle deadline to the first observed completion, so the terminal —
        // and busy release — always fires within terminalSettleQuietMs. Later
        // frames are still emitted downstream (below); they simply cannot push
        // the terminal out. The settle body reads pendingCompleted at fire time,
        // so a completion refined by an intervening frame still uses the latest
        // terminal draft, just on the original, bounded deadline.
        // letta-mobile-c4igq.6: the post-tool usage-tail completion is SPECULATIVE —
        // the turn may still continue into another tool round. If genuine activity
        // arrives after arming (a new tool_call / assistant / tool_return), cancel
        // the pending speculative completion and allow re-arming on the next
        // post-tool usage tail. Real stop_reason / terminal-lifecycle frames still
        // complete the turn via their own branches; this only unwinds a SPECULATIVE
        // arm, never a real terminal. No-op when nothing is armed speculatively.
        fun cancelSpeculativeCompletion() {
            if (!speculativeCompletionArmed) return
            terminalSettleJob?.cancel()
            terminalSettleJob = null
            terminalArmed = false
            speculativeCompletionArmed = false
            pendingCompleted = null
            pendingCompletedSeq = null
        }

        fun armCompletedTerminalOnce() {
            if (terminalArmed) return
            if (pendingCompleted == null) return
            terminalArmed = true
            terminalSettleJob = launch {
                delay(terminalSettleQuietMs.milliseconds)
                val terminal = pendingCompleted ?: return@launch
                // letta-mobile-oqfbj / fix(no-settle-on-clean-completion): do NOT
                // synthesize Failed returns here. This is a CLEAN Completed
                // terminal — with async/parallel tool execution a second tool's
                // real return can legitimately arrive after this quiet window.
                // See settleDanglingToolCalls() KDoc for the full rationale.
                flushTail()
                // letta-mobile-kyqdt: telemetry-only. This terminal was accepted
                // by matches(scope) (it reached the collect body); record the
                // decision as passed along with its source + seq.
                noteOwnerTerminal(
                    RuntimeRunStatus.Completed,
                    source = "completed_settle",
                    seq = pendingCompletedSeq,
                    scopeMatched = true,
                    leaseToken = leaseToken,
                )
                emitDraft(terminal)
                throw TurnCompleted
            }
        }

        var turnEndReason: String? = null
        try {
            collectorReady.complete(Unit)
            client.events.collect { received ->
                if (!received.matches(scope)) {
                    // letta-mobile-kyqdt: P1c KEY PROBE (TELEMETRY-ONLY). A frame
                    // was rejected by the scope filter. If it CARRIED a terminal
                    // (stop_reason / terminal lifecycle), record the rejected
                    // scope decision so the owner metadata proves the leading
                    // hypothesis: "a terminal arrived but failed matches(scope)".
                    // Pure read/write — the control-flow return below is
                    // unchanged; we do NOT gate on this record.
                    if (received.carriesTerminal()) {
                        noteOwnerScopeDecision(
                            scopeMatched = false,
                            source = "scope_rejected_terminal",
                            seq = received.eventSeqOrNull(),
                            leaseToken = leaseToken,
                        )
                        Telemetry.event(
                            "AppServerTurnEngine", "terminal.scope_rejected",
                            "expectedAgent" to scope.agentId,
                            "expectedConv" to scope.conversationId,
                            "frameAgent" to received.frame.runtime?.agentId,
                            "frameConv" to received.frame.runtime?.conversationId,
                            "eventSeq" to received.eventSeqOrNull(),
                        )
                        // letta-mobile-kyqdt STEP 2: AUTHORITATIVE TERMINAL RELEASE.
                        // If the rejected terminal-bearing frame is for the SAME
                        // conversation, release the engine on the authoritative
                        // terminal — no settle-window, no scope-match requirement.
                        // Closes the passive-observer stuck-for-5-min gap.
                        if (received.frame.runtime?.conversationId == scope.conversationId) {
                            noteOwnerTerminal(
                                RuntimeRunStatus.Completed,
                                source = "authoritative_terminal_scope_mismatched",
                                seq = received.eventSeqOrNull(),
                                scopeMatched = false,
                                leaseToken = leaseToken,
                            )
                            throw TurnCompleted
                        }
                    }
                    return@collect
                }
                lastFrameAt.value = currentTimeMs()
                // letta-mobile-vilsn.6: the idle-watchdog pause is intentionally NOT
                // lifted here. An arbitrary inbound frame that merely passes
                // matches(scope) — including a side-channel status frame
                // (UpdateDeviceStatus / UpdateQueue / UpdateSubagentState) — must NOT
                // resume the watchdog while a user-input gate is still outstanding.
                // A gate is lifted only when THAT gate is genuinely resolved (its
                // tool_return below, the submit path's consume, or a terminal/settle
                // path), so an unanswered question can never be force-failed by a
                // stray frame, and a real answer can never leave the watchdog wedged.
                // lgns8.17: GUARANTEE a matched external_tool_call_response. The
                // App Server blocks the turn until every external_tool_call_request
                // is answered by request_id; the mapper below only turns it into a
                // UI ToolCallObserved draft and never replies, so an unanswered
                // request hangs the turn (0 deltas until the idle watchdog fails
                // it). Reply here — this is the one place the raw frame still
                // carries request_id (toToolCallDraft discards it) and the client
                // is in scope. Runs BEFORE the mapper so the UI draft is unchanged.
                (received.frame as? AppServerInboundFrame.ExternalToolCallRequest)
                    ?.let { guaranteeExternalToolResponse(it) }
                // letta-mobile-kyqdt: P1b RUN-ID PROMOTION (TELEMETRY-ONLY).
                // Once the mapper reveals the server run id for this active turn,
                // promote it into the owner via a pure copy(runId=…). This is the
                // same place the engine learns the real run id (frames carry
                // run_id → draft.runId); we do not alter that promotion flow.
                val frameSeq = received.eventSeqOrNull()
                val drafts = mapper.map(command, received)
                drafts.firstOrNull { it.runId != null }?.runId?.value?.let { promoteOwnerRunId(it, leaseToken) }
                drafts.forEach { draft ->
                    val autoApproved = autoApprovedToolCallDraft(scope, turnPermissionMode, command, draft)
                    if (autoApproved != null) {
                        // letta-mobile toolchip-live: auto-approving must not
                        // swallow the tool-call announcement. Over Iroh the
                        // approval_request_message IS the tool call frame; the
                        // shim path still renders a tool card when it
                        // auto-allows, so emit a ToolCallObserved draft here
                        // (suppressing only the approval CARD, not the call).
                        emittedToolCallIds.add(autoApproved.toolCallId.value)
                        emitDraft(
                            command.draftFor(
                                runId = draft.runId,
                                payload = autoApproved,
                            ),
                        )
                        armCompletedTerminalOnce()
                        return@forEach
                    }
                    
                    // letta-mobile-oqfbj: track tool_call emissions and returns
                    when (val payload = draft.payload) {
                        is RuntimeEventPayload.ToolCallObserved -> emittedToolCallIds.add(payload.toolCallId.value)
                        is RuntimeEventPayload.ApprovalRequested -> {
                            emittedToolCallIds.add(payload.request.callId.value)
                            // letta-mobile-vilsn.6: this ApprovalRequested reached
                            // the collect body, which means it was NOT auto-approved
                            // (auto-approved drafts are swallowed above via
                            // autoApprovedToolCallDraft). If it is a runtime
                            // user-input tool (AskUserQuestion / ExitPlanMode) the
                            // turn is now parked awaiting the user's answer — record
                            // an outstanding gate so the idle watchdog is paused and
                            // the unanswered question does not synthesize a Failed
                            // idle timeout.
                            if (RuntimeUserInputTools.requiresUserInput(payload.request.toolName.value)) {
                                // letta-mobile-vilsn: record the REAL approval id
                                // (the can_use_tool control-request request_id, e.g.
                                // perm-call_...) keyed by tool_call_id. This map is
                                // BOTH the submit path's source (submitApproval
                                // clears it after a successful response) AND the
                                // watchdog's outstanding-gate set (vilsn.6): a non-empty
                                // map pauses the idle watchdog. Interactive answers must
                                // close the gate against THIS id, which is not derivable
                                // from the tool_call_id across LLM providers (call_… vs
                                // toolu_…).
                                userInputApprovalIdsRef.update { map ->
                                    map + (payload.request.callId.value to payload.request.approvalId.value)
                                }
                            }
                        }
                        is RuntimeEventPayload.ToolReturnObserved -> {
                            returnedToolCallIds.add(payload.toolCallId.value)
                            // letta-mobile-vilsn.6: a tool_return for a parked
                            // user-input tool_call_id genuinely resolves THAT gate —
                            // lift the pause for this specific id (no-op if the submit
                            // path already consumed it).
                            userInputApprovalIdsRef.update { it - payload.toolCallId.value }
                        }
                        is RuntimeEventPayload.RemoteStreamFrame -> {
                            // Extract tool_call_id from tool_call_message and approval_request_message frames
                            extractToolCallId(payload.body)?.let { emittedToolCallIds.add(it) }
                            // Extract returned tool_call_id from tool_return_message frames
                            if (payload.messageType == "tool_return_message") {
                                extractToolCallId(payload.body)?.let {
                                    returnedToolCallIds.add(it)
                                    // letta-mobile-vilsn.6: a streamed tool_return
                                    // resolves that specific outstanding gate.
                                    userInputApprovalIdsRef.update { map -> map - it }
                                }
                            }
                            // letta-mobile-vilsn.7: some App Server transports deliver
                            // the tool-call approval as a StreamDelta
                            // approval_request_message (RemoteStreamFrame) rather than
                            // a ControlRequest (which maps to ApprovalRequested above).
                            // That path bypassed gate registration entirely, so a
                            // parked AskUserQuestion/ExitPlanMode arriving this way was
                            // never added to userInputApprovalIdsRef: the idle watchdog
                            // was not paused (vilsn.6) and the submit path could not
                            // recover the real can_use_tool request id via
                            // userInputApprovalId. Register it here exactly like
                            // the ApprovalRequested branch does.
                            if (payload.messageType == "approval_request_message") {
                                val approval = draft.toApprovalAutoAllowRequest()
                                val callId = approval?.toolCallId
                                if (approval != null &&
                                    callId != null &&
                                    RuntimeUserInputTools.requiresUserInput(approval.toolName)
                                ) {
                                    userInputApprovalIdsRef.update { map ->
                                        map + (callId to approval.requestId)
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                    
                    // letta-mobile-c4igq.6: a tool_call / tool_return / assistant
                    // frame arriving after we speculatively armed a post-tool usage
                    // completion means the turn is genuinely continuing (another tool
                    // round). Cancel the speculative completion so it cannot fire and
                    // prematurely end the turn. Real terminals are unaffected.
                    if (speculativeCompletionArmed &&
                        (draft.isToolCallFrame() || draft.isToolReturnFrame() || draft.isAssistantFrame())
                    ) {
                        cancelSpeculativeCompletion()
                    }
                    if (draft.isToolReturnFrame()) sawToolReturn = true
                    if (sawToolReturn && draft.isAssistantFrame()) sawAssistantAfterToolReturn = true
                    if (draft.isStopReasonFrame()) {
                        pendingStop = draft
                        return@forEach
                    }
                    if (draft.isUsageStatisticsFrame()) {
                        if (pendingUsage == null) pendingUsage = draft
                        if (sawAssistantAfterToolReturn) {
                            // letta-mobile-c4igq.6: a usage_statistics frame after a
                            // post-tool assistant message is the synthesized-completion
                            // FALLBACK for turns whose real terminal never arrives — BUT
                            // a multi-step agentic turn also emits a usage tail BETWEEN
                            // tool rounds. Throwing here immediately killed the turn
                            // before the next round (Iroh: "stops after a tool call,
                            // needs a user nudge"). Instead, arm a SPECULATIVE deferred
                            // completion on the same bounded quiet window the clean
                            // Completed path uses. If another tool round follows within
                            // the window, cancelSpeculativeCompletion() (above) unwinds
                            // it and the turn proceeds; if the window elapses quietly,
                            // the deferred completion fires — preserving the single-
                            // round fallback. Reset the post-tool latch so a fresh round
                            // must re-observe tool_return -> assistant before re-arming.
                            if (pendingCompleted == null) {
                                pendingCompleted = command.completedDraft(draft.runId)
                                pendingCompletedSeq = frameSeq
                            }
                            sawAssistantAfterToolReturn = false
                            sawToolReturn = false
                            speculativeCompletionArmed = true
                            armCompletedTerminalOnce()
                        }
                        return@forEach
                    }
                    if (draft.isCompletedLifecycle()) {
                        pendingCompleted = draft
                        armCompletedTerminalOnce()
                        return@forEach
                    }
                    // letta-mobile-oqfbj: settle dangling calls BEFORE the tail +
                    // terminal lifecycle so tool cards resolve to error instead of
                    // spinning and the transcript keeps matched call/return pairs.
                    // fix(no-settle-on-clean-completion): only for ABNORMAL
                    // terminals (Failed/Cancelled). A clean Completed terminal
                    // must NOT synthesize Failed returns — see
                    // settleDanglingToolCalls() KDoc.
                    if (draft.isTerminalLifecycle()) {
                        if (draft.isAbnormalTerminal()) {
                            settleDanglingToolCalls(command, emittedToolCallIds, returnedToolCallIds, emitDraft, "Tool execution interrupted by turn termination")
                        }
                        flushTail()
                        // letta-mobile-kyqdt: telemetry-only. Record the terminal
                        // status carried by this lifecycle draft. This frame was
                        // accepted by matches(scope), so the scope decision passed.
                        (draft.payload as? RuntimeEventPayload.RunLifecycleChanged)?.let {
                            noteOwnerTerminal(
                                it.status,
                                source = "terminal_lifecycle",
                                seq = frameSeq,
                                scopeMatched = true,
                                leaseToken = leaseToken,
                            )
                        }
                        emitDraft(draft)
                        throw TurnCompleted
                    }
                    emitDraft(draft)
                    armCompletedTerminalOnce()
                }
            }
        } catch (idle: TurnIdleTimedOutMarker) {
            // letta-mobile-oqfbj: settle before emitting the failed draft
            settleDanglingToolCalls(command, emittedToolCallIds, returnedToolCallIds, emitDraft, "Tool execution interrupted by turn timeout")
            throw idle
        } catch (e: CancellationException) {
            // letta-mobile-oqfbj: settle on cancellation/abort.
            // fix(no-settle-on-clean-completion): structured concurrency can
            // deliver a CLEAN completion's `throw TurnCompleted` (thrown from
            // the delayed terminalSettleJob, a sibling coroutine of this
            // collect loop) to this suspension point wrapped as a
            // CancellationException whose cause chain includes the original
            // TurnCompletedMarker. That is NOT an abnormal cancellation/abort —
            // it is the clean-completion path — so it must not settle.
            if (!e.isCausedByCleanCompletion()) {
                turnEndReason = "Tool execution interrupted by cancellation"
            }
            throw e
        } catch (e: Throwable) {
            // letta-mobile-oqfbj: settle on collector failure.
            // fix(no-settle-on-clean-completion): same guard as above, in case
            // the clean-completion marker surfaces here unwrapped instead.
            if (!e.isCausedByCleanCompletion()) {
                turnEndReason = "Tool execution interrupted by stream error"
            }
            throw e
        } finally {
            // letta-mobile-oqfbj: settle any remaining dangling calls before cleanup
            if (turnEndReason != null) {
                settleDanglingToolCalls(command, emittedToolCallIds, returnedToolCallIds, emitDraft, turnEndReason)
            }
            terminalSettleJob?.cancel()
            watchdog.cancel()
            // letta-mobile-vilsn.6: the collect loop has ended (terminal, idle
            // timeout, cancellation, or stream error) — clear every outstanding
            // user-input gate so none leaks into a later turn and keeps a fresh
            // watchdog wrongly paused.
            userInputApprovalIdsRef.update { emptyMap() }
        }
    }

    /**
     * When the runtime is Unrestricted and [draft] is an approval request,
     * auto-allows it and returns a [RuntimeEventPayload.ToolCallObserved]
     * payload describing the underlying tool call so the caller can surface
     * the tool card immediately (the approval CARD is suppressed; the tool
     * CALL announcement must not be). Returns null when the draft is not an
     * auto-approvable approval request.
     */
    private suspend fun autoApprovedToolCallDraft(
        scope: AppServerRuntimeScope,
        turnPermissionMode: AppServerPermissionMode,
        command: TurnCommand,
        draft: RuntimeEventDraft,
    ): RuntimeEventPayload.ToolCallObserved? {
        if (!autoApproveIfAllowed(scope, turnPermissionMode, draft)) return null
        val approval = draft.toApprovalAutoAllowRequest() ?: return null
        return RuntimeEventPayload.ToolCallObserved(
            toolCallId = ToolCallId(approval.toolCallId ?: approval.requestId),
            toolName = ToolName(approval.toolName ?: "tool"),
            argumentsJson = draft.approvalArgumentsPreview(),
        )
    }

    private fun RuntimeEventDraft.approvalArgumentsPreview(): String? = when (val payload = this.payload) {
        is RuntimeEventPayload.ApprovalRequested -> payload.request.argumentsPreview
        is RuntimeEventPayload.RemoteStreamFrame -> runCatching {
            val raw = AppServerProtocol.json.parseToJsonElement(payload.body).jsonObject
            val delta = raw["delta"]?.jsonObject ?: raw
            (delta["tool_call"] as? JsonObject)?.get("arguments")?.toString()
                ?: delta["arguments"]?.toString()
        }.getOrNull()
        else -> null
    }

    private fun TurnCommand.draftFor(
        runId: RunId?,
        payload: RuntimeEventPayload,
    ): RuntimeEventDraft = RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        runId = runId,
        source = RuntimeEventSource.LocalRuntime,
        payload = payload,
    )

    private suspend fun autoApproveIfAllowed(
        scope: AppServerRuntimeScope,
        turnPermissionMode: AppServerPermissionMode,
        draft: RuntimeEventDraft,
    ): Boolean {
        if (turnPermissionMode != AppServerPermissionMode.Unrestricted) return false
        val approval = draft.toApprovalAutoAllowRequest() ?: return false
        // letta-mobile-vilsn: runtime user-input tools (AskUserQuestion,
        // ExitPlanMode) must NEVER be auto-approved — auto-approving closes them
        // with no answer (the tool returns a "Waiting for user response..."
        // placeholder and the agent stalls). Surface them to the client as a
        // real approval request so the user can see the query and answer it.
        if (RuntimeUserInputTools.requiresUserInput(approval.toolName)) return false
        Telemetry.event(
            "IrohTurn", "approval.auto_allow",
            "approvalId" to approval.requestId,
            "toolCallId" to (approval.toolCallId ?: ""),
            "tool" to (approval.toolName ?: ""),
            "source" to approval.source,
        )
        client.input(
            AppServerCommand.Input(
                runtime = scope,
                payload = AppServerInputPayload.ApprovalResponse(
                    requestId = approval.requestId,
                    decision = AppServerApprovalResponseDecision.Allow(
                        message = "Approved by default mobile policy.",
                    ),
                ),
            ),
        )
        return true
    }

    private fun RuntimeEventDraft.toApprovalAutoAllowRequest(): ApprovalAutoAllowRequest? {
        when (val payload = this.payload) {
            is RuntimeEventPayload.ApprovalRequested -> return ApprovalAutoAllowRequest(
                requestId = payload.request.approvalId.value,
                toolCallId = payload.request.callId.value,
                toolName = payload.request.toolName.value,
                source = "control_request",
            )
            is RuntimeEventPayload.RemoteStreamFrame -> {
                if (payload.messageType != "approval_request_message") return null
                val delta = runCatching {
                    val raw = AppServerProtocol.json.parseToJsonElement(payload.body).jsonObject
                    raw["delta"]?.jsonObject ?: raw
                }.getOrNull() ?: return null
                val requestId = delta.string("approval_request_id")
                    ?: delta.string("id")
                    ?: payload.messageId
                    ?: payload.frameId
                val toolCall = delta["tool_call"] as? JsonObject
                return ApprovalAutoAllowRequest(
                    requestId = requestId,
                    toolCallId = toolCall?.string("tool_call_id") ?: delta.string("tool_call_id"),
                    toolName = toolCall?.string("name") ?: delta.string("tool_name") ?: delta.string("name"),
                    source = "approval_request_message",
                )
            }
            else -> return null
        }
    }

    private data class ApprovalAutoAllowRequest(
        val requestId: String,
        val toolCallId: String?,
        val toolName: String?,
        val source: String,
    )

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private suspend fun ensureRuntime(command: TurnCommand, turnPermissionMode: AppServerPermissionMode): AppServerRuntimeScope {
        runtime?.let { cached ->
            if (cached.matches(command)) return cached
        }
        val response = client.runtimeStart(
            AppServerCommand.RuntimeStart(
                requestId = requestIdFactory(),
                agentId = command.agentId.value,
                conversationId = command.conversationId.value,
                mode = turnPermissionMode,
                clientInfo = clientInfo,
                recoverApprovals = true,
                forceDeviceStatus = true,
            ),
        )
        Telemetry.event("IrohTurn", "runtimeStart.response", "success" to response.success, "hasRuntime" to (response.runtime != null), "error" to response.error)
        if (!response.success) {
            error(response.error ?: "App Server runtime_start failed.")
        }
        val returnedRuntime = response.runtime ?: error("App Server runtime_start returned no runtime.")
        runtime = returnedRuntime
        return returnedRuntime
    }

    private fun AppServerRuntimeScope.matches(command: TurnCommand): Boolean =
        agentId == command.agentId.value && conversationId == command.conversationId.value

    private fun TurnCommand.toInputCommand(scope: AppServerRuntimeScope): AppServerCommand.Input =
        when (val turnInput = input) {
            is TurnInput.UserMessage -> AppServerCommand.Input(
                runtime = scope,
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(
                        AppServerInputMessage(
                            role = "user",
                            content = turnInput.contentPartsJson
                                ?.let { AppServerProtocol.json.parseToJsonElement(it) }
                                ?: JsonPrimitive(turnInput.text),
                            clientMessageId = turnInput.localMessageId,
                        ),
                    ),
                    clientToolAllowlist = toolPolicy.allowedTools.toWireAllowlist(),
                ),
            )
            is TurnInput.ToolApprovalResponse -> AppServerCommand.Input(
                runtime = scope,
                payload = AppServerInputPayload.ApprovalResponse(
                    requestId = turnInput.decision.approvalId.value,
                    decision = when (turnInput.decision.decision) {
                        ToolApprovalDecisionValue.Approved -> {
                            AppServerApprovalResponseDecision.Allow(
                                message = turnInput.decision.response,
                            )
                        }
                        ToolApprovalDecisionValue.Denied,
                        ToolApprovalDecisionValue.TimedOut,
                        -> AppServerApprovalResponseDecision.Deny(
                            message = turnInput.decision.response ?: "Denied by mobile client.",
                        )
                    },
                ),
            )
        }

    private fun Set<ToolName>.toWireAllowlist(): List<String>? =
        takeIf { it.isNotEmpty() }?.map { it.value }?.sorted()

    private fun TurnCommand.startedDraft(): RuntimeEventDraft =
        RuntimeEventDraft(
            backendId = backendId,
            runtimeId = runtimeId,
            agentId = agentId,
            conversationId = conversationId,
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started),
        )

    private fun TurnCommand.completedDraft(runId: RunId?): RuntimeEventDraft =
        RuntimeEventDraft(
            backendId = backendId,
            runtimeId = runtimeId,
            agentId = agentId,
            conversationId = conversationId,
            runId = runId,
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed),
        )

    private fun TurnCommand.failedDraft(reason: String): RuntimeEventDraft =
        RuntimeEventDraft(
            backendId = backendId,
            runtimeId = runtimeId,
            agentId = agentId,
            conversationId = conversationId,
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Failed, reason = reason),
        )

    private fun RuntimeEventDraft.isTerminalLifecycle(): Boolean {
        val lifecycle = payload as? RuntimeEventPayload.RunLifecycleChanged ?: return false
        return lifecycle.status == RuntimeRunStatus.Completed ||
            lifecycle.status == RuntimeRunStatus.Failed ||
            lifecycle.status == RuntimeRunStatus.Cancelled
    }

    private fun RuntimeEventDraft.isCompletedLifecycle(): Boolean {
        val lifecycle = payload as? RuntimeEventPayload.RunLifecycleChanged ?: return false
        return lifecycle.status == RuntimeRunStatus.Completed
    }

    /**
     * fix(no-settle-on-clean-completion): true only for Failed/Cancelled
     * terminal lifecycles — i.e. an ABNORMAL end. [isTerminalLifecycle] is
     * still used for flow control (both clean and abnormal terminals end the
     * collect loop the same way); this narrower check gates whether dangling
     * tool calls should be settled with a synthetic Failed return. See
     * [settleDanglingToolCalls] for why Completed must never settle.
     */
    private fun RuntimeEventDraft.isAbnormalTerminal(): Boolean {
        val lifecycle = payload as? RuntimeEventPayload.RunLifecycleChanged ?: return false
        return lifecycle.status == RuntimeRunStatus.Failed ||
            lifecycle.status == RuntimeRunStatus.Cancelled
    }

    private fun RuntimeEventDraft.isToolReturnFrame(): Boolean = when (val event = payload) {
        is RuntimeEventPayload.ToolReturnObserved -> true
        is RuntimeEventPayload.RemoteStreamFrame -> event.messageType == "client_tool_end" ||
            event.messageType == "tool_return_message" ||
            frameMessageType(event.body) in setOf("client_tool_end", "tool_return_message")
        else -> false
    }

    private fun RuntimeEventDraft.isAssistantFrame(): Boolean = when (val event = payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> event.messageType == "assistant_message" ||
            frameMessageType(event.body) == "assistant_message"
        else -> false
    }

    // letta-mobile-c4igq.6: a tool_call announcement (used to detect a new tool
    // round continuing after a speculative post-tool usage completion was armed).
    private fun RuntimeEventDraft.isToolCallFrame(): Boolean = when (val event = payload) {
        is RuntimeEventPayload.ToolCallObserved -> true
        is RuntimeEventPayload.ApprovalRequested -> true
        is RuntimeEventPayload.RemoteStreamFrame -> event.messageType == "client_tool_start" ||
            event.messageType == "tool_call_message" ||
            frameMessageType(event.body) in setOf("client_tool_start", "tool_call_message")
        else -> false
    }

    private fun RuntimeEventDraft.isUsageStatisticsFrame(): Boolean = when (val event = payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> event.messageType == "usage_statistics" ||
            frameMessageType(event.body) == "usage_statistics"
        is RuntimeEventPayload.ExternalTransportFrame -> event.body.startsWith("usage:") ||
            frameMessageType(event.body) == "usage_statistics"
        else -> false
    }

    private fun RuntimeEventDraft.isStopReasonFrame(): Boolean = when (val event = payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> event.messageType == "stop_reason" ||
            frameMessageType(event.body) == "stop_reason"
        is RuntimeEventPayload.ExternalTransportFrame -> frameMessageType(event.body) == "stop_reason"
        else -> false
    }

    private fun frameMessageType(body: String): String? = runCatching {
        val raw = AppServerProtocol.json.parseToJsonElement(body).jsonObject
        val delta = raw["delta"]?.jsonObject ?: raw
        delta.string("message_type")
    }.getOrNull()

    private fun AppServerReceivedFrame.matches(
        scope: AppServerRuntimeScope,
    ): Boolean {
        val eventRuntime = frame.runtime ?: return true
        return eventRuntime.agentId == scope.agentId &&
            eventRuntime.conversationId == scope.conversationId
    }

    /**
     * letta-mobile-kyqdt: TELEMETRY-ONLY. Best-effort event_seq for a received
     * frame, if the concrete frame type carries one. Pure read; null otherwise.
     */
    private fun AppServerReceivedFrame.eventSeqOrNull(): Long? =
        when (val f = frame) {
            is AppServerInboundFrame.StreamDelta -> f.eventSeq
            is AppServerInboundFrame.UpdateLoopStatus -> f.eventSeq
            is AppServerInboundFrame.UpdateDeviceStatus -> f.eventSeq
            is AppServerInboundFrame.UpdateQueue -> f.eventSeq
            is AppServerInboundFrame.UpdateSubagentState -> f.eventSeq
            else -> null
        }

    /**
     * letta-mobile-kyqdt: TELEMETRY-ONLY. Best-effort check whether a received
     * frame CARRIES a terminal signal (stop_reason / error / terminal
     * lifecycle), used only to record the scope-match decision for
     * terminal-bearing frames that were rejected by matches(scope). Pure read of
     * the frame's delta message_type; never gates control flow.
     */
    private fun AppServerReceivedFrame.carriesTerminal(): Boolean {
        val streamDelta = frame as? AppServerInboundFrame.StreamDelta
            ?: return false
        val messageType = runCatching {
            val delta = streamDelta.delta.jsonObject
            delta.string("message_type")
        }.getOrNull() ?: return false
        return messageType == "stop_reason" || messageType == "error_message"
    }

    
    /**
     * letta-mobile-oqfbj: extract tool_call_id from a RemoteStreamFrame body.
     * Handles tool_call_message, approval_request_message, and tool_return_message frames.
     */
    private fun extractToolCallId(body: String): String? = runCatching {
        val raw = AppServerProtocol.json.parseToJsonElement(body).jsonObject
        val delta = raw["delta"]?.jsonObject ?: raw
        // Try tool_call.tool_call_id first (tool_call_message shape)
        delta["tool_call"]?.jsonObject?.string("tool_call_id")
            // Then direct tool_call_id field (approval_request_message / tool_return_message shape)
            ?: delta.string("tool_call_id")
    }.getOrNull()
    
    /**
     * letta-mobile-oqfbj: emit synthetic ToolReturnObserved drafts for every tool_call_id
     * that was emitted but never returned. No-op when all emitted calls have returns.
     *
     * fix(no-settle-on-clean-completion, letta-mobile-oqfbj): this must be called
     * ONLY on ABNORMAL turn ends — cancellation, idle timeout, stream error, or a
     * terminal lifecycle whose status is Failed/Cancelled. It must NEVER be
     * called for a clean Completed terminal (delayed-settle quiet window,
     * post-tool usage-statistics completion, or a Completed terminal lifecycle
     * frame).
     *
     * Why: with async/parallel tool execution, a second (or later) tool call's
     * real return can legitimately arrive from the server AFTER this turn's
     * terminal frame. The synthetic Failed return produced here is a UI-layer
     * DRAFT ONLY — it is never persisted to the server transcript. On a clean
     * completion the server is authoritative and will still deliver the real
     * return via a later snapshot
     * (TimelineReturnsResponsesProcessor.applyReturnsAndResponsesFromSnapshot,
     * last-wins). Settling early on clean completion therefore has no
     * correctness benefit and one guaranteed cost: the tool card renders red
     * ("Tool execution interrupted by turn completion") for a few seconds
     * before the real success snapshot flips it back to green —
     * a visible, confusing flicker for something that was never actually a
     * failure. Live telemetry confirmed this: 34 settlements fired with reason
     * "turn completion" against just 1 for genuine cancellation, and server
     * transcripts showed real `toolResult isError=False` for the exact call ids
     * that had been prematurely settled.
     *
     * Called from: the collector's finally block (idle timeout / cancellation /
     * stream error) and the terminal-lifecycle path guarded by
     * [RuntimeEventDraft.isAbnormalTerminal] (Failed/Cancelled only).
     */
    private suspend fun settleDanglingToolCalls(
        command: TurnCommand,
        emittedToolCallIds: Set<String>,
        returnedToolCallIds: MutableSet<String>,
        emitDraft: suspend (RuntimeEventDraft) -> Unit,
        settlementReason: String?,
    ) {
        val dangling = emittedToolCallIds - returnedToolCallIds
        if (dangling.isEmpty()) return
        // Mark as returned FIRST so a second settlement pass (terminal path +
        // the finally-block safety net) cannot synthesize duplicates.
        returnedToolCallIds += dangling

        val reasonText = settlementReason ?: "Tool execution interrupted by turn completion"
        
        for (toolCallId in dangling) {
            val syntheticReturn = RuntimeEventDraft(
                backendId = command.backendId,
                runtimeId = command.runtimeId,
                agentId = command.agentId,
                conversationId = command.conversationId,
                runId = null,
                source = RuntimeEventSource.LocalRuntime,
                payload = RuntimeEventPayload.ToolReturnObserved(
                    toolCallId = ToolCallId(toolCallId),
                    status = ToolExecutionStatus.Failed,
                    body = reasonText,
                ),
            )
            emitDraft(syntheticReturn)
            
            Telemetry.event(
                "IrohTurn",
                "settlement.synthesized",
                "toolCallId" to toolCallId,
                "reason" to reasonText,
            )
        }
    }

    /**
     * letta-mobile-kyqdt: TELEMETRY-ONLY snapshot of who owns the [activeTurn]
     * lock. Pure metadata — never consulted for lock/[isBusy] decisions.
     *
     * @property runId server-assigned run id once observed, else null (unknown
     *   at lock-acquire time; the run id is promoted from server frames later).
     * @property runtimeId acquiring turn's runtime id.
     * @property agentId acquiring turn's agent id.
     * @property conversationId acquiring turn's conversation id.
     * @property acquiredAtMs epoch-millis when the lock was acquired.
     * @property lastTerminal last-seen terminal lifecycle status name, else null.
     * @property processRole runtime/process role for the owning turn (e.g.
     *   permission mode), else null. Purely descriptive.
     * @property lastTerminalSource which code path/frame observed the last
     *   terminal (e.g. "terminal_lifecycle", "post_tool_usage",
     *   "completed_settle", "idle_timeout"), else null.
     * @property lastTerminalAtMs epoch-millis when the last terminal was noted.
     * @property lastTerminalSeq event_seq of the terminal-bearing frame if the
     *   frame carried one, else null.
     * @property lastTerminalScopeMatched whether the terminal-bearing frame
     *   PASSED matches(scope) (true) or was rejected by the scope filter
     *   (false). This is the key hypothesis probe: a terminal that arrived but
     *   failed matches(scope) would be recorded here as false. Null when no
     *   scope decision has been observed for a terminal-bearing frame.
     * @property settleDeadlineMs the terminal-settle quiet window (ms) in force
     *   for the owning turn, else null.
     * @property watchdogDeadlineMs the idle watchdog window (ms) in force for
     *   the owning turn, else null.
     * @property releaseReason why the turn reached its finally/release (normal
     *   completion, watchdog timeout, cancellation, preemption, stream error),
     *   else null while still active.
     */
    data class ActiveTurnOwner(
        val runId: String?,
        val runtimeId: String?,
        val agentId: String?,
        val conversationId: String?,
        val acquiredAtMs: Long,
        val lastTerminal: String?,
        val processRole: String? = null,
        val lastTerminalSource: String? = null,
        val lastTerminalAtMs: Long? = null,
        val lastTerminalSeq: Long? = null,
        val lastTerminalScopeMatched: Boolean? = null,
        val settleDeadlineMs: Long? = null,
        val watchdogDeadlineMs: Long? = null,
        val releaseReason: String? = null,
    )

    private object TurnCompleted : TurnCompletedMarker()
    private sealed class TurnCompletedMarker : Throwable()

    /**
     * fix(no-settle-on-clean-completion): true when [this] (or anything in its
     * `cause` chain) is [TurnCompletedMarker] — i.e. the exception is really
     * the clean-completion signal propagated across a coroutine boundary
     * (structured concurrency wraps a sibling's thrown [TurnCompleted] as a
     * [kotlinx.coroutines.CancellationException] whose cause chain preserves
     * the original marker). Used to make sure the abnormal-end catch clauses
     * below never mistake a clean completion for a real cancellation/error and
     * settle dangling tool calls with a synthetic Failed return.
     */
    private fun Throwable.isCausedByCleanCompletion(): Boolean =
        generateSequence(this) { it.cause }.any { it is TurnCompletedMarker }

    private object TurnIdleTimedOut : TurnIdleTimedOutMarker()
    private sealed class TurnIdleTimedOutMarker : Throwable()

    /**
     * letta-mobile-kyqdt: TELEMETRY-ONLY. Records the last-seen terminal
     * lifecycle status on the active-turn owner (if one is set). Pure metadata
     * write — no control-flow, no lock interaction, no effect on emitted drafts.
     *
     * @param status terminal lifecycle status carried by the draft.
     * @param source which collect-loop path observed this terminal (e.g.
     *   "terminal_lifecycle", "post_tool_usage", "completed_settle",
     *   "idle_timeout"). Descriptive only.
     * @param seq event_seq of the terminal-bearing frame if known, else null.
     * @param scopeMatched whether the terminal-bearing frame PASSED
     *   matches(scope). Null when not applicable (e.g. synthesized terminals).
     */
    private fun noteOwnerTerminal(
        status: RuntimeRunStatus,
        source: String? = null,
        seq: Long? = null,
        scopeMatched: Boolean? = null,
        leaseToken: Long,
    ) {
        if (activeLeaseRef.value?.token != leaseToken) return
        val current = activeTurnOwnerRef.value ?: return
        activeTurnOwnerRef.value = current.copy(
            lastTerminal = status.name,
            lastTerminalSource = source ?: current.lastTerminalSource,
            lastTerminalAtMs = currentTimeMs(),
            lastTerminalSeq = seq ?: current.lastTerminalSeq,
            lastTerminalScopeMatched = scopeMatched ?: current.lastTerminalScopeMatched,
        )
        activeLeaseRef.update { lease ->
            if (lease?.token != leaseToken) lease
            else lease.copy(
                lastTerminal = status.name,
                lastTerminalSource = source ?: lease.lastTerminalSource,
                lastTerminalAtMs = currentTimeMs(),
                lastTerminalSeq = seq ?: lease.lastTerminalSeq,
                lastTerminalScopeMatched = scopeMatched ?: lease.lastTerminalScopeMatched,
            )
        }
    }

    /**
     * letta-mobile-kyqdt: TELEMETRY-ONLY. Promotes the server-assigned/promoted
     * run id into the active-turn owner (if one is set and not yet stamped with
     * a run id). Pure `copy(runId=…)` metadata write — no control-flow, no lock
     * interaction, no effect on emitted drafts or the run-id promotion path.
     * Token-gated so a stale owner cannot promote into a successor lease (lgns8.22.2).
     */
    private fun promoteOwnerRunId(runId: String, leaseToken: Long) {
        if (activeLeaseRef.value?.token != leaseToken) return
        val current = activeTurnOwnerRef.value ?: return
        if (current.runId == runId) return
        activeTurnOwnerRef.value = current.copy(runId = runId)
        activeLeaseRef.update { lease ->
            if (lease == null || lease.token != leaseToken || lease.runId == runId) lease
            else lease.copy(runId = runId)
        }
    }

    /**
     * letta-mobile-kyqdt: TELEMETRY-ONLY. Records the accepted-vs-rejected
     * matches(scope) decision for a terminal-bearing frame WITHOUT altering the
     * owner's terminal status. This lets the release event prove the leading
     * hypothesis: a terminal arrived but failed matches(scope). Pure metadata.
     */
    private fun noteOwnerScopeDecision(
        scopeMatched: Boolean,
        source: String,
        seq: Long?,
        leaseToken: Long,
    ) {
        if (activeLeaseRef.value?.token != leaseToken) return
        val current = activeTurnOwnerRef.value ?: return
        activeTurnOwnerRef.value = current.copy(
            lastTerminalScopeMatched = scopeMatched,
            lastTerminalSource = source,
            lastTerminalAtMs = currentTimeMs(),
            lastTerminalSeq = seq ?: current.lastTerminalSeq,
        )
    }

    private fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private companion object {
        private var nextRequestId = 0

        // 90s idle window: long enough for a slow first token / tool round-trip,
        // short enough that a permanently-stuck turn frees the engine before the
        // user gives up. Idle-based (reset per frame), so a long actively-streaming
        // turn never trips. Tunable via the ctor param.
        const val DEFAULT_TURN_IDLE_TIMEOUT_MS: Long = 300_000L
        const val DEFAULT_TERMINAL_SETTLE_QUIET_MS: Long = 1_500L
        /** Fail-fast budget for busy-path run.get / run.list liveness probes. */
        const val LIVENESS_PROBE_TIMEOUT_MS: Long = 3_000L
        /** Aggregate budget for turn-context preflight while activeTurn is held. */
        const val PREFLIGHT_TIMEOUT_MS: Long = 15_000L

        // letta-mobile-vilsn.6: while the watchdog is paused on an outstanding
        // user-input gate it re-checks on this cadence (capped at the idle window)
        // so it resumes promptly once the gate clears, rather than sleeping a full
        // stale interval. Bounds how stale lastFrameAt can be on resume.
        const val WATCHDOG_PAUSE_RECHECK_MS: Long = 250L

        fun defaultRequestId(): String {
            nextRequestId += 1
            return "app-server-${nextRequestId}"
        }
    }
}
