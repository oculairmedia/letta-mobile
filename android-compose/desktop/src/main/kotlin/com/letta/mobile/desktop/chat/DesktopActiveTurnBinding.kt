package com.letta.mobile.desktop.chat

import java.lang.AutoCloseable

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.presence.ActiveTurnCoordinator
import com.letta.mobile.data.presence.ActiveTurnIdentity
import com.letta.mobile.data.presence.ActiveTurnRuntimeEvent
import com.letta.mobile.data.presence.ActiveTurnSession
import com.letta.mobile.data.presence.ActiveTurnTerminalKind
import com.letta.mobile.runtime.RunId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.letta.mobile.data.flow.DerivedStateFlow

/** Desktop scope conversion only; the shared session owns all event and effect bookkeeping. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class DesktopActiveTurnBinding(
    private val scope: CoroutineScope,
    private val registry: com.letta.mobile.data.presence.ConversationRunRegistry?,
    onLocalClear: (String) -> Unit,
) : AutoCloseable {
    constructor(scope: CoroutineScope, onLocalClear: (String) -> Unit) : this(scope, null, onLocalClear)
    private val sessions = kotlinx.coroutines.flow.MutableStateFlow<ActiveTurnSession?>(null)
    private var sessionJob: kotlinx.coroutines.Job? = null
    val hasSession get() = sessions.value != null
    fun bindSessions(source: StateFlow<ActiveTurnSession?>) {
        sessionJob?.cancel()
        sessions.value = source.value
        sessionJob = scope.launch { source.collect { sessions.value = it } }
    }
    private val fallback = lazy { ActiveTurnSession(scope) }
    private val session get() = sessions.value ?: fallback.value
    val states = DerivedStateFlow(
        sessions.flatMapLatest { (it ?: fallback.value).states }.map { values -> values.mapKeys { it.key.value } },
    ) {
        session.states.value.mapKeys { it.key.value }
    }
    val thinkingConversationId = DerivedStateFlow(states.map { turns ->
        turns.values.firstOrNull { it.phase == com.letta.mobile.data.presence.ActiveTurnPhase.Thinking }?.conversationId?.value
    }) { states.value.values.firstOrNull {
        it.phase == com.letta.mobile.data.presence.ActiveTurnPhase.Thinking
    }?.conversationId?.value }
    private val clearObserver = scope.launch {
        var previous = emptyMap<String, com.letta.mobile.data.presence.ActiveTurnState>()
        states.collect { turns ->
            registry?.let(session::bindRegistry)
            turns.filter { (key, value) -> previous[key] != value }.values.forEach { turn ->
                if (session.outcomes[turn.conversationId] == ActiveTurnTerminalKind.LocalClear)
                    onLocalClear(turn.conversationId.value)
            }
            previous = turns
        }
    }
    fun owner(conversationId: String, agentId: String): ActiveTurnCoordinator =
        session.owner(ConversationId(conversationId), AgentId(agentId))
    fun start(conversationId: String, agentId: String, turn: ActiveTurnIdentity) =
        session.start(ConversationId(conversationId), AgentId(agentId), turn)
    fun terminate(conversationId: String, turn: ActiveTurnIdentity, kind: ActiveTurnTerminalKind) =
        session.terminate(ConversationId(conversationId), turn, kind)
    fun onEvent(event: ScopedDesktopRuntimeEvent) = session.onEvent(ActiveTurnRuntimeEvent(
        ConversationId(event.conversationId), AgentId(event.agentId), event.payload,
        event.runId?.let(::RunId), event.serverRunId?.let(::RunId),
    ))
    suspend fun serverRunId(conversationId: String, turn: ActiveTurnIdentity): String? =
        session.serverRunId(ConversationId(conversationId), turn)
    suspend fun reconcile(conversation: DesktopConversationSummary) {
        val agent = conversation.agentId ?: return
        session.reconcile(ConversationId(conversation.id), AgentId(agent))
    }
    suspend fun abortCaptured(
        captured: com.letta.mobile.data.presence.ActiveTurnState,
        turn: ActiveTurnIdentity,
        aborter: DesktopTurnAborter?,
        transport: com.letta.mobile.data.transport.api.IChannelTransport?,
    ): Boolean {
        val conversation = captured.conversationId
        val channel = transport?.activeTurnEvents
        return when {
            aborter is DesktopAppServerChatGateway -> aborter.abortConversationTurn(
                conversation, session.serverRunId(conversation, turn)?.let(::DesktopRunId),
            )
            channel != null -> session.serverRunId(conversation, turn)?.let { server ->
                channel.abortExact(captured.agentId, com.letta.mobile.runtime.ConversationId(conversation.value), RunId(server))
            } ?: false
            else -> aborter?.abortConversationTurn(conversation.value) ?: false
        }
    }
    fun delete(conversation: ConversationId) = session.delete(conversation)
    override fun close() {
        sessionJob?.cancel()
        clearObserver.cancel()
        if (fallback.isInitialized()) fallback.value.close()
    }
}
