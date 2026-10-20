package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.subagent.ParentContext
import com.letta.mobile.data.repository.subagent.SubagentCorrelator
import com.letta.mobile.data.runtime.AppServerRuntimeEventMapper
import com.letta.mobile.data.runtime.TurnBoundaryGate
import com.letta.mobile.data.subagents.SubagentParentProjection
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerStopReason
import com.letta.mobile.data.transport.appserver.AppServerTurnBoundary
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generation-bound observer ingestion collaborator for [IrohChannelTransport].
 *
 * Encapsulates:
 * - Observer stream frame collection bound to a connection generation
 * - Mapping received StreamDelta events to ServerFrame projections via [AppServerRuntimeEventMapper] and [RuntimeEventServerFrameMapper]
 * - Dual-ingest and terminal deduplication via [IrohTurnRegistry]
 * - Parent `Agent` tool_call subagent correlation via [SubagentCorrelator]
 * - Tracked message list view path and re-subscription on reconnect
 */
internal class IrohObserverIngestor(
    private val scope: CoroutineScope,
    private val turnRegistry: IrohTurnRegistry,
    private val connectionGeneration: () -> Long,
    private val emitBoth: suspend (ServerFrame) -> Unit,
    private val adminRpc: suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse,
    private val recordFrameOwnership: (conversationId: String, localTurn: IrohActiveTurn?) -> Unit,
    private val observerMapper: AppServerRuntimeEventMapper = AppServerRuntimeEventMapper(),
    internal val subagentCorrelator: SubagentCorrelator = SubagentCorrelator(),
    // letta-mobile-p0gc: per-turn aggregated skip telemetry + cheap engine-owned
    // terminal gating; injectable sink keeps the aggregation deterministic under test.
    internal val engineOwnedSkipTelemetry: EngineOwnedSkipTelemetry = EngineOwnedSkipTelemetry(),
    /** How long the engine has to publish its own terminal before the observer's copy stands in. */
    private val observerTerminalGraceMs: Long = OBSERVER_TERMINAL_GRACE_MS,
) {
    /** Observer terminal fallbacks waiting out their grace window, one per engine-owned turn. */
    private val pendingObserverTerminals = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val observedTurns = ObservedTurnTracker()

    private val observerGeneration = atomic(0)

    @Volatile
    private var observerJob: Job? = null

    @Volatile
    internal var viewedConversationId: String? = null
        private set

    @Volatile
    internal var viewedMessageListPath: String? = null
        private set

    @Volatile
    private var resubscribeJob: Job? = null

    val isIngesting: Boolean get() = observerJob?.isActive == true
    val currentObserverGeneration: Int get() = observerGeneration.value

    fun start(request: ObserverStartRequest) {
        val handle = request.handle
        val generation = request.generation
        val streamFrames = handle.effectiveObserverStreamFrames
        if (streamFrames == null) {
            stop(ObserverStopRequest("no_observer_stream"))
            Telemetry.event("IrohObserver", "ingest.unavailable", "sessionId" to handle.sessionId)
            return
        }
        observerJob?.cancel()
        Telemetry.event(
            "IrohObserver", "ingest.start",
            "sessionId" to handle.sessionId,
            "generation" to generation.toString(),
        )
        observerJob = scope.launch {
            runCatching {
                streamFrames.collect { received ->
                    if (connectionGeneration() != generation) return@collect
                    ingestObserverFrame(ObserverFrameRequest(received, generation))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Telemetry.event(
                    "IrohObserver", "ingest.failed",
                    "error" to (error.message ?: error.toString()),
                    "class" to error::class.simpleName,
                )
            }
        }
    }

    fun stop(request: ObserverStopRequest) {
        val job = observerJob ?: return
        observerJob = null
        observerGeneration.incrementAndGet()
        job.cancel()
        pendingObserverTerminals.values.forEach { it.cancel() }
        pendingObserverTerminals.clear()
        observedTurns.reset()
        Telemetry.event("IrohObserver", "ingest.stop", "reason" to request.reason)
    }

    fun recordViewedConversationFrom(request: ViewedConversationRequest) {
        if (request.method != "message.list") return
        val conversationId = conversationIdFromMessageListPath(request.path) ?: return
        viewedConversationId = conversationId
        viewedMessageListPath = request.path
    }

    fun reSubscribeViewedConversation(request: ObserverResubscribeRequest) {
        val subscription = viewedMessageListPath?.let { path ->
            ObserverSubscription(path = path, conversationId = viewedConversationId, generation = request.generation)
        } ?: return
        resubscribeJob?.cancel()
        resubscribeJob = scope.launch { resubscribe(subscription) }
    }

    /**
     * Meridian's device-wide `agent_updated` / `conversation_updated` pushes (not App Server
     * messages, so they decode as [AppServerInboundFrame.Unknown]) are republished as
     * [ServerFrame.AgentUpdated] / [ServerFrame.ConversationUpdated], the frames the agent and
     * conversation repositories react to. The host registry's `subagents_updated`
     *  takes the same path to `SubagentRepository`.
     * Returns true when [received] was one of them.
     */
    private suspend fun republishDevicePush(received: AppServerReceivedFrame): Boolean {
        val unknown = received.frame as? AppServerInboundFrame.Unknown ?: return false
        if (unknown.type !in HOST_PUSH_TYPES) return false
        val frame = runCatching {
            DEVICE_PUSH_JSON.decodeFromJsonElement(com.letta.mobile.data.transport.ServerFrameSerializer, received.raw)
        }.getOrNull()?.takeIf {
            it is ServerFrame.AgentUpdated || it is ServerFrame.ConversationUpdated || it is ServerFrame.SubagentsUpdated ||
                it is ServerFrame.PendingApprovalsUpdated || it is ServerFrame.PermissionModeUpdated
        }
        if (frame == null) {
            Telemetry.event("IrohObserver", "${unknown.type}.undecodable", level = Telemetry.Level.WARN)
            return true
        }
        emitBoth(frame)
        return true
    }

    suspend fun ingestObserverFrame(request: ObserverFrameRequest) {
        if (request.expectedGeneration != null && connectionGeneration() != request.expectedGeneration) return
        val received = request.received
        if (republishDevicePush(received)) return
        when (val frame = received.frame) {
            is AppServerInboundFrame.StreamDelta -> ingestStreamDelta(frame, received)
            is AppServerInboundFrame.TurnFinished -> ingestTurnFinished(frame, received)
            is AppServerInboundFrame.UpdateLoopStatus -> ingestLoopStatus(frame)
            else -> Unit
        }
    }

    private suspend fun ingestStreamDelta(streamDelta: AppServerInboundFrame.StreamDelta, received: AppServerReceivedFrame) {
        if (!streamDelta.subagentId.isNullOrBlank()) {
            observeChildActivity(streamDelta)
            return
        }
        val scope = observerScope(streamDelta.runtime)
        recordFrameOwnership(scope.conversationId, scope.localTurn)
        if (scope.localTurn != null) {
            observedTurns.clearConversation(scope.conversationId)
            // letta-mobile-p0gc: ONE aggregated skip event per turn (delta types +
            // counts) instead of one log line per frame.
            engineOwnedSkipTelemetry.record(
                conversationId = scope.conversationId,
                turnId = scope.localTurn.turnId,
                deltaType = engineOwnedDeltaType(streamDelta),
            )
            projectEngineOwnedObserverDeltaIfTerminalCandidate(scope, streamDelta, received)
            return
        }
        if (isRetiredObserverRun(streamDelta, scope.conversationId)) return
        correlateAgentFrame(streamDelta)
        projectPassiveObserverDelta(scope, sanitizeAgentReturn(streamDelta, received))
    }

    private suspend fun ingestTurnFinished(
        turnFinished: AppServerInboundFrame.TurnFinished,
        received: AppServerReceivedFrame,
    ) {
        if (!received.raw.string("subagent_id").isNullOrBlank()) return
        val scope = observerScope(turnFinished.runtime)
        recordFrameOwnership(scope.conversationId, scope.localTurn)
        if (scope.localTurn != null) {
            observedTurns.clearConversation(scope.conversationId)
            engineOwnedSkipTelemetry.record(
                conversationId = scope.conversationId,
                turnId = scope.localTurn.turnId,
                deltaType = "turn_finished",
            )
            projectEngineOwnedObserverDelta(scope, received)
            engineOwnedSkipTelemetry.endTurn()
            return
        }
        projectPassiveObserverTurnFinished(scope, turnFinished, received)
    }

    private suspend fun ingestLoopStatus(loopStatusFrame: AppServerInboundFrame.UpdateLoopStatus) {
        val scope = observerScope(loopStatusFrame.runtime)
        recordFrameOwnership(scope.conversationId, scope.localTurn)
        if (scope.localTurn != null) {
            observedTurns.clearConversation(scope.conversationId)
            return
        }
        val terminal = observedTurns.completeOnIdleLoop(scope.conversationId, loopStatusFrame) ?: return
        emitBoth(terminal)
    }

    /**
     *  (causal slice C): the ONLY thing the observer does with
     * an engine-owned delta is look for the local turn's [ServerFrame.TurnDone]
     * terminal fallback. Full mapping is therefore worth running ONLY for delta
     * shapes that can ever produce a TurnDone through
     * [AppServerRuntimeEventMapper] + [RuntimeEventServerFrameMapper]:
     * `stop_reason` (terminal stop reasons → Completed/Cancelled/Failed),
     * `loop_error` / `error_message` (→ Failed). Everything else maps to
     * RemoteStreamFrame / ToolCallObserved / ToolReturnObserved payloads —
     * never a TurnDone — so we skip the mapping entirely instead of
     * project-mapping every streaming token only to discard the result.
     *
     * Safety: an UNCLASSIFIABLE shape (non-object delta or missing
     * `message_type`) conservatively falls back to FULL mapping, preserving
     * exactly the pre-change behavior for anything this cheap predicate cannot
     * prove non-terminal. The observer terminal fallback itself is untouched:
     * candidates still run the full mapper pipeline and claim/emit path.
     */
    private suspend fun projectEngineOwnedObserverDeltaIfTerminalCandidate(
        scope: ObserverProjectionScope,
        streamDelta: AppServerInboundFrame.StreamDelta,
        received: AppServerReceivedFrame,
    ) {
        if (scope.localTurn == null) return
        if (!isTerminalCandidate(streamDelta)) return
        projectEngineOwnedObserverDelta(scope, received)
        // A terminal candidate ends the observer's interest in this turn —
        // flush the per-turn skip aggregate now rather than at some later turn.
        engineOwnedSkipTelemetry.endTurn()
    }

    private fun isTerminalCandidate(streamDelta: AppServerInboundFrame.StreamDelta): Boolean {
        val delta = streamDelta.delta as? JsonObject ?: return true
        return when (delta.string("message_type")) {
            null -> true
            "stop_reason", "loop_error", "error_message" -> true
            else -> false
        }
    }

    private fun engineOwnedDeltaType(streamDelta: AppServerInboundFrame.StreamDelta): String {
        val delta = streamDelta.delta as? JsonObject ?: return "<non-object>"
        return delta.string("message_type") ?: "<untyped>"
    }

    private suspend fun resubscribe(subscription: ObserverSubscription) {
        if (connectionGeneration() != subscription.generation) return
        Telemetry.event(
            "IrohObserver", "resubscribe.begin",
            "conversationId" to (subscription.conversationId ?: ""),
            "generation" to subscription.generation.toString(),
        )
        runCatching {
            if (connectionGeneration() != subscription.generation) return
            adminRpc("message.list", subscription.path, null)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Telemetry.event(
                "IrohObserver", "resubscribe.failed",
                "conversationId" to (subscription.conversationId ?: ""),
                "error" to (error.message ?: error.toString()),
                "class" to error::class.simpleName,
            )
        }
    }

    private fun observerScope(runtime: AppServerRuntimeScope): ObserverProjectionScope =
        ObserverProjectionScope(
            agentId = runtime.agentId,
            conversationId = runtime.conversationId,
            localTurn = turnRegistry.getActiveTurn(IrohConversationId(runtime.conversationId)),
        )

    private fun isRetiredObserverRun(
        streamDelta: AppServerInboundFrame.StreamDelta,
        conversationId: String,
    ): Boolean {
        val delta = streamDelta.delta as? JsonObject
        val runId = delta?.string("run_id") ?: delta?.string("runId") ?: return false
        if (!turnRegistry.isRetiredRun(IrohRunId(runId))) return false
        Telemetry.event("IrohObserver", "ingest.skip_already_retired", "conversationId" to conversationId, "runId" to runId)
        return true
    }

    private fun isRetiredObserverTurnFinished(
        turnFinished: AppServerInboundFrame.TurnFinished,
        scope: ObserverProjectionScope,
    ): Boolean {
        val run = turnFinished.runId?.takeIf { it.isNotBlank() }
        val turn = turnFinished.turnId.takeIf { it.isNotBlank() }
        val retiredRun = run != null && turnRegistry.isRetiredRun(IrohRunId(run))
        val retiredTurn = turn != null && turnRegistry.isRetiredTurn(IrohTurnId(turn))
        if (!retiredRun && !retiredTurn) return false
        Telemetry.event(
            "IrohObserver", "ingest.skip_already_retired",
            "conversationId" to scope.conversationId,
            "runId" to (run ?: ""),
        )
        return true
    }

    private suspend fun projectPassiveObserverDelta(scope: ObserverProjectionScope, received: AppServerReceivedFrame) {
        val delta = (received.frame as? AppServerInboundFrame.StreamDelta)?.delta as? JsonObject
        val wireTurn = wireTurnId(received)
        val wireRun = (delta?.string("run_id") ?: delta?.string("runId"))?.takeIf { it.isNotBlank() }
        val active = observedTurns.active(scope.conversationId)
        val effectiveTurn = wireTurn ?: active?.turnId
        val effectiveRun = wireRun ?: active?.runId
        if (isTerminalDeltaObject(delta) && observedTurns.isCompleted(effectiveTurn, effectiveRun)) return
        // The observer has no turn of its own: a row is named by the host's stamp on the frame, and the
        // context carries only what the frame itself says (or the active observed turn for unstamped
        // lifecycle frames like stop_reason / usage_statistics / error_message).
        val frames = projectObserverFrames(scope, received, effectiveTurn, effectiveRun)
        frames.forEach { emitBoth(it) }
        if (frames.any { it is ServerFrame.TurnDone }) {
            observedTurns.markCompleted(scope.conversationId, effectiveTurn, effectiveRun)
        } else if (delta != null) {
            observedTurns.recordDelta(scope, delta, wireTurn, wireRun)
        }
    }

    private suspend fun projectPassiveObserverTurnFinished(
        scope: ObserverProjectionScope,
        turnFinished: AppServerInboundFrame.TurnFinished,
        received: AppServerReceivedFrame,
    ) {
        if (isRetiredObserverTurnFinished(turnFinished, scope)) {
            observedTurns.clearConversation(scope.conversationId)
            return
        }
        val active = observedTurns.active(scope.conversationId)
        val effectiveTurn = turnFinished.turnId.takeIf { it.isNotBlank() } ?: active?.turnId
        val effectiveRun = turnFinished.runId?.takeIf { it.isNotBlank() } ?: active?.runId
        if (observedTurns.consumeCompletedTurnFinished(scope.conversationId, effectiveTurn, effectiveRun)) return
        val frames = projectObserverFrames(scope, received, effectiveTurn, effectiveRun)
        frames.forEach { emitBoth(it) }
        if (frames.any { it is ServerFrame.TurnDone }) {
            observedTurns.markCompleted(scope.conversationId, effectiveTurn, effectiveRun)
        }
    }

    private fun projectObserverFrames(
        scope: ObserverProjectionScope,
        received: AppServerReceivedFrame,
        turn: String?,
        run: String?,
    ): List<ServerFrame> =
        observerMapper.map(observerTurnCommand(scope.agentId, scope.conversationId), received).flatMap { draft ->
            RuntimeEventServerFrameMapper.map(
                payload = draft.payload,
                context = RuntimeEventServerFrameMapper.Context(
                    agentId = draft.agentId?.value ?: scope.agentId,
                    conversationId = draft.conversationId?.value ?: scope.conversationId,
                    turnId = turn,
                    runId = draft.runId?.value ?: run,
                ),
            )
        }

    private fun isTerminalDeltaObject(delta: JsonObject?): Boolean = when (delta?.string("message_type")) {
        "stop_reason", "loop_error", "error_message" -> true
        else -> false
    }

    private fun wireTurnId(received: AppServerReceivedFrame): String? = when (val frame = received.frame) {
        is AppServerInboundFrame.StreamDelta -> (frame.delta as? JsonObject)?.string("turn_id")?.takeIf { it.isNotBlank() }
        is AppServerInboundFrame.TurnFinished -> frame.turnId.takeIf { it.isNotBlank() }
        else -> null
    }

    private suspend fun projectEngineOwnedObserverDelta(
        scope: ObserverProjectionScope,
        received: AppServerReceivedFrame,
    ) {
        val localTurn = scope.localTurn ?: return
        val command = observerTurnCommand(scope.agentId, scope.conversationId)
        val projectedFrames = observerMapper.map(command, received).flatMap { draft ->
            RuntimeEventServerFrameMapper.map(
                payload = draft.payload,
                context = RuntimeEventServerFrameMapper.Context(
                    agentId = draft.agentId?.value ?: scope.agentId,
                    conversationId = draft.conversationId?.value ?: scope.conversationId,
                    turnId = localTurn.turnId,
                    runId = draft.runId?.value ?: localTurn.runId,
                ),
            )
        }
        claimObserverTerminal(localTurn, projectedFrames)
    }

    /**
     * The observer's copy of an engine-owned turn's terminal is a safety net, not a race entry.
     *
     * The wrapper completes a turn on the idle loop status, and its fanned-out stop_reason reaches
     * this path before the engine has projected the reply it streamed. Claiming here at once retired
     * the turn ahead of its own deltas (device capture 2026-09-24, local-run-55: StopReason and
     * TurnDone at 20:48:03.573, every assistant delta after them), so the whole reply reached the
     * coordinator as a retired turn's tail and the timeline's turn-end repair was refused.
     *
     * The engine publishes its own terminal (turn_finished, or its settle window), so the observer
     * waits [observerTerminalGraceMs] and stands in only if the engine still has not. The claim is
     * exactly-once, so an engine terminal inside the window simply wins it. Assistant/reasoning
     * deltas stay engine-owned and are never re-emitted here.
     */
    private fun claimObserverTerminal(localTurn: IrohActiveTurn, projectedFrames: List<ServerFrame>) {
        val terminal = projectedFrames.firstOrNull { it is ServerFrame.TurnDone } as? ServerFrame.TurnDone ?: return
        if (localTurn.hasTerminal) return
        val endingFrames = projectedFrames.filter { it is ServerFrame.StopReason || it is ServerFrame.UsageStatistics }
        val key = localTurn.turnId
        if (pendingObserverTerminals[key]?.isActive == true) return
        Telemetry.event(
            "IrohObserver", "terminal.fallback_scheduled",
            "conversationId" to localTurn.conversationId,
            "turnId" to key,
            "graceMs" to observerTerminalGraceMs,
        )
        val job = scope.launch {
            kotlinx.coroutines.delay(observerTerminalGraceMs)
            publishObserverTerminal(localTurn, terminal, endingFrames)
        }
        pendingObserverTerminals[key] = job
        job.invokeOnCompletion { pendingObserverTerminals.remove(key, job) }
    }

    private suspend fun publishObserverTerminal(
        localTurn: IrohActiveTurn,
        terminal: ServerFrame.TurnDone,
        endingFrames: List<ServerFrame>,
    ) {
        val publication = IrohTerminalPublication(
            turn = localTurn,
            status = IrohTerminalStatus(terminal.status),
            source = IrohTerminalSource.Observer,
        )
        if (!turnRegistry.claimTerminal(publication)) {
            Telemetry.event(
                "IrohObserver", "terminal.fallback_superseded",
                "conversationId" to localTurn.conversationId,
                "turnId" to localTurn.turnId,
                "terminalSource" to (localTurn.terminalSource?.toString() ?: ""),
            )
            return
        }
        Telemetry.event(
            "IrohObserver", "terminal.fallback_claimed",
            "conversationId" to localTurn.conversationId,
            "turnId" to localTurn.turnId,
            level = Telemetry.Level.WARN,
        )
        // The turn's ending (stop reason / usage) goes ahead of the TurnDone, or the coordinator
        // retires the turn with an empty stop reason.
        withContext(NonCancellable) {
            endingFrames.forEach { emitBoth(it) }
            emitBoth(terminal)
            turnRegistry.retireClaimed(publication)
        }
    }

    /**
     * Tracks `Agent` dispatches and returns so [sanitizeAgentReturn] can recognise a subagent's
     * return. The host's `subagents_updated` push is the only registry
     * writer; the client correlator emits nothing.
     */
    private fun correlateAgentFrame(streamDelta: AppServerInboundFrame.StreamDelta) {
        runCatching { correlateAgentDelta(streamDelta) }
    }

    private fun correlateAgentDelta(streamDelta: AppServerInboundFrame.StreamDelta) {
        val delta = streamDelta.delta as? JsonObject ?: return
        val messageType = delta.string("message_type") ?: return
        val toolCall = delta["tool_call"]?.jsonObject
        val parent = ParentContext(
            agentId = streamDelta.runtime.agentId,
            conversationId = streamDelta.runtime.conversationId,
            runId = delta.string("run_id"),
        )
        when (messageType) {
            "tool_call_message", "approval_request_message" -> processAgentDispatch(delta, toolCall, parent)
            "tool_return_message" -> processAgentReturn(delta, toolCall, parent)
        }
    }

    private fun processAgentDispatch(
        delta: JsonObject,
        toolCall: JsonObject?,
        parent: ParentContext,
    ) {
        val name = toolCall?.string("name") ?: return
        if (name != "Agent") return
        val toolCallId = toolCall.string("tool_call_id") ?: delta.string("tool_call_id") ?: return
        val arguments = toolCall["arguments"]?.toString() ?: delta["arguments"]?.toString()
        subagentCorrelator.onAgentDispatch(toolCallId, arguments, parent)
    }

    private fun processAgentReturn(
        delta: JsonObject,
        toolCall: JsonObject?,
        parent: ParentContext,
    ) {
        val toolCallId = toolCall?.string("tool_call_id") ?: delta.string("tool_call_id") ?: return
        subagentCorrelator.onDispatchReturn(toolCallId, parent)
    }

    private fun observeChildActivity(streamDelta: AppServerInboundFrame.StreamDelta) {
        val line = SubagentParentProjection.activityLine(streamDelta.delta) ?: return
        Telemetry.event(
            "IrohObserver",
            "subagent.activity_suppressed",
            "subagentId" to streamDelta.subagentId.orEmpty(),
            "conversationId" to streamDelta.runtime.conversationId,
            "activityBytes" to line.encodeToByteArray().size,
        )
    }

    private fun sanitizeAgentReturn(
        streamDelta: AppServerInboundFrame.StreamDelta,
        received: AppServerReceivedFrame,
    ): AppServerReceivedFrame {
        val delta = streamDelta.delta as? JsonObject ?: return received
        if (delta.string("message_type") != "tool_return_message") return received
        val toolCallId = delta.string("tool_call_id") ?: return received
        val body = listOf("tool_return", "output", "result")
            .firstNotNullOfOrNull { key -> delta[key]?.toString() }
            .orEmpty()
        val explicitlySubagent = body.contains("<task-notification", ignoreCase = true) ||
            delta["subagent_id"] != null || delta["task_id"] != null
        if (!explicitlySubagent && subagentCorrelator.snapshot().none { it.toolCallId == toolCallId }) return received
        val sanitized = SubagentParentProjection.sanitizedAgentReturn(
            delta = delta,
            conversationId = streamDelta.runtime.conversationId,
            messageId = delta.string("id"),
        )
        val frame = streamDelta.copy(delta = sanitized)
        val raw = JsonObject(received.raw.toMutableMap().apply { this["delta"] = sanitized })
        return received.copy(frame = frame, raw = raw)
    }

    fun currentSubagentScope(): SubagentRpcScope? {
        val conversationId = viewedConversationId ?: return null
        val agentId = turnRegistry.getActiveTurn(IrohConversationId(conversationId))?.agentId
        return SubagentRpcScope(conversationId, agentId)
    }

    fun reset() {
        resubscribeJob?.cancel()
        resubscribeJob = null
        subagentCorrelator.reset()
        observedTurns.reset()
        engineOwnedSkipTelemetry.endTurn()
    }

    private fun conversationIdFromMessageListPath(path: String): String? {
        val marker = "/v1/conversations/"
        val start = path.indexOf(marker)
        if (start < 0) return null
        val after = path.substring(start + marker.length)
        val id = after.substringBefore('/').substringBefore('?')
        return id.takeIf { it.isNotBlank() }
    }

    private data class ObserverSubscription(
        val path: String,
        val conversationId: String?,
        val generation: Long,
    )

    companion object {
        /** Meridian's device-wide agent change push; see AgentChangeNotifier on the host. */
        private const val AGENT_UPDATED_TYPE = "agent_updated"

        /** Meridian's device-wide conversation change push; see ConversationChangeNotifier on the host. */
        private const val CONVERSATION_UPDATED_TYPE = "conversation_updated"

        /** The host registry's push; see SubagentRegistryPublisher on the host. */
        private const val SUBAGENTS_UPDATED_TYPE = "subagents_updated"

        private val HOST_PUSH_TYPES = setOf(
            AGENT_UPDATED_TYPE, CONVERSATION_UPDATED_TYPE, SUBAGENTS_UPDATED_TYPE,
            // letta-mobile-bzvro.40: the host's parked-approval snapshots.
            com.letta.mobile.data.runtime.ApprovalRelay.FRAME_TYPE,
            // letta-mobile-a4tm7: another client changed a conversation's permission mode.
            com.letta.mobile.data.runtime.PermissionModeRpc.FRAME_TYPE,
        )

        /** Longer than the engine's settle window (1.5 s), so a healthy engine always wins. */
        internal const val OBSERVER_TERMINAL_GRACE_MS = 3_000L
        private val DEVICE_PUSH_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }


        private fun JsonObject.string(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull

        private fun observerTurnCommand(agentId: String, conversationId: String): TurnCommand =
            TurnCommand(
                backendId = BackendId("iroh-app-server"),
                runtimeId = RuntimeId("iroh-observer"),
                agentId = AgentId(agentId),
                conversationId = ConversationId(conversationId),
                input = TurnInput.UserMessage(
                    localMessageId = "iroh-observer-$conversationId",
                    text = "",
                ),
            )
    }
}

internal data class ObserverStartRequest(val handle: IrohConnectionHandle, val generation: Long)
internal data class ObserverStopRequest(val reason: String)
internal data class ViewedConversationRequest(val method: String, val path: String)
internal data class ObserverResubscribeRequest(val generation: Long)
internal data class ObserverFrameRequest(val received: AppServerReceivedFrame, val expectedGeneration: Long? = null)
internal data class SubagentRpcScope(val conversationId: String, val agentId: String?)

private data class ObserverProjectionScope(
    val agentId: String,
    val conversationId: String,
    val localTurn: IrohActiveTurn?,
)

private data class ObservedTurn(
    val agentId: String,
    val conversationId: String,
    val turnId: String?,
    val runId: String?,
    val roundContinues: Boolean,
)

private class ObservedTurnTracker {
    private val lock = Any()
    private val activeByConversation = HashMap<String, ObservedTurn>()
    private val completedTurnIds = LinkedHashSet<String>()
    private val completedRunIds = LinkedHashSet<String>()
    private val completedWithoutTurnConversations = HashSet<String>()

    fun active(conversation: String): ObservedTurn? = synchronized(lock) {
        activeByConversation[conversation]
    }

    fun clearConversation(conversation: String) = synchronized(lock) {
        activeByConversation.remove(conversation)
        completedWithoutTurnConversations.remove(conversation)
    }

    fun reset() = synchronized(lock) {
        activeByConversation.clear()
        completedTurnIds.clear()
        completedRunIds.clear()
        completedWithoutTurnConversations.clear()
    }

    fun isCompleted(turn: String?, run: String?): Boolean = synchronized(lock) {
        isCompletedLocked(turn, run)
    }

    fun consumeCompletedTurnFinished(conversation: String, turn: String?, run: String?): Boolean =
        synchronized(lock) {
            val closedWithoutTurn = completedWithoutTurnConversations.remove(conversation)
            val alreadyDone = closedWithoutTurn ||
                (turn != null && turn in completedTurnIds) ||
                (activeByConversation[conversation] == null && run != null && run in completedRunIds)
            if (alreadyDone) {
                activeByConversation.remove(conversation)
                rememberBounded(completedTurnIds, turn)
                rememberBounded(completedRunIds, run)
            }
            alreadyDone
        }

    fun markCompleted(conversation: String, turn: String?, run: String?) = synchronized(lock) {
        activeByConversation.remove(conversation)
        rememberBounded(completedTurnIds, turn)
        rememberBounded(completedRunIds, run)
        if (turn == null) {
            completedWithoutTurnConversations.add(conversation)
        } else {
            completedWithoutTurnConversations.remove(conversation)
        }
    }

    fun recordDelta(
        scope: ObserverProjectionScope,
        delta: JsonObject,
        wireTurn: String?,
        wireRun: String?,
    ) = synchronized(lock) {
        val messageType = delta["message_type"]?.jsonPrimitive?.contentOrNull
        val ignore = isCompletedLocked(wireTurn, wireRun) ||
            messageType == null || messageType == "ping" || messageType == "heartbeat"
        if (!ignore) {
            if (wireTurn != null && wireRun != null) completedRunIds.remove(wireRun)
            completedWithoutTurnConversations.remove(scope.conversationId)
            val previous = activeByConversation[scope.conversationId]
            val sameTurn = previous != null &&
                (wireTurn == null || previous.turnId == null || previous.turnId == wireTurn)
            val nextContinues = when (messageType) {
                "stop_reason" -> isContinuingStopReason(delta)
                "approval_request_message" -> true
                "usage_statistics" -> previous?.roundContinues ?: false
                else -> false
            }
            activeByConversation[scope.conversationId] = ObservedTurn(
                agentId = scope.agentId,
                conversationId = scope.conversationId,
                turnId = wireTurn ?: previous?.takeIf { sameTurn }?.turnId,
                runId = wireRun ?: previous?.takeIf { sameTurn }?.runId,
                roundContinues = nextContinues,
            )
        }
    }

    fun completeOnIdleLoop(
        conversation: String,
        frame: AppServerInboundFrame.UpdateLoopStatus,
    ): ServerFrame.TurnDone? = synchronized(lock) {
        val status = frame.loopStatus
        val current = activeByConversation[conversation]
        val canComplete = current != null &&
            !current.roundContinues &&
            status.status == TurnBoundaryGate.LOOP_WAITING_ON_INPUT &&
            status.activeRunIds.isEmpty()
        if (!canComplete || current == null) return@synchronized null
        markCompleted(conversation, current.turnId, current.runId)
        RuntimeEventServerFrameMapper.map(
            payload = RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed),
            context = RuntimeEventServerFrameMapper.Context(
                agentId = current.agentId,
                conversationId = current.conversationId,
                turnId = current.turnId,
                runId = current.runId,
            ),
        ).filterIsInstance<ServerFrame.TurnDone>().firstOrNull()
    }

    private fun isCompletedLocked(turn: String?, run: String?): Boolean = when {
        turn != null -> turn in completedTurnIds
        run != null -> run in completedRunIds
        else -> false
    }

    private fun isContinuingStopReason(delta: JsonObject): Boolean {
        val reason = delta["stop_reason"]?.jsonPrimitive?.contentOrNull
            ?: delta["reason"]?.jsonPrimitive?.contentOrNull
        val boundary = AppServerStopReason.boundaryOf(reason)
        return boundary == AppServerTurnBoundary.AwaitingApproval ||
            boundary == AppServerTurnBoundary.Continuing
    }

    private fun rememberBounded(target: LinkedHashSet<String>, value: String?) {
        val key = value?.takeIf { it.isNotBlank() } ?: return
        target.remove(key)
        target.add(key)
        while (target.size > MAX_COMPLETED_OBSERVER_TURNS) {
            target.remove(target.first())
        }
    }

    companion object {
        private const val MAX_COMPLETED_OBSERVER_TURNS = 32
    }
}
