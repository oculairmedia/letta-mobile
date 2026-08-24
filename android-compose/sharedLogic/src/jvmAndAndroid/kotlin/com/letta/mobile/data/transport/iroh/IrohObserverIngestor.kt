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
    private val dependencies: Dependencies,
) {
    internal data class Dependencies(
        val scope: CoroutineScope,
        val turnRegistry: IrohTurnRegistry,
        val connectionGeneration: () -> Long,
        val emit: suspend (ServerFrame) -> Unit,
        val adminRpc: suspend (AdminRpcRequest) -> AppServerInboundFrame.AdminRpcResponse,
        val recordFrameOwnership: (FrameObservation) -> Unit,
        val observerMapper: AppServerRuntimeEventMapper = AppServerRuntimeEventMapper(),
        val subagentCorrelator: SubagentCorrelator = SubagentCorrelator(),
    )

    private val scope get() = dependencies.scope
    private val turnRegistry get() = dependencies.turnRegistry
    private val connectionGeneration get() = dependencies.connectionGeneration
    private val emitBoth get() = dependencies.emit
    private val adminRpc get() = dependencies.adminRpc
    private val recordFrameOwnership get() = dependencies.recordFrameOwnership
    private val observerMapper get() = dependencies.observerMapper
    internal val subagentCorrelator get() = dependencies.subagentCorrelator
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

    fun start(connection: ObserverConnection) {
        val handle = connection.handle
        val generation = connection.generation
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
                    ingestObserverFrame(ObserverFrameReceipt(received, generation))
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

    fun observeAdminRequest(request: AdminRpcRequest) {
        if (request.method != "message.list") return
        val conversationId = conversationIdFromMessageListPath(request.path) ?: return
        viewedConversationId = conversationId
        viewedMessageListPath = request.path
    }

    fun reSubscribeViewedConversation(generation: Long) {
        val subscription = viewedMessageListPath?.let { ViewedConversation(it, viewedConversationId, generation) } ?: return
        resubscribeJob?.cancel()
        resubscribeJob = scope.launch { resubscribe(subscription) }
    }

    private suspend fun resubscribe(subscription: ViewedConversation) {
        if (connectionGeneration() != subscription.generation) return
        Telemetry.event(
            "IrohObserver", "resubscribe.begin",
            "conversationId" to subscription.conversationId.orEmpty(),
            "generation" to subscription.generation.toString(),
        )
        runCatching { adminRpc(subscription.request) }
            .onFailure { reportResubscribeFailure(it, subscription) }
    }

    private fun reportResubscribeFailure(error: Throwable, view: ViewedConversation) {
        if (error is CancellationException) throw error
        Telemetry.event(
            "IrohObserver", "resubscribe.failed",
            "conversationId" to view.conversationId.orEmpty(),
            "error" to (error.message ?: error.toString()),
            "class" to error::class.simpleName,
        )
    }

    suspend fun ingestObserverFrame(receipt: ObserverFrameReceipt) {
        if (receipt.expectedGeneration != null && connectionGeneration() != receipt.expectedGeneration) return
        val streamDelta = receipt.received.frame as? AppServerInboundFrame.StreamDelta ?: return
        ingestStreamDelta(ObserverFrameContext(receipt.received, streamDelta))
    }

    private suspend fun ingestStreamDelta(context: ObserverFrameContext) {
        val conversation = IrohConversationKey(context.conversationId)
        val localTurn = turnRegistry.getActiveTurn(conversation)
        recordFrameOwnership(FrameObservation(context.conversationId, localTurn))
        if (localTurn == null) {
            ingestPassiveObserverDelta(context)
        } else {
            ingestEngineOwnedObserverDelta(context, localTurn)
        }
    }

    private suspend fun ingestEngineOwnedObserverDelta(context: ObserverFrameContext, localTurn: IrohActiveTurn) {
        Telemetry.event(
            "IrohObserver", "ingest.skip_engine_owned",
            "conversationId" to context.conversationId,
            "turnId" to localTurn.turnId,
        )
        projectEngineOwnedObserverDelta(
            ObserverProjectionScope(context.agentId, context.conversationId, localTurn),
            context.received,
        )
    }

    private suspend fun ingestPassiveObserverDelta(context: ObserverFrameContext) {
        if (skipRetiredRun(context)) return
        correlateAgentFrame(context.streamDelta).forEach { emitBoth(it) }
        projectPassiveObserverDelta(context)
    }

    private fun skipRetiredRun(context: ObserverFrameContext): Boolean {
        val delta = context.streamDelta.delta as? JsonObject ?: return false
        val runId = delta.string("run_id") ?: delta.string("runId") ?: return false
        if (!turnRegistry.isRetiredRun(IrohRunKey(runId))) return false
        Telemetry.event(
            "IrohObserver", "ingest.skip_already_retired",
            "conversationId" to context.conversationId,
            "runId" to runId,
        )
        return true
    }

    private suspend fun projectPassiveObserverDelta(context: ObserverFrameContext) {
        val command = observerTurnCommand(context.agentId, context.conversationId)
        observerMapper.map(command, context.received)
            .flatMap { draft -> RuntimeEventServerFrameMapper.map(draft.payload, passiveContext(draft, context)) }
            .forEach { emitBoth(it) }
    }

    private fun passiveContext(draft: RuntimeEventDraft, context: ObserverFrameContext): RuntimeEventServerFrameMapper.Context =
        RuntimeEventServerFrameMapper.Context(
            agentId = draft.agentId?.value ?: context.agentId,
            conversationId = draft.conversationId?.value ?: context.conversationId,
            turnId = "iroh-observer-turn-${context.conversationId}",
            runId = draft.runId?.value ?: "iroh-observer-run-${context.conversationId}",
        )

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
            if (turnRegistry.publishTerminal(IrohTerminalPublication(scope.localTurn, terminal.status, "observer"))) {
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
        val agentId = turnRegistry.getActiveTurn(IrohConversationKey(conversationId))?.agentId
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

    internal data class ObserverConnection(val handle: IrohConnectionHandle, val generation: Long)
    internal data class ObserverFrameReceipt(
        val received: AppServerReceivedFrame,
        val expectedGeneration: Long? = null,
    )

    private data class ViewedConversation(
        val path: String,
        val conversationId: String?,
        val generation: Long,
    ) {
        val request: AdminRpcRequest get() = AdminRpcRequest("message.list", path, null)
    }

    internal data class FrameObservation(
        val conversationId: String,
        val localTurn: IrohActiveTurn?,
    )

    private data class ObserverFrameContext(
        val received: AppServerReceivedFrame,
        val streamDelta: AppServerInboundFrame.StreamDelta,
    ) {
        val agentId: String get() = streamDelta.runtime.agentId
        val conversationId: String get() = streamDelta.runtime.conversationId
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
