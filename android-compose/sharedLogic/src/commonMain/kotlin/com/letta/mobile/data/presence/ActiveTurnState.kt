package com.letta.mobile.data.presence

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.Serializable

/** Proposed v1 foreground phase; token streaming is Thinking, not a second busy flag. */
@Serializable
enum class ActiveTurnPhase {
    /** No foreground turn remains active. */
    Idle,
    /** Waiting for or receiving reasoning/assistant output. */
    Thinking,
    /** At least one foreground tool is executing. */
    ToolExecuting,
    /** A turn-bound approval request is unresolved. */
    AwaitingInput,
    /** Abort requested; its terminal or local escape hatch is pending. */
    Cancelling,
}

/** Exact turn value within a trusted session. Idle retains identity as a closed-turn fence. */
@Serializable
data class ActiveTurnState(
    /** Resolved conversation in the trusted session. */
    val conversationId: ConversationId,
    /** Verified owner of this conversation. */
    val agentId: AgentId,
    /** Exact opaque run identity, retained after closure; null before correlation. */
    val runId: String? = null,
    /** Exact opaque turn identity, retained after closure; null before correlation. */
    val turnId: String? = null,
    /** The sole foreground activity phase. */
    val phase: ActiveTurnPhase = ActiveTurnPhase.Idle,
    /** Display name of the deterministically selected open tool, if any. */
    val activeTool: String? = null,
    /** Nonnegative number of this turn’s foreground subagents. */
    val subagentCount: Int = 0,
    /** Start timestamp retained through progress; null when idle. */
    val startedAtEpochMs: Long? = null,
)

/** Evidence supplied by a bound observer; Start alone may introduce a different turn. */
@Serializable
enum class ActiveTurnBoundary {
    /** Explicit correlated start; may introduce a new identity. */
    Start,
    /** Same-turn detail that cannot undo an approval or cancellation. */
    Progress,
    /** Accepted answer to an exact pending approval. */
    ApprovalResolved,
    /** One distinct user stop gesture, after owner-level delivery deduplication. */
    Interrupt,
    /** Correlated completion, failure or local clear. */
    Terminal,
    /** Verified deletion, followed by owner-level observer retirement. */
    Delete,
}

/** Why an exact turn closed; local clear never claims the server acknowledged cancellation. */
@Serializable
enum class ActiveTurnTerminalKind {
    /** Server reported successful completion. */
    Finished,
    /** Server reported cancellation. */
    Cancelled,
    /** Server reported terminal failure. */
    Failed,
    /** Client stopped displaying activity without server acknowledgment. */
    LocalClear,
    /** Conversation was authoritatively deleted. */
    Deleted,
}

/**
 * Contract-only arbitration of validated snapshots, without an owner, effects or transport mapping.
 * Callers serialize publication and retain retired identities outside this value. Candidate identity
 * must be captured by the operation, never filled from whatever turn is current on callback arrival.
 */
fun arbitrateActiveTurn(
    current: ActiveTurnState,
    candidate: ActiveTurnState,
    boundary: ActiveTurnBoundary,
): ActiveTurnState {
    if (boundary == ActiveTurnBoundary.Delete) {
        return if (candidate.conversationId == current.conversationId && candidate.agentId == current.agentId) {
            current.closed()
        } else {
            current
        }
    }
    if (!candidate.isResolvedFor(current)) return current
    return when (boundary) {
        ActiveTurnBoundary.Start -> if (current.sameTurn(candidate)) current else candidate
        ActiveTurnBoundary.Terminal -> current.terminated(candidate)
        ActiveTurnBoundary.Interrupt,
        ActiveTurnBoundary.Progress,
        ActiveTurnBoundary.ApprovalResolved -> current.withinTurn(candidate, boundary)
        ActiveTurnBoundary.Delete -> current.closed()
    }
}

private fun ActiveTurnState.sameTurn(candidate: ActiveTurnState): Boolean =
    candidate.runId == runId && candidate.turnId == turnId

private fun ActiveTurnState.terminated(candidate: ActiveTurnState): ActiveTurnState {
    val unbound = runId == null && turnId == null && phase == ActiveTurnPhase.Idle
    return if (sameTurn(candidate) || unbound) candidate.closed() else this
}

private fun ActiveTurnState.withinTurn(candidate: ActiveTurnState, boundary: ActiveTurnBoundary): ActiveTurnState {
    if (!sameTurn(candidate)) return this
    return when (boundary) {
        ActiveTurnBoundary.Delete -> closed()
        ActiveTurnBoundary.Interrupt -> interrupted()
        ActiveTurnBoundary.Progress -> progressed(candidate)
        ActiveTurnBoundary.ApprovalResolved -> approvalResolved(candidate)
        ActiveTurnBoundary.Start, ActiveTurnBoundary.Terminal -> this
    }
}

private fun ActiveTurnState.approvalResolved(candidate: ActiveTurnState): ActiveTurnState =
    if (phase == ActiveTurnPhase.AwaitingInput) {
        if (candidate.phase == ActiveTurnPhase.Idle) candidate.closed()
        else candidate.copy(startedAtEpochMs = startedAtEpochMs)
    } else this

private fun ActiveTurnState.isResolvedFor(current: ActiveTurnState): Boolean =
    conversationId == current.conversationId && agentId == current.agentId && runId != null && turnId != null

private fun ActiveTurnState.interrupted(): ActiveTurnState = when (phase) {
    ActiveTurnPhase.Idle -> this
    ActiveTurnPhase.Cancelling -> closed()
    ActiveTurnPhase.Thinking, ActiveTurnPhase.ToolExecuting, ActiveTurnPhase.AwaitingInput ->
        copy(phase = ActiveTurnPhase.Cancelling)
}

private fun ActiveTurnState.progressed(candidate: ActiveTurnState): ActiveTurnState = when (phase) {
    ActiveTurnPhase.Idle, ActiveTurnPhase.Cancelling -> this
    ActiveTurnPhase.AwaitingInput ->
        if (candidate.phase == ActiveTurnPhase.AwaitingInput) candidate.copy(startedAtEpochMs = startedAtEpochMs) else this
    ActiveTurnPhase.Thinking, ActiveTurnPhase.ToolExecuting ->
        if (candidate.phase == ActiveTurnPhase.Idle) this
        else candidate.copy(startedAtEpochMs = startedAtEpochMs)
}

private fun ActiveTurnState.closed(): ActiveTurnState = copy(
    phase = ActiveTurnPhase.Idle,
    activeTool = null,
    subagentCount = 0,
    startedAtEpochMs = null,
)
