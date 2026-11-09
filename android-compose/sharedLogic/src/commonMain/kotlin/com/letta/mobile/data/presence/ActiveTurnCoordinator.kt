package com.letta.mobile.data.presence

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.runtime.ToolCallId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Correlation captured by an observer or action within one trusted session. */
data class ActiveTurnIdentity(val runId: String, val turnId: String) {
    init {
        require(runId.isNotBlank() && turnId.isNotBlank())
    }
}

/** Exact request and tool binding; submit validates the answer and returns acknowledged acceptance. */
class ActiveTurnApproval(
    val requestId: ToolApprovalId,
    val toolCallId: ToolCallId,
    val submit: suspend (Boolean) -> Boolean,
) {
    init {
        require(requestId.value.isNotBlank() && toolCallId.value.isNotBlank())
    }
}

/** Session-bound owner of one conversation with ownership verified before construction. */
class ActiveTurnCoordinator(
    conversationId: ConversationId,
    agentId: AgentId,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val lock = SynchronizedObject()
    private val mutableState = MutableStateFlow(ActiveTurnState(conversationId, agentId))
    /** Atomic foreground snapshot, independent of chat focus. */
    val state: StateFlow<ActiveTurnState> = mutableState.asStateFlow()
    private val retired = mutableSetOf<ActiveTurnIdentity>()
    private val tools = linkedMapOf<String, String?>()
    private val requests = ActiveTurnRequests()
    private val operations = mutableSetOf<String>()
    private var deleted = false
    private val mutableOutcome = MutableStateFlow<ActiveTurnTerminalKind?>(null)
    /** Last terminal reason; LocalClear does not assert server cancellation. */
    val outcome: StateFlow<ActiveTurnTerminalKind?> = mutableOutcome.asStateFlow()

    /** Only explicit correlated starts may introduce a new identity. */
    fun start(turn: ActiveTurnIdentity) {
        synchronized(lock) {
            if (deleted) return
            if (turn in retired || state.value.matches(turn)) return
            state.value.identity()?.let(retired::add)
            clearDetails()
            mutableOutcome.value = null
            publish(state.value.copy(runId = turn.runId, turnId = turn.turnId, phase = ActiveTurnPhase.Thinking,
                activeTool = null, subagentCount = 0, startedAtEpochMs = now()), ActiveTurnBoundary.Start)
        }
    }

    /** Replace open tools in event order; pending approvals and cancellation retain priority. */
    fun progress(turn: ActiveTurnIdentity, openTools: Map<String, String>, subagentCount: Int = 0) {
        synchronized(lock) {
            require(subagentCount >= 0)
            if (!active(turn)) return
            tools.keys.retainAll(openTools.keys)
            tools.putAll(openTools)
            publish(state.value.copy(phase = requests.phase(tools.isNotEmpty()), activeTool = tools.values.firstOrNull(),
                subagentCount = subagentCount), ActiveTurnBoundary.Progress)
        }
    }

    /** Bind the exact request to transport validation, not an offline enqueue. */
    fun awaitApproval(turn: ActiveTurnIdentity, approval: ActiveTurnApproval) {
        synchronized(lock) {
            if (!active(turn) || !requests.register(approval)) return
            tools.getOrPut(approval.toolCallId.value) { null }
            publish(state.value.copy(phase = ActiveTurnPhase.AwaitingInput), ActiveTurnBoundary.Progress)
        }
    }

    /** Reserve one submission; rejected answers remain parked and may be retried. */
    suspend fun submitApproval(turn: ActiveTurnIdentity, requestId: ToolApprovalId, answer: Boolean): Boolean {
        val captured = synchronized(lock) {
            if (state.value.phase != ActiveTurnPhase.AwaitingInput || !state.value.matches(turn)) return false
            val request = requests.reserve(requestId) ?: return false
            turn to request
        }
        try {
            val accepted = captured.second.submit(answer)
            return synchronized(lock) {
                if (!active(captured.first)) return@synchronized false
                if (!accepted) return@synchronized false
                val tool = requests.resolve(requestId)
                when (answer) {
                    true -> tools.getOrPut(tool.value) { null }
                    false -> tools.remove(tool.value)
                }
                publish(state.value.copy(phase = requests.phase(answer || tools.isNotEmpty()),
                    activeTool = tools.values.firstOrNull()), ActiveTurnBoundary.ApprovalResolved)
                true
            }
        } finally {
            synchronized(lock) {
                if (state.value.matches(captured.first)) requests.release(requestId)
            }
        }
    }

    /** Compatibility overload for callers holding only the request ID. */
    suspend fun submitApproval(requestId: ToolApprovalId, answer: Boolean): Boolean {
        val turn = synchronized(lock) { state.value.identity() } ?: return false
        return submitApproval(turn, requestId, answer)
    }

    /** Distinct stop gestures have distinct IDs; redelivery reuses the same operation ID. */
    fun interrupt(turn: ActiveTurnIdentity, operationId: String, abort: suspend () -> Boolean) {
        val requestedAt = now()
        synchronized(lock) {
            require(operationId.isNotBlank())
            if (!active(turn) || !operations.add(operationId)) return
            if (state.value.phase == ActiveTurnPhase.Cancelling) {
                close(turn, ActiveTurnTerminalKind.LocalClear)
                return
            }
            publish(state.value, ActiveTurnBoundary.Interrupt)
        }
        val timeout = (CANCEL_TIMEOUT_MS - (now() - requestedAt)).coerceAtLeast(0L)
        val abortJob = scope.launch {
            val stillCurrent = synchronized(lock) { active(turn) && state.value.phase == ActiveTurnPhase.Cancelling }
            if (!stillCurrent) return@launch
            val result = runCatching { abort() }
            if (!result.getOrDefault(false)) clearCancelling(turn)
            result.exceptionOrNull()?.let(::rethrowAbortFailure)
        }
        scope.launch {
            delay(timeout)
            if (abortJob.isActive) abortJob.cancel()
            clearCancelling(turn)
        }
    }

    /** Close only a captured exact identity, retaining terminal-before-start and retired fences. */
    fun terminate(turn: ActiveTurnIdentity, kind: ActiveTurnTerminalKind) {
        synchronized(lock) {
            if (!deleted) close(turn, kind)
        }
    }

    /** Verified deletion tombstones this lifecycle permanently. */
    fun delete() {
        synchronized(lock) {
            deleted = true
            mutableOutcome.value = ActiveTurnTerminalKind.Deleted
            state.value.identity()?.let(retired::add)
            clearDetails()
            publish(state.value, ActiveTurnBoundary.Delete)
        }
    }

    private fun clearCancelling(turn: ActiveTurnIdentity) {
        synchronized(lock) {
            if (active(turn) && state.value.phase == ActiveTurnPhase.Cancelling) {
                close(turn, ActiveTurnTerminalKind.LocalClear)
            }
        }
    }

    private fun close(turn: ActiveTurnIdentity, kind: ActiveTurnTerminalKind) {
        if (!retired.add(turn)) return
        val applies = state.value.matches(turn) || state.value.runId == null
        if (applies) {
            clearDetails()
            mutableOutcome.value = kind
        }
        publish(state.value.copy(runId = turn.runId, turnId = turn.turnId), ActiveTurnBoundary.Terminal)
        if (applies && kind == ActiveTurnTerminalKind.Deleted) delete()
    }

    private fun active(turn: ActiveTurnIdentity): Boolean {
        if (deleted) return false
        return state.value.matches(turn) && state.value.phase != ActiveTurnPhase.Idle
    }

    private fun clearDetails() {
        tools.clear()
        requests.clear()
        operations.clear()
    }

    private fun publish(candidate: ActiveTurnState, boundary: ActiveTurnBoundary) {
        mutableState.value = arbitrateActiveTurn(state.value, candidate, boundary)
    }

    private companion object {
        const val CANCEL_TIMEOUT_MS = 30_000L
    }
}

