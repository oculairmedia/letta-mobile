package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.subagent.ParentContext
import com.letta.mobile.data.repository.subagent.SubagentCorrelator
import com.letta.mobile.data.runtime.AppServerRuntimeEventMapper
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeId
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

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
) {
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

    @Volatile
    private var lastEmittedSubagentRevision: Long = 0L

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

    suspend fun ingestObserverFrame(request: ObserverFrameRequest) {
        if (request.expectedGeneration != null && connectionGeneration() != request.expectedGeneration) return
        val received = request.received
        val streamDelta = received.frame as? AppServerInboundFrame.StreamDelta ?: return
        val scope = observerScope(streamDelta)
        recordFrameOwnership(scope.conversationId, scope.localTurn)
        if (scope.localTurn != null) {
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
        correlateAgentFrame(streamDelta).forEach { emitBoth(it) }
        projectPassiveObserverDelta(scope, received)
    }

    /**
     * letta-mobile-p0gc (causal slice C): the ONLY thing the observer does with
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

    private fun observerScope(streamDelta: AppServerInboundFrame.StreamDelta): ObserverProjectionScope =
        ObserverProjectionScope(
            agentId = streamDelta.runtime.agentId,
            conversationId = streamDelta.runtime.conversationId,
            localTurn = turnRegistry.getActiveTurn(IrohConversationId(streamDelta.runtime.conversationId)),
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

    private suspend fun projectPassiveObserverDelta(scope: ObserverProjectionScope, received: AppServerReceivedFrame) {
        observerMapper.map(observerTurnCommand(scope.agentId, scope.conversationId), received).forEach { draft ->
            RuntimeEventServerFrameMapper.map(
                payload = draft.payload,
                context = RuntimeEventServerFrameMapper.Context(
                    agentId = draft.agentId?.value ?: scope.agentId,
                    conversationId = draft.conversationId?.value ?: scope.conversationId,
                    turnId = "iroh-observer-turn-${scope.conversationId}",
                    runId = draft.runId?.value ?: "iroh-observer-run-${scope.conversationId}",
                ),
            ).forEach { emitBoth(it) }
        }
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
        val terminal = projectedFrames.firstOrNull { it is ServerFrame.TurnDone }
        if (terminal is ServerFrame.TurnDone) {
            val publication = IrohTerminalPublication(
                turn = localTurn,
                status = IrohTerminalStatus(terminal.status),
                source = IrohTerminalSource.Observer,
            )
            if (turnRegistry.claimTerminal(publication)) {
                withContext(NonCancellable) {
                    emitBoth(terminal)
                    turnRegistry.retireClaimed(publication)
                }
            }
        }
    }

    private fun correlateAgentFrame(
        streamDelta: AppServerInboundFrame.StreamDelta,
    ): List<ServerFrame> = runCatching {
        val delta = streamDelta.delta as? JsonObject ?: return@runCatching emptyList()
        val messageType = delta.string("message_type") ?: return@runCatching emptyList()
        val toolCall = delta["tool_call"]?.jsonObject
        val parent = ParentContext(
            agentId = streamDelta.runtime.agentId,
            conversationId = streamDelta.runtime.conversationId,
            runId = delta.string("run_id"),
        )
        when (messageType) {
            "tool_call_message", "approval_request_message" -> processAgentDispatch(delta, toolCall, parent)
            "tool_return_message" -> processAgentReturn(delta, toolCall, parent)
            else -> emptyList()
        }
    }.getOrElse { emptyList() }

    private fun processAgentDispatch(
        delta: JsonObject,
        toolCall: JsonObject?,
        parent: ParentContext,
    ): List<ServerFrame> {
        val name = toolCall?.string("name") ?: return emptyList()
        if (name != "Agent") return emptyList()
        val toolCallId = toolCall.string("tool_call_id") ?: delta.string("tool_call_id") ?: return emptyList()
        val arguments = toolCall["arguments"]?.toString() ?: delta["arguments"]?.toString()
        subagentCorrelator.onAgentDispatch(toolCallId, arguments, parent)
        return buildSubagentsUpdatedIfChanged(toolCallId, SUBAGENT_REASON_STARTED)
    }

    private fun processAgentReturn(
        delta: JsonObject,
        toolCall: JsonObject?,
        parent: ParentContext,
    ): List<ServerFrame> {
        val toolCallId = toolCall?.string("tool_call_id") ?: delta.string("tool_call_id") ?: return emptyList()
        subagentCorrelator.onAgentReturn(toolCallId, parent)
        return buildSubagentsUpdatedIfChanged(toolCallId, SUBAGENT_REASON_COMPLETED)
    }

    private fun buildSubagentsUpdatedIfChanged(
        changedToolCallId: String,
        reason: String,
    ): List<ServerFrame> {
        val revision = subagentCorrelator.revision
        if (revision == lastEmittedSubagentRevision) return emptyList()
        lastEmittedSubagentRevision = revision
        val snapshot = subagentCorrelator.snapshot()
        val changed = snapshot.firstOrNull { it.toolCallId == changedToolCallId }
        val nowIso = nowIso()
        return listOf(
            ServerFrame.SubagentsUpdated(
                id = frameId("subagents_updated"),
                ts = nowIso,
                reason = reason,
                subagent = changed,
                subagentsActive = snapshot,
                at = nowIso,
            ),
        )
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
        engineOwnedSkipTelemetry.endTurn()
        lastEmittedSubagentRevision = 0L
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

    private data class ObserverProjectionScope(
        val agentId: String,
        val conversationId: String,
        val localTurn: IrohActiveTurn?,
    )

    companion object {
        internal const val SUBAGENT_REASON_STARTED = "started"
        internal const val SUBAGENT_REASON_COMPLETED = "completed"

        private fun frameId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
        private fun nowIso(): String = Instant.now().toString()

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
