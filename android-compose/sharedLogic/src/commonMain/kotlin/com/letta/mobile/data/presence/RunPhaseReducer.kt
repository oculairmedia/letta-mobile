package com.letta.mobile.data.presence

import com.letta.mobile.data.runtime.RuntimeFrameKind
import com.letta.mobile.data.runtime.frameKind
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecisionValue

/**
 * The one place runtime events become run phases.
 *
 * Pure and platform-neutral: a transition table over [RuntimeEventPayload], no transports, no
 * clocks (the caller passes `nowMs`), no UI state. Both platforms fold their own event stream
 * through this and publish the result into a [ConversationRunRegistry]; nothing else may map
 * events to phases, or the mascot and the timeline start telling different stories about the
 * same turn.
 *
 * Stream frames are classified by [frameKind] — the App Server's own `message_type` vocabulary,
 * shared with the turn processor — never by re-parsing the frame body here.
 */
object RunPhaseReducer {

    /** Apply one event to [state]. Returns [state] unchanged when the event says nothing about phase. */
    fun reduce(state: ConversationRunState, event: RuntimeEventPayload, nowMs: Long): ConversationRunState {
        // DONE is momentary: any next event means a new turn is under way, so the run starts from
        // rest rather than inheriting the finished turn's tool counters.
        val base = if (state.phase == RunPhase.DONE) state.reset() else state
        return when (event) {
            is RuntimeEventPayload.LocalUserAppend -> base.reset().at(RunPhase.QUEUED, nowMs)
            is RuntimeEventPayload.RetryRequested -> base.reset().at(RunPhase.QUEUED, nowMs)
            is RuntimeEventPayload.SendMarkedFailed -> base.reset().at(RunPhase.FAILED, nowMs)
            is RuntimeEventPayload.SendMarkedSent ->
                if (base.phase == RunPhase.IDLE) base.at(RunPhase.QUEUED, nowMs) else base

            is RuntimeEventPayload.ToolCallObserved -> base
                .copy(
                    openToolCallIds = base.openToolCallIds + event.toolCallId.value,
                    toolName = event.toolName.value,
                )
                .at(base.toolPhase(), nowMs)

            is RuntimeEventPayload.ToolReturnObserved -> base.onToolReturn(event.toolCallId.value, nowMs)

            is RuntimeEventPayload.ApprovalRequested -> base
                .copy(
                    // The call the user is being asked about is in flight, whether or not its own
                    // tool-call frame has been seen yet.
                    openToolCallIds = base.openToolCallIds + event.request.callId.value,
                    toolName = event.request.toolName.value,
                )
                .at(RunPhase.AWAITING_INPUT, nowMs)

            is RuntimeEventPayload.ApprovalResolved -> base.onApprovalResolved(event, nowMs)

            is RuntimeEventPayload.RunLifecycleChanged -> base.onLifecycle(event.status, nowMs)

            is RuntimeEventPayload.RemoteStreamFrame -> base.onStreamFrame(event, nowMs)

            // Snapshots, external-transport frames and file/memfs bookkeeping say nothing about
            // what the turn is doing.
            is RuntimeEventPayload.RestSnapshotReconcile,
            is RuntimeEventPayload.ExternalTransportFrame,
            is RuntimeEventPayload.MemFsCommitObserved,
            is RuntimeEventPayload.AgentFileImported,
            is RuntimeEventPayload.AgentFileExported,
            -> base
        }
    }

    /**
     * Fold in how many subagents this conversation currently has running. Subagent activity lives
     * in its own registry, not in the event stream, so it arrives as a separate signal: a tool
     * call with subagents under it is [RunPhase.DELEGATING] rather than [RunPhase.WORKING].
     */
    fun withSubagents(state: ConversationRunState, count: Int, nowMs: Long): ConversationRunState {
        val safe = count.coerceAtLeast(0)
        if (safe == state.subagentCount) return state
        val next = state.copy(subagentCount = safe)
        return if (next.phase.isToolRunning) next.at(next.toolPhase(), nowMs) else next
    }

    /** The user asked to cancel; the terminal has not arrived yet. */
    fun interrupting(state: ConversationRunState, nowMs: Long): ConversationRunState =
        if (state.phase.isBusy) state.at(RunPhase.INTERRUPTING, nowMs) else state

    // --- transitions ---------------------------------------------------------