private class ActiveTurnRequests {
    private val pending = mutableMapOf<ToolApprovalId, ActiveTurnApproval>()
    private val submitting = mutableSetOf<ToolApprovalId>()
    private val resolved = mutableSetOf<ToolApprovalId>()

    fun register(approval: ActiveTurnApproval): Boolean {
        if (approval.requestId in resolved || approval.requestId in pending) return false
        pending[approval.requestId] = approval
        return true
    }

    fun reserve(requestId: ToolApprovalId): ActiveTurnApproval? {
        val request = pending[requestId] ?: return null
        if (!submitting.add(requestId)) return null
        return request
    }

    fun resolve(requestId: ToolApprovalId): ToolCallId {
        resolved.add(requestId)
        return requireNotNull(pending.remove(requestId)).toolCallId
    }

    fun release(requestId: ToolApprovalId) {
        submitting.remove(requestId)
    }

    fun phase(hasTools: Boolean): ActiveTurnPhase = when {
        pending.isNotEmpty() -> ActiveTurnPhase.AwaitingInput
        hasTools -> ActiveTurnPhase.ToolExecuting
        else -> ActiveTurnPhase.Thinking
    }

    fun clear() {
        pending.clear()
        submitting.clear()
        resolved.clear()
    }
}

private fun ActiveTurnState.matches(turn: ActiveTurnIdentity): Boolean = runId == turn.runId && turnId == turn.turnId

private fun ActiveTurnState.identity(): ActiveTurnIdentity? {
    val run = runId ?: return null
    val turn = turnId ?: return null
    return ActiveTurnIdentity(run, turn)
}

private fun rethrowAbortFailure(failure: Throwable) {
    when (failure) {
        is CancellationException -> throw failure
        is Exception -> Unit
        else -> throw failure
    }
}
