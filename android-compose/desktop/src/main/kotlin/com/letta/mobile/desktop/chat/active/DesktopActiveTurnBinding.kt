package com.letta.mobile.desktop.chat.active

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
import kotlinx.coroutines.launch

/** Window lifetime binding: retains exact shared owners across page and gateway recreation. */
internal class DesktopActiveTurnBinding(
    scope: CoroutineScope,
    private val onLocalClear: (String) -> Unit,
) : AutoCloseable {
    private val lifetime = Job(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + lifetime)
    private val owners = mutableMapOf<String, ActiveTurnCoordinator>()
    private val openTools = mutableMapOf<Pair<String, ActiveTurnIdentity>, LinkedHashMap<String, String>>()
    private val observedAliases = mutableMapOf<Pair<String, String>, ActiveTurnIdentity>()
    private val serverRuns = mutableMapOf<Pair<String, ActiveTurnIdentity>, String>()
    private val acknowledgedAnswers = mutableMapOf<Triple<String, ActiveTurnIdentity, String>, Boolean>()
    private val snapshots = MutableStateFlow<Map<String, ActiveTurnState>>(emptyMap())
    val states = snapshots.asStateFlow()

    fun owner(conversationId: String, agentId: String): ActiveTurnCoordinator = owners.getOrPut(conversationId) {
        val owner = ActiveTurnCoordinator(ConversationId(conversationId), AgentId(agentId), scope, System::currentTimeMillis)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
        owner(conversationId, agentId).start(turn)
        refresh(conversationId)
    }

    fun terminate(conversationId: String, turn: ActiveTurnIdentity, kind: ActiveTurnTerminalKind) {
        owners[conversationId]?.terminate(turn, kind)
        openTools.remove(conversationId to turn)
        refresh(conversationId)
    }

    /** Uncorrelated frames never select a current identity on callback arrival. */
    fun onEvent(
        conversationId: String, agentId: String, runId: String?, payload: RuntimeEventPayload, serverRunId: String? = null,
    ) {
        val run = runId?.takeIf(String::isNotBlank) ?: return
        // The runtime draft exposes one run identity per turn; keep its namespace distinct from local sends.
        val turn = observedAliases[conversationId to run] ?: ActiveTurnIdentity(run, "runtime:$run")
        serverRunId?.let {
            observedAliases.putIfAbsent(conversationId to it, turn)
            serverRuns[conversationId to turn] = it
        }
        val owner = owner(conversationId, agentId)
        if (owner.state.value.agentId.value != agentId) return
        val current = owner.state.value
        if (payload !is RuntimeEventPayload.RunLifecycleChanged &&
            (current.runId != turn.runId || current.turnId != turn.turnId || current.startedAtEpochMs == null)) return
        val toolScope = conversationId to turn
        when (payload) {
            is RuntimeEventPayload.RunLifecycleChanged -> lifecycle(conversationId, agentId, turn, payload.status)
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
            is RuntimeEventPayload.ApprovalResolved -> scope.launch {
                // Transport acceptance is the authority; rendering/submitting a card never resolves it optimistically.
                val approved = payload.decision.decision == ToolApprovalDecisionValue.Approved
                acknowledgedAnswers[Triple(conversationId, turn, payload.decision.approvalId.value)] = approved
                owner.submitApproval(turn, payload.decision.approvalId, approved)
                refresh(conversationId)
            }
            else -> Unit // Timeline, typing and transport loss are not activity boundaries.
        }
        refresh(conversationId)
    }

    fun serverRunId(conversationId: String, turn: ActiveTurnIdentity): String? = serverRuns[conversationId to turn]

    private fun lifecycle(conversationId: String, agentId: String, turn: ActiveTurnIdentity, status: RuntimeRunStatus) {
        when (status) {
            RuntimeRunStatus.Started -> start(conversationId, agentId, turn)
            RuntimeRunStatus.Running -> Unit
            RuntimeRunStatus.Completed -> terminate(conversationId, turn, ActiveTurnTerminalKind.Finished)
            RuntimeRunStatus.Failed -> terminate(conversationId, turn, ActiveTurnTerminalKind.Failed)
            RuntimeRunStatus.Cancelled -> terminate(conversationId, turn, ActiveTurnTerminalKind.Cancelled)
        }
    }

    private fun refresh(conversationId: String) {
        owners[conversationId]?.let { owner -> snapshots.update { it + (conversationId to owner.state.value) } }
    }

    override fun close() {
        lifetime.cancel()
    }
}
