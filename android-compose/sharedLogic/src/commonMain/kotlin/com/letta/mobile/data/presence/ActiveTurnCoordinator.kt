package com.letta.mobile.data.presence

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val retired = mutableSetOf<Pair<String, String>>()
    private val tools = linkedMapOf<String, String>()
    private val approvals = mutableMapOf<String, suspend (Boolean) -> Boolean>()
    private val submitting = mutableSetOf<String>()
    private val resolvedApprovals = mutableSetOf<String>()
    private val approvalTools = mutableMapOf<String, String>()
    private val operations = mutableSetOf<String>()
    private var deleted = false
    private val mutableOutcome = MutableStateFlow<ActiveTurnTerminalKind?>(null)
    /** Last local or server terminal reason; LocalClear does not assert server cancellation. */
    val outcome: StateFlow<ActiveTurnTerminalKind?> = mutableOutcome.asStateFlow()

    /** Only explicit correlated starts may introduce a new identity. */
    fun start(runId: String, turnId: String) = synchronized(lock) {
        require(runId.isNotBlank() && turnId.isNotBlank())
        if (deleted || runId to turnId in retired || state.value.matches(runId, turnId)) return@synchronized
        retireCurrent()
        clearDetails()
        mutableOutcome.value = null
        publish(state.value.copy(runId = runId, turnId = turnId, phase = ActiveTurnPhase.Thinking,
            activeTool = null, subagentCount = 0, startedAtEpochMs = now()), ActiveTurnBoundary.Start)
    }

    /** Replace open tools in event order; pending approvals and cancellation retain priority. */
    fun progress(runId: String, turnId: String, openTools: Map<String, String>, subagentCount: Int = 0) =
        synchronized(lock) {
            require(subagentCount >= 0)
            if (!active(runId, turnId)) return@synchronized
            tools.keys.retainAll(openTools.keys)
            tools.putAll(openTools)
            publish(state.value.copy(phase = phase(), activeTool = tools.values.firstOrNull(),
                subagentCount = subagentCount), ActiveTurnBoundary.Progress)
        }

    /** Bind the exact request to transport validation; true means acknowledged acceptance, not an offline enqueue. */
    fun awaitApproval(runId: String, turnId: String, requestId: String, toolCallId: String, submit: suspend (Boolean) -> Boolean) =
        synchronized(lock) {
            require(requestId.isNotBlank() && toolCallId.isNotBlank())
            if (!active(runId, turnId) || requestId in resolvedApprovals) return@synchronized
            approvals.getOrPut(requestId) { submit }
            approvalTools.getOrPut(requestId) { toolCallId }
            publish(state.value.copy(phase = ActiveTurnPhase.AwaitingInput), ActiveTurnBoundary.Progress)
        }

    /** Reserve one submission; rejected answers remain parked and may be retried. */
    suspend fun submitApproval(requestId: String, answer: Boolean): Boolean {
        val captured = synchronized(lock) {
            val current = state.value
            val submit = approvals[requestId]
            if (deleted || current.phase != ActiveTurnPhase.AwaitingInput || submit == null ||
                !submitting.add(requestId)) return false
            current to submit
        }
        try {
            val accepted = captured.second(answer)
            return synchronized(lock) {
                if (!active(captured.first.runId, captured.first.turnId) || !accepted) return@synchronized false
                approvals.remove(requestId)
                resolvedApprovals.add(requestId)
                val tool = approvalTools.remove(requestId)
                if (!answer) tools.remove(tool)
                val nextPhase = when {
                    approvals.isNotEmpty() -> ActiveTurnPhase.AwaitingInput
                    answer || tools.isNotEmpty() -> ActiveTurnPhase.ToolExecuting
                    else -> ActiveTurnPhase.Thinking
                }
                publish(state.value.copy(phase = nextPhase, activeTool = tools.values.firstOrNull()),
                    ActiveTurnBoundary.ApprovalResolved)
                true
            }
        } finally {
            synchronized(lock) {
                if (state.value.matches(captured.first.runId, captured.first.turnId)) submitting.remove(requestId)
            }
        }
    }

    /** Distinct stop gestures have distinct IDs; redelivery reuses the same operation ID. */
    fun interrupt(runId: String, turnId: String, operationId: String, abort: suspend () -> Boolean) {
        val requestedAt = now()
        val first = synchronized(lock) {
            require(operationId.isNotBlank())
            if (!active(runId, turnId) || !operations.add(operationId)) return
            val wasCancelling = state.value.phase == ActiveTurnPhase.Cancelling
            publish(state.value, ActiveTurnBoundary.Interrupt)
            if (wasCancelling) close(runId, turnId, ActiveTurnTerminalKind.LocalClear)
            !wasCancelling
        }
        if (!first) return
        // Separate timer: an abort RPC that hangs must not postpone the first-press deadline.
        scope.launch {
            delay((CANCEL_TIMEOUT_MS - (now() - requestedAt)).coerceAtLeast(0L))
            clearCancelling(runId, turnId)
        }
        scope.launch {
            val stillCurrent = synchronized(lock) { active(runId, turnId) &&
                state.value.phase == ActiveTurnPhase.Cancelling }
            if (!stillCurrent) return@launch
            val dispatched = runCatching { abort() }.getOrElse { failure ->
                when (failure) {
                    is CancellationException -> {
                        clearCancelling(runId, turnId)
                        throw failure
                    }
                    is Exception -> false
                    else -> throw failure
                }
            }
            if (!dispatched) clearCancelling(runId, turnId)
        }
    }

    /** Close only a captured exact identity, retaining terminal-before-start and retired fences. */
    fun terminate(runId: String, turnId: String, kind: ActiveTurnTerminalKind) = synchronized(lock) {
        require(runId.isNotBlank() && turnId.isNotBlank())
        if (!deleted) close(runId, turnId, kind)
    }

    /** Verified deletion tombstones this lifecycle permanently. */
    fun delete() = synchronized(lock) {
        deleted = true
        mutableOutcome.value = ActiveTurnTerminalKind.Deleted
        retireCurrent()
        clearDetails()
        publish(state.value, ActiveTurnBoundary.Delete)
    }

    private fun clearCancelling(runId: String, turnId: String) = synchronized(lock) {
        if (active(runId, turnId) && state.value.phase == ActiveTurnPhase.Cancelling) {
            close(runId, turnId, ActiveTurnTerminalKind.LocalClear)
        }
    }

    private fun close(runId: String, turnId: String, kind: ActiveTurnTerminalKind) {
        if (!retired.add(runId to turnId)) return
        val applies = state.value.matches(runId, turnId) || state.value.runId == null
        if (applies) {
            clearDetails()
            mutableOutcome.value = kind
        }
        publish(state.value.copy(runId = runId, turnId = turnId), ActiveTurnBoundary.Terminal)
        if (applies && kind == ActiveTurnTerminalKind.Deleted) delete()
    }

    private fun active(runId: String?, turnId: String?): Boolean =
        !deleted && state.value.matches(runId, turnId) && state.value.phase != ActiveTurnPhase.Idle

    private fun phase(): ActiveTurnPhase = when {
        approvals.isNotEmpty() -> ActiveTurnPhase.AwaitingInput
        tools.isNotEmpty() -> ActiveTurnPhase.ToolExecuting
        else -> ActiveTurnPhase.Thinking
    }

    private fun retireCurrent() {
        val current = state.value
        val run = current.runId ?: return
        val turn = current.turnId ?: return
        retired.add(run to turn)
    }

    private fun clearDetails() {
        tools.clear()
        approvals.clear()
        submitting.clear()
        resolvedApprovals.clear()
        approvalTools.clear()
        operations.clear()
    }

    private fun publish(candidate: ActiveTurnState, boundary: ActiveTurnBoundary) {
        mutableState.value = arbitrateActiveTurn(state.value, candidate, boundary)
    }

    private companion object {
        const val CANCEL_TIMEOUT_MS = 30_000L
    }
}

private fun ActiveTurnState.matches(runId: String?, turnId: String?): Boolean =
    this.runId == runId && this.turnId == turnId
