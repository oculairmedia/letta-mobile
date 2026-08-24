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

    fun start(handle: IrohConnectionHandle, generation: Long) {
        val streamFrames = handle.effectiveObserverStreamFrames
        if (streamFrames == null) {
            stop("no_observer_stream")
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
                    ingestObserverFrame(received, generation)
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

    fun stop(reason: String) {
        val job = observerJob ?: return
        observerJob = null
        observerGeneration.incrementAndGet()
        job.cancel()
        Telemetry.event("IrohObserver", "ingest.stop", "reason" to reason)
    }

    fun recordViewedConversationFrom(method: String, path: String) {
        if (method != "message.list") return
        val conversationId = conversationIdFromMessageListPath(path) ?: return
        viewedConversationId = conversationId
        viewedMessageListPath = path
    }

    fun reSubscribeViewedConversation(generation: Long) {
        val path = viewedMessageListPath ?: return
        val conversationId = viewedConversationId
        resubscribeJob?.cancel()
        resubscribeJob = scope.launch {
            if (connectionGeneration() != generation) return@launch
            Telemetry.event(
                "IrohObserver", "resubscribe.begin",
                "conversationId" to (conversationId ?: ""),
                "generation" to generation.toString(),
            )
            runCatching {
                if (connectionGeneration() != generation) return@launch
                adminRpc("message.list", path, null)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Telemetry.event(
                    "IrohObserver", "resubscribe.failed",
                    "conversationId" to (conversationId ?: ""),
                    "error" to (error.message ?: error.toString()),
                    "class" to error::class.simpleName,
                )
            }
        }
    }

    suspend fun ingestObserverFrame(received: AppServerReceivedFrame, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && connectionGeneration() != expectedGeneration) return

        val streamDelta = received.frame as? AppServerInboundFrame.StreamDelta ?: return
        val conversationId = streamDelta.runtime.conversationId
        val agentId = streamDelta.runtime.agentId

        val localTurn = turnRegistry.getActiveTurn(conversationId)
        recordFrameOwnership(conversationId, localTurn)
        if (localTurn != null) {
            Telemetry.event(
                "IrohObserver", "ingest.skip_engine_owned",
                "conversationId" to conversationId,
                "turnId" to localTurn.turnId,
            )
            projectEngineOwnedObserverDelta(
                scope = ObserverProjectionScope(
                    agentId = agentId,
                    conversationId = conversationId,
                    localTurn = localTurn,
                ),
                received = received,
            )
            return
        }

        val deltaObj = streamDelta.delta as? JsonObject
        val frameRunId = deltaObj?.string("run_id") ?: deltaObj?.string("runId")
        if (frameRunId != null && turnRegistry.isRetiredRun(frameRunId)) {
            Telemetry.event(
                "IrohObserver", "ingest.skip_already_retired",
                "conversationId" to conversationId,
                "runId" to frameRunId,
            )
            return
        }

        correlateAgentFrame(streamDelta).forEach { emitBoth(it) }

        val command = observerTurnCommand(agentId, conversationId)
        observerMapper.map(command, received).forEach { draft ->
            val frames = RuntimeEventServerFrameMapper.map(
                payload = draft.payload,
                context = RuntimeEventServerFrameMapper.Context(
                    agentId = draft.agentId?.value ?: agentId,
                    conversationId = draft.conversationId?.value ?: conversationId,
                    turnId = "iroh-observer-turn-$conversationId",
                    runId = draft.runId?.value ?: "iroh-observer-run-$conversationId",
                ),
            )
            frames.forEach { emitBoth(it) }
        }
    }

    private suspend fun projectEngineOwnedObserverDelta(
        scope: ObserverProjectionScope,
        received: AppServerReceivedFrame,
    ) {
        val command = observerTurnCommand(scope.agentId, scope.conversationId)
        val projectedFrames = observerMapper.map(command, received).flatMap { draft ->
            RuntimeEventServerFrameMapper.map(
                payload = draft.payload,
                context = RuntimeEventServerFrameMapper.Context(
                    agentId = draft.agentId?.value ?: scope.agentId,
                    conversationId = draft.conversationId?.value ?: scope.conversationId,
                    turnId = scope.localTurn.turnId,
                    runId = draft.runId?.value ?: scope.localTurn.runId,
                ),
            )
        }
        val terminal = projectedFrames.firstOrNull { it is ServerFrame.TurnDone }
        if (terminal is ServerFrame.TurnDone) {
            if (turnRegistry.publishTerminal(scope.localTurn, terminal.status, source = "observer")) {
                emitBoth(terminal)
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
        val agentId = turnRegistry.getActiveTurn(conversationId)?.agentId
        return SubagentRpcScope(conversationId, agentId)
    }

    fun reset() {
        resubscribeJob?.cancel()
        resubscribeJob = null
        subagentCorrelator.reset()
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

    private data class ObserverProjectionScope(
        val agentId: String,
        val conversationId: String,
        val localTurn: IrohActiveTurn,
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

internal data class SubagentRpcScope(val conversationId: String, val agentId: String?)
