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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
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
    private val seenIncidentOrder = ArrayDeque<Pair<Long, Long>>()
    private val seenIncidentsMutex = Mutex()
    private val conversationLocks = mutableMapOf<Pair<Long, String>, ConversationRecoveryLock>()
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
        while (true) {
            collectProjectionOnce(graph, subscriptionIdentity, source) { emit(it) }
        }
    }

    private suspend fun <T> collectProjectionOnce(
        graph: SessionRepositoryGraph,
        subscriptionIdentity: String,
        source: () -> SharedFlow<T>,
        emit: suspend (T) -> Unit,
    ) {
        val overflowAware = graph.channelTransport as? FrameCollectorOverflowAwareChannelTransport
        try {
            source().collect { emit(it) }
        } catch (cancelled: CancellationException) {
            kotlin.coroutines.coroutineContext.ensureActive()
            if (!isCurrentOverflowDetach(graph.id, overflowAware, subscriptionIdentity, cancelled)) {
                throw cancelled
            }
            // Reattach before reconciliation. The recovery job yields once before
            // fetching canonical state, so no frame is stranded in a replay-zero gap.
        }
    }

    // Match the transport-owned cancellation itself rather than a generation
    // sampled before collection. A redial may advance the generation while the
    // subscription is registering, but the typed detach still requires reattachment.
    private fun isCurrentOverflowDetach(
        graphId: Long,
        overflowAware: FrameCollectorOverflowAwareChannelTransport?,
        subscriptionIdentity: String,
        cancellation: CancellationException,
    ): Boolean = sessionManager.currentGraph.value.id == graphId &&
        overflowAware?.isFrameCollectorOverflowCancellation(subscriptionIdentity, cancellation) == true

    private suspend fun recoverOverflow(graph: SessionRepositoryGraph, incident: FrameCollectorOverflowIncident) {
        val graphId = graph.id
        val overflowAware = graph.channelTransport as? FrameCollectorOverflowAwareChannelTransport ?: return
        if (!validateIncident(graphId, overflowAware, incident)) return

        publishRecovery(incident, graphId, attempt = 0, FrameCollectorOverflowRecoveryOutcome.Started)
        if (incident.conversationId.isBlank()) {
            publishRecovery(
                incident,
                graphId,
                attempt = 0,
                FrameCollectorOverflowRecoveryOutcome.InvalidIncident("blank_conversation_id"),
            )
            return
        }

        val key = graphId to incident.conversationId
        val recoveryLock = acquireConversationLock(key)
        try {
            recoveryLock.mutex.withLock {
                recoverUnderLock(graphId, overflowAware, incident)
            }
        } finally {
            releaseConversationLock(key, recoveryLock)
        }
    }

    private suspend fun validateIncident(
        graphId: Long,
        overflowAware: FrameCollectorOverflowAwareChannelTransport,
        incident: FrameCollectorOverflowIncident,
    ): Boolean {
        if (sessionManager.currentGraph.value.id != graphId) return false
        if (!rememberIncident(graphId to incident.subscriptionId)) return false
        if (incident.connectionGeneration != overflowAware.frameCollectorConnectionGeneration) {
            publishRecovery(
                incident,
                graphId,
                attempt = 0,
                FrameCollectorOverflowRecoveryOutcome.InvalidIncident("stale_connection_generation"),
            )
            return false
        }
        return true
    }

    private suspend fun recoverUnderLock(
        graphId: Long,
        overflowAware: FrameCollectorOverflowAwareChannelTransport,
        incident: FrameCollectorOverflowIncident,
    ) {
        if (!isCurrentRecovery(graphId, overflowAware, incident)) {
            publishRecovery(incident, graphId, attempt = 0, FrameCollectorOverflowRecoveryOutcome.Superseded)
            return
        }
        kotlinx.coroutines.yield()
        val completed = reconcileWithRetry(incident)
        val terminal = if (isCurrentRecovery(graphId, overflowAware, incident)) {
            completed.outcome
        } else {
            FrameCollectorOverflowRecoveryOutcome.Superseded
        }
        publishRecovery(incident, graphId, completed.attempt, terminal)
    }

    private fun isCurrentRecovery(
        graphId: Long,
        overflowAware: FrameCollectorOverflowAwareChannelTransport,
        incident: FrameCollectorOverflowIncident,
    ): Boolean = sessionManager.currentGraph.value.id == graphId &&
        incident.connectionGeneration == overflowAware.frameCollectorConnectionGeneration

    private suspend fun reconcileWithRetry(
        incident: FrameCollectorOverflowIncident,
    ): CompletedReconciliation {
        var last: com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome? = null
        repeat(MAX_RECONCILE_ATTEMPTS) { index ->
            val attempt = index + 1
            val result = try {
                overflowReconciler.reconcile(incident.conversationId, incident.connectionGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return CompletedReconciliation(attempt, FrameCollectorOverflowRecoveryOutcome.Failed)
            }
            last = result
            if (result is com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome.Applied) {
                return CompletedReconciliation(
                    attempt,
                    FrameCollectorOverflowRecoveryOutcome.Reconciled(result.appended),
                )
            }
            if (attempt < MAX_RECONCILE_ATTEMPTS) delay(RECONCILE_RETRY_DELAY_MS)
        }
        return CompletedReconciliation(
            MAX_RECONCILE_ATTEMPTS,
            FrameCollectorOverflowRecoveryOutcome.NotApplied(checkNotNull(last)),
        )
    }

    private suspend fun rememberIncident(key: Pair<Long, Long>): Boolean = seenIncidentsMutex.withLock {
        if (!seenIncidents.add(key)) return@withLock false
        seenIncidentOrder.addLast(key)
        if (seenIncidentOrder.size > MAX_RETAINED_INCIDENTS) {
            seenIncidents.remove(seenIncidentOrder.removeFirst())
        }
        true
    }

    private suspend fun acquireConversationLock(key: Pair<Long, String>): ConversationRecoveryLock =
        conversationLocksMutex.withLock {
            conversationLocks.getOrPut(key) { ConversationRecoveryLock() }.also { it.users++ }
        }

    private suspend fun releaseConversationLock(key: Pair<Long, String>, lock: ConversationRecoveryLock) {
        conversationLocksMutex.withLock {
            lock.users--
            if (lock.users == 0 && conversationLocks[key] === lock) conversationLocks.remove(key)
        }
    }

    private fun publishRecovery(
        incident: FrameCollectorOverflowIncident,
        graphId: Long,
        attempt: Int,
        outcome: FrameCollectorOverflowRecoveryOutcome,
    ) {
        _recoveryEvents.tryEmit(incident.toRecoveryEvent(graphId, attempt, outcome))
    }

    private suspend fun clearRecoveryStateExcept(graphId: Long?) {
        seenIncidentsMutex.withLock {
            seenIncidents.removeAll { it.first != graphId }
            seenIncidentOrder.removeAll { it.first != graphId }
        }
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

    private data class CompletedReconciliation(
        val attempt: Int,
        val outcome: FrameCollectorOverflowRecoveryOutcome,
    )

    private data class ConversationRecoveryLock(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )

    private companion object {
        const val MAX_RECONCILE_ATTEMPTS = 2
        const val MAX_RETAINED_INCIDENTS = 256
        const val RECONCILE_RETRY_DELAY_MS = 50L
        const val EVENTS_SUBSCRIPTION = "events"
        const val FRAME_EVENTS_SUBSCRIPTION = "frameEvents"
    }
}
