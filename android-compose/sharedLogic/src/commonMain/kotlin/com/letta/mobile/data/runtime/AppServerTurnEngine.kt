package com.letta.mobile.data.runtime

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
) : TurnEngine {
    private val activeTurn = Mutex()
    private var runtime: AppServerRuntimeScope? = null

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

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = channelFlow {
        if (!activeTurn.tryLock()) {
            throw IllegalStateException("An App Server turn is already active for ${command.runtimeId.value}.")
        }

        var collector: kotlinx.coroutines.Job? = null
        try {
            com.letta.mobile.util.Telemetry.event("IrohTurn", "ensureRuntime.begin", "agent" to command.agentId.value)
            val scope = ensureRuntime(command)
            com.letta.mobile.util.Telemetry.event("IrohTurn", "ensureRuntime.ok", "scopeAgent" to scope.agentId, "scopeConv" to scope.conversationId)
            send(command.startedDraft())

            val collectorReady = CompletableDeferred<Unit>()
            collector = launch {
                try {
                    collectTurnWithIdleWatchdog(scope, command, collectorReady) { draft -> send(draft) }
                } catch (completed: TurnCompletedMarker) {
                    // Flow completed after a terminal App Server lifecycle event.
                } catch (idle: TurnIdleTimedOutMarker) {
                    // No frames for turnIdleTimeoutMs: the App Server never produced a
                    // terminal stop_reason. Force a Failed lifecycle so the UI stops
                    // "Thinking..." and the activeTurn lock is released for the next send.
                    com.letta.mobile.util.Telemetry.event(
                        "IrohTurn", "turn.idle_timeout", "agent" to command.agentId.value, "idleMs" to turnIdleTimeoutMs,
                    )
                    send(command.failedDraft("App Server turn idle for ${turnIdleTimeoutMs}ms (no terminal stop_reason)"))
                }
            }
            collectorReady.await()
            client.input(command.toInputCommand(scope))
            com.letta.mobile.util.Telemetry.event("IrohTurn", "input.sent")
            collector.join()
        } finally {
            collector?.cancelAndJoin()
            activeTurn.unlock()
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
        try {
            collectorReady.complete(Unit)
            client.events.collect { received ->
                if (!received.matches(scope)) return@collect
                lastFrameAt.value = currentTimeMs()
                val drafts = mapper.map(command, received)
                drafts.forEach { draft ->
                    emitDraft(draft)
                    if (draft.isTerminalLifecycle()) throw TurnCompleted
                }
            }
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun ensureRuntime(command: TurnCommand): AppServerRuntimeScope {
        runtime?.let { cached ->
            if (cached.matches(command)) return cached
        }
        val response = client.runtimeStart(
            AppServerCommand.RuntimeStart(
                requestId = requestIdFactory(),
                agentId = command.agentId.value,
                conversationId = command.conversationId.value,
                mode = permissionMode,
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

    private fun com.letta.mobile.data.transport.appserver.AppServerReceivedFrame.matches(
        scope: AppServerRuntimeScope,
    ): Boolean {
        val eventRuntime = frame.runtime ?: return true
        return eventRuntime.agentId == scope.agentId &&
            eventRuntime.conversationId == scope.conversationId
    }

    private object TurnCompleted : TurnCompletedMarker()
    private sealed class TurnCompletedMarker : Throwable()

    private object TurnIdleTimedOut : TurnIdleTimedOutMarker()
    private sealed class TurnIdleTimedOutMarker : Throwable()

    private fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private companion object {
        private var nextRequestId = 0

        // 90s idle window: long enough for a slow first token / tool round-trip,
        // short enough that a permanently-stuck turn frees the engine before the
        // user gives up. Idle-based (reset per frame), so a long actively-streaming
        // turn never trips. Tunable via the ctor param.
        const val DEFAULT_TURN_IDLE_TIMEOUT_MS: Long = 300_000L

        fun defaultRequestId(): String {
            nextRequestId += 1
            return "app-server-${nextRequestId}"
        }
    }
}
