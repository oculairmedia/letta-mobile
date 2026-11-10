package com.letta.mobile.data.presence

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.Run
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Session lifetime binding: retains exact shared owners across presentation recreation. */
class ActiveTurnSession(
    private val scope: CoroutineScope,
    private val onLocalClear: (ConversationId) -> Unit = {},
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val readRuns: (suspend (ConversationId, AgentId, RunId?) -> List<Run>)? = null,
) : AutoCloseable {
    private var closed = false
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val collectorJobs = mutableListOf<Job>()
    private val approvalJobs = mutableListOf<Job>()
    private val registries = mutableSetOf<ConversationRunRegistry>()
    private val deleted = mutableSetOf<ConversationId>()
    private val owners = mutableMapOf<ConversationId, ActiveTurnCoordinator>()
    private val openTools = mutableMapOf<Pair<ConversationId, ActiveTurnIdentity>, LinkedHashMap<String, String>>()
    private val observedAliases = mutableMapOf<Pair<ConversationId, String>, ActiveTurnIdentity>()
    private val serverRuns = MutableStateFlow<Map<Pair<ConversationId, ActiveTurnIdentity>, String>>(emptyMap())
    private val acknowledgedAnswers = mutableMapOf<Triple<ConversationId, ActiveTurnIdentity, String>, Boolean>()
    private val snapshots = MutableStateFlow<Map<ConversationId, ActiveTurnState>>(emptyMap())
    /** Complete owner snapshots, keyed by verified conversation identity. */
    val states = snapshots.asStateFlow()
    /** Outcome metadata remains readable while a retired session's final observer drains. */
    val outcomes: Map<ConversationId, ActiveTurnTerminalKind?>
        get() = kotlinx.atomicfu.locks.synchronized(lock) { owners.mapValues { it.value.outcome.value } }

    /** Resolve the retained owner, rejecting ambiguous defaults and foreign agent rebinding. */
    fun owner(conversationId: ConversationId, agentId: AgentId): ActiveTurnCoordinator {
        return kotlinx.atomicfu.locks.synchronized(lock) {
            check(!closed) { "Active turn session retired" }
            require(conversationId.value != com.letta.mobile.data.model.IdFormats.DEFAULT_CONVERSATION_ID) {
                "Default conversation requires verified resolution"
            }
            val known = owners[conversationId]
            require(known == null || known.state.value.agentId == agentId) { "Conversation belongs to another agent" }
            owners.getOrPut(conversationId) { createOwner(conversationId, agentId) }
        }
    }

    private fun createOwner(conversation: ConversationId, agent: AgentId): ActiveTurnCoordinator {
        val owner = ActiveTurnCoordinator(conversation, agent, scope, now)
        owners[conversation] = owner
        if (conversation in deleted) owner.delete()
        collectorJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            owner.state.collect { turn ->
                snapshots.update { it + (conversation to turn) }
                if (owner.outcome.value == ActiveTurnTerminalKind.LocalClear && owner.state.value == turn) {
                    onLocalClear(conversation)
                }
            }
        }
        if (readRuns != null) collectorJobs += watch(owner)
        return owner
    }

    private fun watch(owner: ActiveTurnCoordinator) = scope.launch {
        while (true) {
            kotlinx.coroutines.delay(RECONCILE_INTERVAL_MS)
            reconcileIfActive(owner)
        }
    }

    /** One session projection covers background turns even when no presentation is attached. */
    fun bindRegistry(registry: ConversationRunRegistry) = kotlinx.atomicfu.locks.synchronized(lock) {
        if (!registries.add(registry)) return
        collectorJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            states.collect { turns ->
                turns.values.forEach { turn -> registry.bindActiveTurn(owner(turn.conversationId, turn.agentId)) }
            }
        }
    }

    /** Introduce an explicitly correlated start within this session. */
    fun start(conversationId: ConversationId, agentId: AgentId, turn: ActiveTurnIdentity) {
        val owner = owner(conversationId, agentId)
        owner.start(turn)
        refresh(owner)
    }

    /** Close only the captured identity and release its tool bookkeeping. */
    fun terminate(conversationId: ConversationId, turn: ActiveTurnIdentity, kind: ActiveTurnTerminalKind) {
        owners[conversationId]?.let { owner ->
            owner.terminate(turn, kind)
            refresh(owner)
        }
        openTools.remove(conversationId to turn)
    }

    /** Uncorrelated frames never select a current identity on callback arrival. */
    fun onEvent(event: ActiveTurnRuntimeEvent) {
        kotlinx.atomicfu.locks.synchronized(lock) {
            val (conversationId, agentId, payload) = event
            if (!acceptsEventScope(event)) return
            val run = event.runId?.value?.takeIf(String::isNotBlank) ?: return
            // The runtime draft exposes one run identity per turn; keep its namespace distinct from local sends.
            val turn = observedAliases[conversationId to run]
                ?: event.serverRunId?.value?.let { observedAliases[conversationId to it] }
                ?: ActiveTurnIdentity(run, "runtime:$run")
            observedAliases.getOrPut(conversationId to run) { turn }
            val owner = owner(conversationId, agentId)
            event.serverRunId?.value?.takeIf(String::isNotBlank)?.let {
                observedAliases.getOrPut(conversationId to it) { turn }
                serverRuns.update { runs -> runs + ((conversationId to turn) to it) }
            }
            if (!acceptsProgress(owner.state.value, turn, payload)) return
            applyEvent(event, turn, owner)
            refresh(owner)
        }
    }

    private fun acceptsEventScope(event: ActiveTurnRuntimeEvent): Boolean {
        return when {
            closed -> false
            event.conversationId in deleted -> false
            event.conversationId.value == com.letta.mobile.data.model.IdFormats.DEFAULT_CONVERSATION_ID -> false
            else -> owners[event.conversationId]?.state?.value?.agentId?.let { it == event.agentId } ?: true
        }
    }

    private fun applyEvent(event: ActiveTurnRuntimeEvent, turn: ActiveTurnIdentity, owner: ActiveTurnCoordinator) {
        val (conversationId, _, payload) = event
        val toolScope = conversationId to turn
        when (payload) {
            is RuntimeEventPayload.RunLifecycleChanged -> lifecycle(event, turn, payload.status)
            is RuntimeEventPayload.ToolCallObserved -> {
                openTools.getOrPut(toolScope, ::linkedMapOf)[payload.toolCallId.value] = payload.toolName.value
                owner.progress(turn, openTools.getValue(toolScope))
            }
            is RuntimeEventPayload.ToolReturnObserved -> {
                openTools[toolScope]?.remove(payload.toolCallId.value)
                owner.progress(turn, openTools[toolScope].orEmpty())
            }
            is RuntimeEventPayload.ApprovalRequested -> owner.awaitApproval(turn, ActiveTurnApproval(
                payload.request.approvalId, payload.request.callId,
            ) { answer -> kotlinx.atomicfu.locks.synchronized(lock) {
                acknowledgedAnswers.remove(Triple(conversationId, turn, payload.request.approvalId.value)) == answer
            } })
            is RuntimeEventPayload.ApprovalResolved -> {
                approvalJobs += scope.launch {
                    // Transport acceptance is the authority; rendering/submitting a card never resolves it optimistically.
                    val approved = payload.decision.decision == ToolApprovalDecisionValue.Approved
                    kotlinx.atomicfu.locks.synchronized(lock) {
                        acknowledgedAnswers[Triple(conversationId, turn, payload.decision.approvalId.value)] = approved
                    }
                    owner.submitApproval(turn, payload.decision.approvalId, approved)
                    refresh(owner)
                }
            }
            else -> Unit // Timeline, typing and transport loss are not activity boundaries.
        }
    }

    /** The coordinator owns the cancellation deadline; only this captured key can release the wait. */
    suspend fun serverRunId(conversationId: ConversationId, turn: ActiveTurnIdentity): String? {
        val owner = kotlinx.atomicfu.locks.synchronized(lock) { owners[conversationId] } ?: return null
        return serverRuns.combine(owner.state) { runs, current ->
            runs[conversationId to turn] to matchesActiveTurn(current, turn)
        }.first { it.first != null || !it.second }.takeIf { it.second }?.first
    }

    private fun refresh(owner: ActiveTurnCoordinator) {
        val state = owner.state.value
        snapshots.update { it + (state.conversationId to state) }
    }

    /** Reconcile an exact captured identity before a recreated presentation can read it. */
    suspend fun reconcile(conversation: ConversationId, agent: AgentId) {
        val read = readRuns ?: return
        if (kotlinx.atomicfu.locks.synchronized(lock) { conversation in deleted }) return
        val owner = owner(conversation, agent)
        val captured = owner.state.value
        val identity = captured.identity()
        val server = kotlinx.atomicfu.locks.synchronized(lock) {
            identity?.takeUnless { captured.phase == ActiveTurnPhase.Idle }?.let { serverRuns.value[conversation to it] }
        }?.let(::RunId)
        if (captured.phase != ActiveTurnPhase.Idle && server == null) return
        val runs = read(conversation, agent, server)
        kotlinx.atomicfu.locks.synchronized(lock) {
            // Progress of the same turn cannot invalidate authoritative closure; replacement identity does.
            if (owner.state.value.identity() == captured.identity()) applyReconciledRuns(captured, server, runs)
        }
    }

    /** Verified deletion fences every later event in this session. */
    fun delete(conversation: ConversationId) = kotlinx.atomicfu.locks.synchronized(lock) {
        deleted.add(conversation)
        owners[conversation]?.let { owner -> owner.delete(); refresh(owner) }
    }

    private companion object {
        const val RECONCILE_INTERVAL_MS = 5_000L
    }

    override fun close() = kotlinx.atomicfu.locks.synchronized(lock) {
        closed = true
        registries.forEach { registry -> owners.values.forEach(registry::unbindActiveTurn) }
        collectorJobs.forEach(Job::cancel)
        approvalJobs.forEach(Job::cancel)
    }
}

