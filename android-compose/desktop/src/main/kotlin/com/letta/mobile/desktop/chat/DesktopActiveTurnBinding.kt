package com.letta.mobile.desktop.chat

import java.lang.AutoCloseable
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.presence.ActiveTurnApproval
import com.letta.mobile.data.presence.ActiveTurnCoordinator
import com.letta.mobile.data.presence.ActiveTurnIdentity
import com.letta.mobile.data.presence.ActiveTurnState
import com.letta.mobile.data.presence.ActiveTurnTerminalKind
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Window lifetime binding: retains exact shared owners across page and gateway recreation. */
internal class DesktopActiveTurnBinding(
    private val scope: CoroutineScope,
    private val onLocalClear: (String) -> Unit,
) : AutoCloseable {
    private val collectorJobs = mutableListOf<Job>()
    private val approvalJobs = mutableListOf<Job>()
    private val owners = mutableMapOf<String, ActiveTurnCoordinator>()
    private val openTools = mutableMapOf<Pair<String, ActiveTurnIdentity>, LinkedHashMap<String, String>>()
    private val observedAliases = mutableMapOf<Pair<String, String>, ActiveTurnIdentity>()
    private val serverRuns = MutableStateFlow<Map<Pair<String, ActiveTurnIdentity>, String>>(emptyMap())
    private val acknowledgedAnswers = mutableMapOf<Triple<String, ActiveTurnIdentity, String>, Boolean>()
    private val snapshots = MutableStateFlow<Map<String, ActiveTurnState>>(emptyMap())
    val states = snapshots.asStateFlow()

    fun owner(conversationId: String, agentId: String): ActiveTurnCoordinator = owners.getOrPut(conversationId) {
        val owner = ActiveTurnCoordinator(ConversationId(conversationId), AgentId(agentId), scope, System::currentTimeMillis)
        collectorJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            owner.state.collect { turn ->
                snapshots.update { it + (conversationId to turn) }
                if (owner.outcome.value == ActiveTurnTerminalKind.LocalClear && owner.state.value == turn) {
                    onLocalClear(conversationId)
                }
            }
        }
        owner
    }

    fun start(conversationId: String, agentId: String, turn: ActiveTurnIdentity) {
        val owner = owner(conversationId, agentId)
        owner.start(turn)
        refresh(owner)
    }

    fun terminate(conversationId: String, turn: ActiveTurnIdentity, kind: ActiveTurnTerminalKind) {
        owners[conversationId]?.let { owner ->
            owner.terminate(turn, kind)
            refresh(owner)
        }
        openTools.remove(conversationId to turn)
    }

    /** Uncorrelated frames never select a current identity on callback arrival. */
    fun onEvent(event: ScopedDesktopRuntimeEvent) {
        val (conversationId, agentId, payload) = event
        val run = event.runId?.takeIf(String::isNotBlank) ?: return
        // The runtime draft exposes one run identity per turn; keep its namespace distinct from local sends.
        val turn = observedAliases[conversationId to run] ?: ActiveTurnIdentity(run, "runtime:$run")
        val owner = owner(conversationId, agentId)
        if (owner.state.value.agentId.value != agentId) return
        event.serverRunId?.takeIf(String::isNotBlank)?.let {
            observedAliases.putIfAbsent(conversationId to it, turn)
            serverRuns.update { runs -> runs + ((conversationId to turn) to it) }
        }
        if (!acceptsProgress(owner.state.value, turn, payload)) return
        applyEvent(event, turn, owner)
        refresh(owner)
    }

    private fun acceptsProgress(current: ActiveTurnState, turn: ActiveTurnIdentity, payload: RuntimeEventPayload): Boolean =
        payload is RuntimeEventPayload.RunLifecycleChanged || matchesActiveTurn(current, turn)

    private fun matchesActiveTurn(current: ActiveTurnState, turn: ActiveTurnIdentity): Boolean =
        current.runId == turn.runId && current.turnId == turn.turnId && current.startedAtEpochMs != null

    private fun applyEvent(event: ScopedDesktopRuntimeEvent, turn: ActiveTurnIdentity, owner: ActiveTurnCoordinator) {
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
            ) { answer -> acknowledgedAnswers.remove(Triple(conversationId, turn, payload.request.approvalId.value)) == answer })
            is RuntimeEventPayload.ApprovalResolved -> {
                approvalJobs += scope.launch {
                    // Transport acceptance is the authority; rendering/submitting a card never resolves it optimistically.
                    val approved = payload.decision.decision == ToolApprovalDecisionValue.Approved
                    acknowledgedAnswers[Triple(conversationId, turn, payload.decision.approvalId.value)] = approved
                    owner.submitApproval(turn, payload.decision.approvalId, approved)
                    refresh(owner)
                }
            }
            else -> Unit // Timeline, typing and transport loss are not activity boundaries.
        }
    }

    /** The coordinator owns the cancellation deadline; only this captured key can release the wait. */
    suspend fun serverRunId(conversationId: String, turn: ActiveTurnIdentity): String? =
        serverRuns.map { it[conversationId to turn] }.filterNotNull().first()

    private fun lifecycle(event: ScopedDesktopRuntimeEvent, turn: ActiveTurnIdentity, status: RuntimeRunStatus) {
        val (conversationId, agentId) = event
        when (status) {
            RuntimeRunStatus.Started -> start(conversationId, agentId, turn)
            RuntimeRunStatus.Running -> Unit
            RuntimeRunStatus.Completed -> terminate(conversationId, turn, ActiveTurnTerminalKind.Finished)
            RuntimeRunStatus.Failed -> terminate(conversationId, turn, ActiveTurnTerminalKind.Failed)
            RuntimeRunStatus.Cancelled -> terminate(conversationId, turn, ActiveTurnTerminalKind.Cancelled)
        }
    }

    private fun refresh(owner: ActiveTurnCoordinator) {
        val state = owner.state.value
        snapshots.update { it + (state.conversationId.value to state) }
    }

    override fun close() {
        collectorJobs.forEach(Job::cancel)
        approvalJobs.forEach(Job::cancel)
    }
}
