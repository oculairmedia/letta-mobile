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
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
        var sawToolReturn = false
        var sawAssistantAfterToolReturn = false
        
        // letta-mobile-oqfbj: track emitted and returned tool_call_ids for settlement
        val emittedToolCallIds = mutableSetOf<String>()
        val returnedToolCallIds = mutableSetOf<String>()

        suspend fun flushTail() {
            pendingStop?.let { emitDraft(it) }
            pendingStop = null
            pendingUsage?.let { emitDraft(it) }
            pendingUsage = null
        }

        fun rescheduleCompletedTerminal() {
            val terminal = pendingCompleted ?: return
            terminalSettleJob?.cancel()
            terminalSettleJob = launch {
                delay(terminalSettleQuietMs)
                // letta-mobile-oqfbj: settle dangling calls before the delayed
                // completed terminal too.
                settleDanglingToolCalls(command, emittedToolCallIds, returnedToolCallIds, emitDraft, "Tool execution interrupted by turn completion")
                flushTail()
                emitDraft(terminal)
                throw TurnCompleted
            }
        }

        var turnEndReason: String? = null
        try {
            collectorReady.complete(Unit)
            client.events.collect { received ->
                if (!received.matches(scope)) return@collect
                lastFrameAt.value = currentTimeMs()
                val drafts = mapper.map(command, received)
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
                        rescheduleCompletedTerminal()
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
                    
                    if (draft.isToolReturnFrame()) sawToolReturn = true
                    if (sawToolReturn && draft.isAssistantFrame()) sawAssistantAfterToolReturn = true
                    if (draft.isStopReasonFrame()) {
                        pendingStop = draft
                        return@forEach
                    }
                    if (draft.isUsageStatisticsFrame()) {
                        if (pendingUsage == null) pendingUsage = draft
                        if (sawAssistantAfterToolReturn) {
                            // letta-mobile-oqfbj: settle dangling calls before the
                            // synthesized post-tool completion.
                            settleDanglingToolCalls(command, emittedToolCallIds, returnedToolCallIds, emitDraft, "Tool execution interrupted by turn completion")
                            flushTail()
                            emitDraft(command.completedDraft(draft.runId))
                            throw TurnCompleted
                        }
                        return@forEach
                    }
                    if (draft.isCompletedLifecycle()) {
                        pendingCompleted = draft
                        rescheduleCompletedTerminal()
                        return@forEach
                    }
                    // letta-mobile-oqfbj: settle dangling calls BEFORE the tail +
                    // terminal lifecycle so tool cards resolve to error instead of
                    // spinning and the transcript keeps matched call/return pairs.
                    if (draft.isTerminalLifecycle()) {
                        settleDanglingToolCalls(command, emittedToolCallIds, returnedToolCallIds, emitDraft, "Tool execution interrupted by turn termination")
                        flushTail()
                        emitDraft(draft)
                        throw TurnCompleted
                    }
                    emitDraft(draft)
                    rescheduleCompletedTerminal()
                }
            }
        } catch (idle: TurnIdleTimedOutMarker) {
            // letta-mobile-oqfbj: settle before emitting the failed draft
            settleDanglingToolCalls(command, emittedToolCallIds, returnedToolCallIds, emitDraft, "Tool execution interrupted by turn timeout")
            throw idle
        } catch (e: kotlinx.coroutines.CancellationException) {
            // letta-mobile-oqfbj: settle on cancellation/abort
            turnEndReason = "Tool execution interrupted by cancellation"
            throw e
        } catch (e: Throwable) {
            // letta-mobile-oqfbj: settle on collector failure
            turnEndReason = "Tool execution interrupted by stream error"
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
     * Called from the collector's finally block (abnormal exit: cancel, timeout, collector
     * error) AND from the terminal lifecycle path (when a TERMINAL failure arrives from the
     * server with dangling calls still in flight).
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
        const val DEFAULT_TERMINAL_SETTLE_QUIET_MS: Long = 1_500L

        fun defaultRequestId(): String {
            nextRequestId += 1
            return "app-server-${nextRequestId}"
        }
    }
}