/**
 * Correlated runtime event within a trusted backend generation.
 * @property conversationId Verified conversation scope.
 * @property agentId Verified conversation owner.
 * @property payload Runtime boundary or detail.
 * @property runId Stable command identity, including the interval before server correlation.
 * @property serverRunId Exact server identity when established; never a current-focus fallback.
 */
data class ActiveTurnRuntimeEvent(
    val conversationId: ConversationId,
    val agentId: AgentId,
    val payload: RuntimeEventPayload,
    val runId: RunId? = null,
    val serverRunId: RunId? = null,
)

private fun acceptsProgress(current: ActiveTurnState, turn: ActiveTurnIdentity, payload: RuntimeEventPayload): Boolean {
    return payload is RuntimeEventPayload.RunLifecycleChanged || matchesActiveTurn(current, turn)
}

private fun matchesActiveTurn(current: ActiveTurnState, turn: ActiveTurnIdentity): Boolean {
    return current.startedAtEpochMs != null && current.identity() == turn
}


private fun ActiveTurnSession.lifecycle(event: ActiveTurnRuntimeEvent, turn: ActiveTurnIdentity, status: RuntimeRunStatus) {
        val (conversationId, agentId) = event
        when (status) {
            RuntimeRunStatus.Started -> start(conversationId, agentId, turn)
            RuntimeRunStatus.Running -> Unit
            RuntimeRunStatus.Completed -> terminate(conversationId, turn, ActiveTurnTerminalKind.Finished)
            RuntimeRunStatus.Failed -> terminate(conversationId, turn, ActiveTurnTerminalKind.Failed)
            RuntimeRunStatus.Cancelled -> terminate(conversationId, turn, ActiveTurnTerminalKind.Cancelled)
        }
    }
