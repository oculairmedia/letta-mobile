package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
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
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

import kotlin.time.Duration.Companion.milliseconds
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
) : TurnEngine {
    private val activeTurn = Mutex()
    private var runtime: AppServerRuntimeScope? = null

    /**
     * letta-mobile-kyqdt: TELEMETRY-ONLY owner identity for the currently held
     * [activeTurn] lock. This holder is set STRICTLY adjacent to the existing
     * `activeTurn.lock()`/`activeTurn.unlock()` calls and never participates in
     * lock acquisition or the [isBusy] computation — it exists purely so a
     * busy-rejected send can PROVE which run/agent/conversation owns the engine,
     * when it acquired the lock, and its last-seen terminal. Backed by an
     * atomicfu ref so the read accessor is safe from any thread with no locking.
     */
    private val activeTurnOwnerRef = kotlinx.atomicfu.atomic<ActiveTurnOwner?>(null)

    /**
     * Pure read accessor for the current active-turn owner (telemetry only).
     * Null when idle. Does NOT touch [activeTurn]; reading it never affects lock
     * semantics or [isBusy].
     */
    val activeTurnOwner: ActiveTurnOwner? get() = activeTurnOwnerRef.value

    /**
     * true when a turn is actively running (activeTurn locked).
     * Check before calling runTurn to avoid "can't send while busy" errors.
     */
    val isBusy: Boolean get() = !activeTurn.tryLock().also { if (it) activeTurn.unlock() }

    /**
     * The runtime scope for the most recently started/cached runtime, or null if
     * no runtime has been started on this engine yet. Exposed so the transport
     * can build an `abort_message` addressed to the exact agent/conversation the
     * active turn is running against.
     */
    val currentRuntime: AppServerRuntimeScope? get() = runtime

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
     * letta-mobile-c4igq.3: causal liveness reconciler. When a send is rejected
     * because [activeTurn] is held, PROVE whether the owning run is actually dead
     * (via run.get) before giving up. Clears the stale lock ONLY on server-
     * confirmed death (terminal status, completedAt set, or the run is gone). A
     * run the server still reports active/in_progress is left ALONE — a silently-
     * thinking multi-hour turn is never interrupted. No wall-clock: keyed purely
     * on the run's causal lifecycle state. Returns true iff it released the lock.
     */
    private suspend fun reconcileOwnerLivenessAndMaybeRelease(): Boolean {
        val owner = activeTurnOwnerRef.value ?: return false
        val runId = owner.runId?.takeIf { it.isNotBlank() } ?: return false
        val dead = try {
            val resp = client.adminRpc(
                AppServerCommand.AdminRpc(
                    requestId = requestIdFactory(),
                    method = "run.get",
                    params = kotlinx.serialization.json.buildJsonObject { put("run_id", runId) },
                ),
            )
            when {
                // The server reports success with a run body: dead iff terminal.
                resp.success -> runResultIsDead(resp.result)
                // A failed run.get whose error indicates the run is gone/not-found
                // is also proof of death.
                resp.error?.let { it.contains("not found", ignoreCase = true) || it.contains("no such run", ignoreCase = true) } == true -> true
                else -> false
            }
        } catch (t: Throwable) {
            // Never clear the lock on an inconclusive/errored liveness check — that
            // could interrupt a live run. Only server-CONFIRMED death releases.
            com.letta.mobile.util.Telemetry.error("AppServerTurnEngine", "activeTurn.reconcileLivenessFailed", t, "runId" to runId)
            return false
        }
        if (!dead) {
            com.letta.mobile.util.Telemetry.event("AppServerTurnEngine", "activeTurn.reconciledAlive", "runId" to runId)
            return false
        }
        // Confirmed dead: release the stale lock so the pending send can proceed.
        val released = activeTurnOwnerRef.value
        activeTurnOwnerRef.value = null
        try {
            activeTurn.unlock()
        } catch (t: Throwable) {
            // If it was already unlocked concurrently, treat as released.
        }
        com.letta.mobile.util.Telemetry.event(
            "AppServerTurnEngine", "activeTurn.reconciledDead",
            "runId" to runId,
            "agentId" to (released?.agentId ?: ""),
            "conversationId" to (released?.conversationId ?: ""),
            "reason" to "run_provably_dead",
        )
        return true
    }

    /** True iff a run.get result body proves the run is terminal/dead. */
    private fun runResultIsDead(result: kotlinx.serialization.json.JsonElement?): Boolean {
        val obj = (result as? JsonObject) ?: return false
        val status = obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase()
        if (status != null && (status == "completed" || status == "failed" || status == "cancelled" || status == "error" || status == "expired")) return true
        // completed_at set is also terminal.
        if (obj["completed_at"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) return true
        return false
    }

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = channelFlow {
        if (!activeTurn.tryLock()) {
            // letta-mobile-c4igq.3: CAUSAL liveness reconciler (backstop for the
            // residual no-terminal death — a run that vanished server-side with no
            // terminal frame and no stream-death, so c4igq.1's terminal-release
            // never fired). Before rejecting the send, PROVE the owning run is dead
            // via run.get; clear the stale lock ONLY on confirmed death. A silently-
            // thinking run reports active/in_progress → we do NOTHING and reject the
            // send as before, so a legitimate multi-hour turn is never interrupted.
            // No wall-clock: this runs only on a busy-rejected send and clears the
            // lock solely on server-confirmed death.
            if (reconcileOwnerLivenessAndMaybeRelease() && activeTurn.tryLock()) {
                // Reconciled a dead run; the retry acquired the lock — proceed.
            } else {
                throw IllegalStateException("An App Server turn is already active for ${command.runtimeId.value}.")
            }
        }

        // letta-mobile-kyqdt: TELEMETRY-ONLY. Stamp owner identity immediately
        // after the lock is acquired (the lock call itself is unchanged). The
        // runId is server-assigned later, so it is null at acquire time; the
        // runtimeId/agent/conversation are the acquiring turn's identity.
        val acquiredAtMs = currentTimeMs()
        // letta-mobile-kyqdt: TELEMETRY-ONLY. Resolve the permission/process role
        // for this turn once (pure read of the same provider the turn already
        // uses below) so the owner can be stamped with it. This does NOT change
        // the value used by the turn — it is the identical provider result.
        val ownerProcessRole = permissionModeProvider(command).name
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
        com.letta.mobile.util.Telemetry.event(
            "AppServerTurnEngine", "activeTurn.acquired",
            "runtimeId" to command.runtimeId.value,
            "agentId" to command.agentId.value,
            "conversationId" to command.conversationId.value,
            "acquiredAtMs" to acquiredAtMs,
            "processRole" to ownerProcessRole,
            "settleDeadlineMs" to terminalSettleQuietMs,
            "watchdogDeadlineMs" to turnIdleTimeoutMs,
        )

        var collector: kotlinx.coroutines.Job? = null
        // letta-mobile-kyqdt: TELEMETRY-ONLY. Track which path reached the
        // finally so the released event carries a RELEASE REASON. Defaults to a
        // normal completion; overwritten (pure write) if a distinct terminal
        // path is observed. Never gates control flow.
        var releaseReason: String = "normal_completion"
        try {
            val turnPermissionMode = permissionModeProvider(command)
            com.letta.mobile.util.Telemetry.event("IrohTurn", "ensureRuntime.begin", "agent" to command.agentId.value)
            val scope = ensureRuntime(command, turnPermissionMode)
            com.letta.mobile.util.Telemetry.event("IrohTurn", "ensureRuntime.ok", "scopeAgent" to scope.agentId, "scopeConv" to scope.conversationId)
            send(command.startedDraft())

            val collectorReady = CompletableDeferred<Unit>()
            collector = launch {
                try {
                    collectTurnWithIdleWatchdog(scope, command, turnPermissionMode, collectorReady) { draft -> send(draft) }
                } catch (completed: TurnCompletedMarker) {
                    // Flow completed after a terminal App Server lifecycle event.
                    releaseReason = "normal_completion" // letta-mobile-kyqdt: telemetry-only
                } catch (idle: TurnIdleTimedOutMarker) {
                    // No frames for turnIdleTimeoutMs: the App Server never produced a
                    // terminal stop_reason. Force a Failed lifecycle so the UI stops
                    // "Thinking..." and the activeTurn lock is released for the next send.
                    releaseReason = "watchdog_timeout" // letta-mobile-kyqdt: telemetry-only
                    com.letta.mobile.util.Telemetry.event(
                        "IrohTurn", "turn.idle_timeout", "agent" to command.agentId.value, "idleMs" to turnIdleTimeoutMs,
                    )
                    noteOwnerTerminal(RuntimeRunStatus.Failed, source = "idle_timeout") // letta-mobile-kyqdt: telemetry-only
                    send(command.failedDraft("App Server turn idle for ${turnIdleTimeoutMs}ms (no terminal stop_reason)"))
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    // letta-mobile-kyqdt: TELEMETRY-ONLY. The turn was cancelled/
                    // preempted (e.g. abort/scope teardown) before a terminal.
                    // Record the reason then rethrow unchanged — no behavior change.
                    releaseReason = "cancellation"
                    throw cancellation
                } catch (error: Throwable) {
                    // letta-mobile-kyqdt: TELEMETRY-ONLY. Collector failed with a
                    // stream error. Record the reason then rethrow unchanged.
                    releaseReason = "stream_error"
                    throw error
                }
            }
            collectorReady.await()
            client.input(command.toInputCommand(scope))
            com.letta.mobile.util.Telemetry.event("IrohTurn", "input.sent")
            collector.join()
        } finally {
            // letta-mobile-c4igq.1: the busy lock MUST release on every terminal,
            // including stream-death/error. runTurn is a channelFlow; when the
            // collector child throws (e.g. the QUIC/App Server stream dies mid-turn),
            // the channelFlow producer scope is cancelled. cancelAndJoin() below is a
            // SUSPEND call — on the already-cancelled scope it would throw
            // CancellationException and SKIP activeTurn.unlock(), wedging the engine
            // busy until a manual restart. Run the whole cleanup under NonCancellable
            // so the unlock is guaranteed regardless of the terminal outcome.
            withContext(kotlinx.coroutines.NonCancellable) {
            collector?.cancelAndJoin()
            // letta-mobile-kyqdt: P1a RACE FIX (TELEMETRY-ONLY ordering).
            // Snapshot the owner metadata for the release event, but do NOT clear
            // it yet. The mutex must be unlocked FIRST; only AFTER unlock returns
            // do we clear activeTurnOwnerRef. This removes the observable window
            // where a concurrent isBusy pre-check could see the lock still held
            // while the owner was already null ("busy but unknown owner"). The
            // unlock call itself is unchanged and not reordered relative to the
            // rest of the finally — only the pure metadata clear is sequenced to
            // run strictly after it. The clear stays in finally so it still runs
            // even if unlock() throws.
            val releasedOwner = activeTurnOwnerRef.value
            try {
                activeTurn.unlock()
            } finally {
                // Clear STRICTLY after unlock returns (or throws). While the lock
                // is still held-observable, the owner is never null.
                activeTurnOwnerRef.value = null
                com.letta.mobile.util.Telemetry.event(
                    "AppServerTurnEngine", "activeTurn.released",
                    "runtimeId" to command.runtimeId.value,
                    "agentId" to command.agentId.value,
                    "conversationId" to command.conversationId.value,
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
        emitDraft: suspend (RuntimeEventDraft) -> Unit,
    ) = coroutineScope {
        val lastFrameAt = kotlinx.atomicfu.atomic(currentTimeMs())
        val watchdog = this.launch {
            while (true) {
                val idleFor = currentTimeMs() - lastFrameAt.value
                val remaining = turnIdleTimeoutMs - idleFor
                if (remaining <= 0) {
                    throw TurnIdleTimedOut
                }
                delay(remaining)
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
        // letta-mobile-kyqdt: TELEMETRY-ONLY. Seq of the frame currently being
        // processed, and the seq of the frame that produced the pending
        // completed terminal (so the delayed settle can record it). Pure reads.
        var currentFrameSeq: Long? = null
        var pendingCompletedSeq: Long? = null
        
        // letta-mobile-oqfbj: track emitted and returned tool_call_ids for settlement
        val emittedToolCallIds = mutableSetOf<String>()
        val returnedToolCallIds = mutableSetOf<String>()

        suspend fun flushTail() {
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
                        )
                        com.letta.mobile.util.Telemetry.event(
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
                            )
                            throw TurnCompleted
                        }
                    }
                    return@collect
                }
                lastFrameAt.value = currentTimeMs()
                // letta-mobile-kyqdt: P1b RUN-ID PROMOTION (TELEMETRY-ONLY).
                // Once the mapper reveals the server run id for this active turn,
                // promote it into the owner via a pure copy(runId=…). This is the
                // same place the engine learns the real run id (frames carry
                // run_id → draft.runId); we do not alter that promotion flow.
                val frameSeq = received.eventSeqOrNull()
                currentFrameSeq = frameSeq
                val drafts = mapper.map(command, received)
                drafts.firstOrNull { it.runId != null }?.runId?.value?.let { promoteOwnerRunId(it) }
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
                        is RuntimeEventPayload.ApprovalRequested -> emittedToolCallIds.add(payload.request.callId.value)
                        is RuntimeEventPayload.ToolReturnObserved -> returnedToolCallIds.add(payload.toolCallId.value)
                        is RuntimeEventPayload.RemoteStreamFrame -> {
                            // Extract tool_call_id from tool_call_message and approval_request_message frames
                            extractToolCallId(payload.body)?.let { emittedToolCallIds.add(it) }
                            // Extract returned tool_call_id from tool_return_message frames
                            if (payload.messageType == "tool_return_message") {
                                extractToolCallId(payload.body)?.let { returnedToolCallIds.add(it) }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
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
        runId: com.letta.mobile.runtime.RunId?,
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
        com.letta.mobile.util.Telemetry.event(
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
        com.letta.mobile.util.Telemetry.event("IrohTurn", "runtimeStart.response", "success" to response.success, "hasRuntime" to (response.runtime != null), "error" to response.error)
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
                                ?: kotlinx.serialization.json.JsonPrimitive(turnInput.text),
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
                            com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision.Allow(
                                message = turnInput.decision.response,
                            )
                        }
                        ToolApprovalDecisionValue.Denied,
                        ToolApprovalDecisionValue.TimedOut,
                        -> com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision.Deny(
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

    private fun TurnCommand.completedDraft(runId: com.letta.mobile.runtime.RunId?): RuntimeEventDraft =
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

    private fun com.letta.mobile.data.transport.appserver.AppServerReceivedFrame.matches(
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
    private fun com.letta.mobile.data.transport.appserver.AppServerReceivedFrame.eventSeqOrNull(): Long? =
        when (val f = frame) {
            is com.letta.mobile.data.transport.appserver.AppServerInboundFrame.StreamDelta -> f.eventSeq
            is com.letta.mobile.data.transport.appserver.AppServerInboundFrame.UpdateLoopStatus -> f.eventSeq
            is com.letta.mobile.data.transport.appserver.AppServerInboundFrame.UpdateDeviceStatus -> f.eventSeq
            is com.letta.mobile.data.transport.appserver.AppServerInboundFrame.UpdateQueue -> f.eventSeq
            is com.letta.mobile.data.transport.appserver.AppServerInboundFrame.UpdateSubagentState -> f.eventSeq
            else -> null
        }

    /**
     * letta-mobile-kyqdt: TELEMETRY-ONLY. Best-effort check whether a received
     * frame CARRIES a terminal signal (stop_reason / error / terminal
     * lifecycle), used only to record the scope-match decision for
     * terminal-bearing frames that were rejected by matches(scope). Pure read of
     * the frame's delta message_type; never gates control flow.
     */
    private fun com.letta.mobile.data.transport.appserver.AppServerReceivedFrame.carriesTerminal(): Boolean {
        val streamDelta = frame as? com.letta.mobile.data.transport.appserver.AppServerInboundFrame.StreamDelta
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
            
            com.letta.mobile.util.Telemetry.event(
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
    ) {
        val current = activeTurnOwnerRef.value ?: return
        activeTurnOwnerRef.value = current.copy(
            lastTerminal = status.name,
            lastTerminalSource = source ?: current.lastTerminalSource,
            lastTerminalAtMs = currentTimeMs(),
            lastTerminalSeq = seq ?: current.lastTerminalSeq,
            lastTerminalScopeMatched = scopeMatched ?: current.lastTerminalScopeMatched,
        )
    }

    /**
     * letta-mobile-kyqdt: TELEMETRY-ONLY. Promotes the server-assigned/promoted
     * run id into the active-turn owner (if one is set and not yet stamped with
     * a run id). Pure `copy(runId=…)` metadata write — no control-flow, no lock
     * interaction, no effect on emitted drafts or the run-id promotion path.
     */
    private fun promoteOwnerRunId(runId: String) {
        val current = activeTurnOwnerRef.value ?: return
        if (current.runId == runId) return
        activeTurnOwnerRef.value = current.copy(runId = runId)
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
    ) {
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

        fun defaultRequestId(): String {
            nextRequestId += 1
            return "app-server-${nextRequestId}"
        }
    }
}
