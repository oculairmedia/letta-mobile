package com.letta.mobile.data.session

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.FrameCollectorOverflowAwareChannelTransport
import com.letta.mobile.data.transport.api.FrameCollectorOverflowIncident
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.RedialAwareChannelTransport
import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray

internal fun defaultSessionScopedChannelTransportScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedChannelTransport internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
    private val overflowReconciler: FrameCollectorOverflowReconciler,
) : IChannelTransport, RedialAwareChannelTransport, FrameCollectorOverflowRecoveryMonitor {
    @Inject
    constructor(
        sessionManager: SessionManager,
        overflowReconciler: TimelineFrameCollectorOverflowReconciler,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedChannelTransportScope(),
        overflowReconciler = overflowReconciler,
    )

    private val _state = MutableStateFlow(sessionManager.current.channelTransport.state.value)
    override val state: StateFlow<ChannelTransportState> = _state

    private val seenIncidents = mutableSetOf<Pair<Long, Long>>()
    private val seenIncidentsMutex = Mutex()
    private val conversationLocks = mutableMapOf<Pair<Long, String>, Mutex>()
    private val conversationLocksMutex = Mutex()
    private val _recoveryEvents = MutableSharedFlow<FrameCollectorOverflowRecoveryEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val recoveryEvents: SharedFlow<FrameCollectorOverflowRecoveryEvent> = _recoveryEvents.asSharedFlow()

    override val events: SharedFlow<ServerFrame> = sessionManager.currentGraph
        .flatMapLatest { graph -> retryProjection(graph, EVENTS_SUBSCRIPTION) { graph.channelTransport.events } }
        .shareIn(proxyScope, SharingStarted.Eagerly, replay = 0)

    override val frameEvents: SharedFlow<TransportFrameEvent> = sessionManager.currentGraph
        .flatMapLatest { graph -> retryProjection(graph, FRAME_EVENTS_SUBSCRIPTION) { graph.channelTransport.frameEvents } }
        .shareIn(proxyScope, SharingStarted.Eagerly, replay = 0)

    override val redialWhileTurnActive: SharedFlow<RedialWhileTurnActive> = sessionManager.currentGraph
        .flatMapLatest { (it.channelTransport as? RedialAwareChannelTransport)?.redialWhileTurnActive ?: emptyFlow() }
        .shareIn(proxyScope, SharingStarted.Eagerly, replay = 0)

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.channelTransport.state }
            .onEach { _state.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .onEach { graph -> clearRecoveryStateExcept(graph.id) }
            .flatMapLatest { graph ->
                val overflows = (graph.channelTransport as? FrameCollectorOverflowAwareChannelTransport)
                    ?.collectorOverflows ?: emptyFlow()
                overflows.onEach { incident ->
                    proxyScope.launch { recoverOverflow(graph, incident) }
                }
            }
            .launchIn(proxyScope)
    }


    private fun <T> retryProjection(
        graph: SessionRepositoryGraph,
        subscriptionIdentity: String,
        source: () -> SharedFlow<T>,
    ) = flow {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val overflowAware = graph.channelTransport as? FrameCollectorOverflowAwareChannelTransport
            val generationAtAttach = overflowAware?.frameCollectorConnectionGeneration
            try {
                source().collect { emit(it) }
            } catch (cancelled: CancellationException) {
                if (!kotlin.coroutines.coroutineContext.isActive || sessionManager.currentGraph.value.id != graph.id) {
                    throw cancelled
                }
                val detachedByOverflow = generationAtAttach != null && overflowAware
                    .isFrameCollectorOverflowCancellation(subscriptionIdentity, generationAtAttach, cancelled)
                if (!detachedByOverflow) throw cancelled
                // Reattach before reconciliation. The recovery job yields once
                // before fetching canonical state, so frames arriving after the
                // replacement subscription starts are either streamed or folded
                // by the reconcile, never stranded in a detach/replay-zero gap.
                continue
            }
        }
    }

    private suspend fun recoverOverflow(graph: SessionRepositoryGraph, incident: FrameCollectorOverflowIncident) {
        val graphId = graph.id
        val overflowAware = graph.channelTransport as? FrameCollectorOverflowAwareChannelTransport ?: return
        if (sessionManager.currentGraph.value.id != graphId) return
        if (incident.connectionGeneration != overflowAware.frameCollectorConnectionGeneration) {
            publishRecovery(incident, graphId, FrameCollectorOverflowRecoveryOutcome.InvalidIncident("stale_connection_generation"))
            return
        }
        val key = graphId to incident.subscriptionId
        if (!seenIncidentsMutex.withLock { seenIncidents.add(key) }) return
        publishRecovery(incident, graphId, FrameCollectorOverflowRecoveryOutcome.Started)
        if (incident.conversationId.isBlank()) {
            publishRecovery(incident, graphId, FrameCollectorOverflowRecoveryOutcome.InvalidIncident("blank_conversation_id"))
            return
        }
        val lock = conversationLocksMutex.withLock {
            conversationLocks.getOrPut(graphId to incident.conversationId) { Mutex() }
        }
        lock.withLock {
            if (sessionManager.currentGraph.value.id != graphId ||
                incident.connectionGeneration != overflowAware.frameCollectorConnectionGeneration
            ) {
                publishRecovery(incident, graphId, FrameCollectorOverflowRecoveryOutcome.Superseded)
                return@withLock
            }
            val outcome = try {
                kotlinx.coroutines.yield()
                reconcileWithRetry(incident)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                FrameCollectorOverflowRecoveryOutcome.Failed(failure)
            }
            if (sessionManager.currentGraph.value.id != graphId ||
                incident.connectionGeneration != overflowAware.frameCollectorConnectionGeneration
            ) {
                publishRecovery(incident, graphId, FrameCollectorOverflowRecoveryOutcome.Superseded)
            } else {
                publishRecovery(incident, graphId, outcome)
            }
        }
    }

    private suspend fun reconcileWithRetry(
        incident: FrameCollectorOverflowIncident,
    ): FrameCollectorOverflowRecoveryOutcome {
        var last: com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome? = null
        repeat(MAX_RECONCILE_ATTEMPTS) { attempt ->
            val result = overflowReconciler.reconcile(
                incident.conversationId,
                incident.connectionGeneration,
            )
            last = result
            when (result) {
                is com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome.Applied ->
                    return FrameCollectorOverflowRecoveryOutcome.Reconciled(result.appended)
                is com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome.Failed,
                is com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome.Skipped,
                -> if (attempt + 1 < MAX_RECONCILE_ATTEMPTS) delay(RECONCILE_RETRY_DELAY_MS)
            }
        }
        return FrameCollectorOverflowRecoveryOutcome.NotApplied(checkNotNull(last))
    }

    private fun publishRecovery(
        incident: FrameCollectorOverflowIncident,
        graphId: Long,
        outcome: FrameCollectorOverflowRecoveryOutcome,
    ) {
        _recoveryEvents.tryEmit(incident.toRecoveryEvent(graphId, outcome))
    }

    private suspend fun clearRecoveryStateExcept(graphId: Long?) {
        seenIncidentsMutex.withLock { seenIncidents.removeAll { it.first != graphId } }
        conversationLocksMutex.withLock { conversationLocks.keys.removeAll { it.first != graphId } }
    }

    private val current: IChannelTransport
        get() = sessionManager.current.channelTransport

    // dir4k (z5vfy PR-2): authoritative active-turn ownership must pass THROUGH
    // the session-scoped wrapper to the live transport. Without this override the
    // wrapper inherited IChannelTransport's default (false), swallowing the real
    // Iroh/WS ownership signal — so ChatSendCoordinator's stale-presence self-heal
    // (`if (!hasActiveChatTurn && streaming)`) fired on EVERY send regardless of a
    // genuinely active turn (the "legacy WS misclassification"). Delegate it like
    // every other IChannelTransport member.
    //
    // letta-mobile-or40x: the signal is now KEYED BY conversationId. Both forms
    // must be delegated — inheriting either default (false) re-opens exactly the
    // dir4k regression above, this time per conversation.
    override fun hasActiveChatTurn(conversationId: String): Boolean =
        current.hasActiveChatTurn(conversationId)

    override val hasAnyActiveChatTurn: Boolean
        get() = current.hasAnyActiveChatTurn

    override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) =
        sessionManager.withCurrentSession { it.channelTransport.connect(baseShimUrl, token, deviceId, clientVersion) }

    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        contentParts: JsonArray?,
        startNewConversation: Boolean,
    ): Boolean = current.send(agentId, conversationId, text, otid, contentParts, startNewConversation)

    override fun cancel(conversationId: String): Boolean = current.cancel(conversationId)
    override fun bye(): Boolean = current.bye()
    override fun subscribe(runId: String, cursor: Long): Boolean = current.subscribe(runId, cursor)
    override suspend fun adminRpc(method: String, path: String, body: String?): com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse =
        sessionManager.withCurrentSession { it.channelTransport.adminRpc(method, path, body) }
    override suspend fun disconnect() = sessionManager.withCurrentSession { it.channelTransport.disconnect() }
    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = current.sendA2uiAction(action)

    override suspend fun sendCronList(
        agentId: String?,
        conversationId: String?,
        timeoutMs: Long,
    ): ServerFrame.CronListResponse = sessionManager.withCurrentSession { it.channelTransport.sendCronList(agentId, conversationId, timeoutMs) }

    override suspend fun sendCronAdd(
        agentId: String,
        name: String,
        description: String,
        prompt: String,
        recurring: Boolean,
        cron: String?,
        every: String?,
        at: String?,
        timezone: String?,
        conversationId: String?,
        timeoutMs: Long,
    ): ServerFrame.CronAddResponse = sessionManager.withCurrentSession { it.channelTransport.sendCronAdd(
        agentId, name, description, prompt, recurring, cron, every, at, timezone, conversationId, timeoutMs,
    ) }

    override suspend fun sendCronGet(taskId: String, timeoutMs: Long): ServerFrame.CronGetResponse =
        sessionManager.withCurrentSession { it.channelTransport.sendCronGet(taskId, timeoutMs) }

    override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse =
        sessionManager.withCurrentSession { it.channelTransport.sendCronDelete(taskId, timeoutMs) }

    override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long): ServerFrame.CronDeleteAllResponse =
        sessionManager.withCurrentSession { it.channelTransport.sendCronDeleteAll(agentId, timeoutMs) }

    override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long): ServerFrame.SubagentListResponse =
        sessionManager.withCurrentSession { it.channelTransport.sendSubagentList(all, timeoutMs) }

    override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long): ServerFrame.SubagentTodosResponse =
        sessionManager.withCurrentSession { it.channelTransport.sendSubagentTodos(toolCallId, timeoutMs) }

    fun close() {
        proxyScope.cancel()
    }

    private companion object {
        const val MAX_RECONCILE_ATTEMPTS = 2
        const val RECONCILE_RETRY_DELAY_MS = 50L
        const val EVENTS_SUBSCRIPTION = "events"
        const val FRAME_EVENTS_SUBSCRIPTION = "frameEvents"
    }
}