    private fun ConversationRunState.onToolReturn(toolCallId: String, nowMs: Long): ConversationRunState {
        val open = openToolCallIds - toolCallId
        // A return that arrives while the turn is parked on an approval, or after the terminal,
        // must not resurrect the run.
        if (phase == RunPhase.AWAITING_INPUT || !phase.isBusy) return copy(openToolCallIds = open)
        return if (open.isEmpty()) {
            // Back into the gap before the next tokens; the tool name stays as "what it last did".
            copy(openToolCallIds = open).at(RunPhase.QUEUED, nowMs)
        } else {
            copy(openToolCallIds = open).at(toolPhase(), nowMs)
        }
    }

    private fun ConversationRunState.onApprovalResolved(
        event: RuntimeEventPayload.ApprovalResolved,
        nowMs: Long,
    ): ConversationRunState = when (event.decision.decision) {
        // Approved: the tool the user was asked about now runs.
        ToolApprovalDecisionValue.Approved ->
            if (openToolCalls > 0) at(toolPhase(), nowMs) else at(RunPhase.WORKING, nowMs)
        // Denied / timed out: that call will never return, so it stops counting as in flight. Any
        // other call still open keeps the turn working; only an empty set is back in the gap.
        ToolApprovalDecisionValue.Denied,
        ToolApprovalDecisionValue.TimedOut,
        -> {
            val remaining = openToolCallIds - event.decision.callId.value
            copy(toolName = if (remaining.isEmpty()) null else toolName, openToolCallIds = remaining)
                .at(if (remaining.isEmpty()) RunPhase.QUEUED else toolPhase(), nowMs)
        }
    }

    private fun ConversationRunState.onLifecycle(status: RuntimeRunStatus, nowMs: Long): ConversationRunState =
        when (status) {
            RuntimeRunStatus.Started -> reset().at(RunPhase.QUEUED, nowMs)
            RuntimeRunStatus.Running -> if (phase == RunPhase.IDLE) at(RunPhase.QUEUED, nowMs) else this
            // A terminal wins over any tool call still counted open: the turn is over whether or
            // not every return landed.
            RuntimeRunStatus.Completed -> reset().at(RunPhase.DONE, nowMs)
            RuntimeRunStatus.Cancelled -> reset().at(RunPhase.DONE, nowMs)
            RuntimeRunStatus.Failed -> reset().at(RunPhase.FAILED, nowMs)
        }

    private fun ConversationRunState.onStreamFrame(
        frame: RuntimeEventPayload.RemoteStreamFrame,
        nowMs: Long,
    ): ConversationRunState = when (frame.frameKind()) {
        RuntimeFrameKind.REASONING ->
            // A tool in flight outranks reasoning tokens: the honest answer is what it is doing.
            if (phase.isToolRunning || phase == RunPhase.AWAITING_INPUT) this else at(RunPhase.REASONING, nowMs)

        RuntimeFrameKind.ASSISTANT ->
            if (phase == RunPhase.AWAITING_INPUT) this else at(RunPhase.RESPONDING, nowMs)

        // The transports that emit ToolCallObserved/ToolReturnObserved also re-emit the raw
        // frame; count only the structured events, but let the frame move the phase so a
        // transport that emits frames alone is still honest.
        RuntimeFrameKind.TOOL_CALL ->
            if (phase == RunPhase.AWAITING_INPUT) this else at(toolPhase(), nowMs)

        RuntimeFrameKind.TOOL_RETURN ->
            if (openToolCalls == 0 && phase.isToolRunning) at(RunPhase.QUEUED, nowMs) else this

        RuntimeFrameKind.ERROR -> reset().at(RunPhase.FAILED, nowMs)

        // stop_reason / usage_statistics / anything else: the turn engine owns the terminal.
        RuntimeFrameKind.STOP_REASON,
        RuntimeFrameKind.USAGE,
        RuntimeFrameKind.OTHER,
        -> this
    }

    // --- helpers -------------------------------------------------------------

    private fun ConversationRunState.toolPhase(): RunPhase =
        if (subagentCount > 0) RunPhase.DELEGATING else RunPhase.WORKING

    /**
     * Same conversation, nothing in flight. Keeps identity and the orthogonal user-typing flag.
     * The subagent count goes too: it described the finished turn's tool call, and a new turn's
     * first tool must not read as DELEGATING until the subagent registry has spoken again.
     */
    private fun ConversationRunState.reset(): ConversationRunState =
        copy(phase = RunPhase.IDLE, toolName = null, openToolCallIds = emptySet(), subagentCount = 0)

    /** Move to [phase], stamping [nowMs] only when the phase actually changed. */
    private fun ConversationRunState.at(phase: RunPhase, nowMs: Long): ConversationRunState =
        if (this.phase == phase) this else copy(phase = phase, phaseSinceEpochMs = nowMs)
}
