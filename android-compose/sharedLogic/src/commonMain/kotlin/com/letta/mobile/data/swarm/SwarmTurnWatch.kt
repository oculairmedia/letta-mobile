package com.letta.mobile.data.swarm

import com.letta.mobile.data.runtime.toApprovalAutoAllowRequest
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus

/** Why a send stopped watching its turn. */
internal sealed interface TurnStop {
    /** The authoritative terminal (or the approvals the turn is parked on). */
    data class Terminal(val terminal: TurnTerminal) : TurnStop

    /** The caller's wait stage was reached; the turn keeps running on the host. */
    data object WaitReached : TurnStop

    data class Rejected(val reason: String) : TurnStop

    data object TimedOut : TurnStop

    /** The turn's stream ended or the link dropped without a terminal: resume after reconnecting. */
    data object LinkLost : TurnStop
}

/** What one turn draft means to a swarm send. */
internal sealed interface DraftSignal {
    data class Report(val activity: Activity) : DraftSignal

    data class Terminal(val terminal: TurnTerminal) : DraftSignal

    data class Approval(val approval: PendingSwarmApproval) : DraftSignal

    data object Ignored : DraftSignal
}

/**
 * Reads the shared engine's drafts for a send. Lifecycle terminals end the
 * watch; an approval request (control request or streamed `approval_request_message`) means the
 * turn is parked for the person, since this client never answers approvals.
 */
internal fun RuntimeEventDraft.toSignal(): DraftSignal {
    toApprovalAutoAllowRequest()?.let { request ->
        return DraftSignal.Approval(
            PendingSwarmApproval(toolName = request.toolName ?: "tool", toolCallId = request.toolCallId, approvalId = request.requestId),
        )
    }
    return when (val event = payload) {
        is RuntimeEventPayload.RunLifecycleChanged -> event.toSignal()
        is RuntimeEventPayload.ToolCallObserved -> DraftSignal.Report(Activity.ToolCall(event.toolName.value, event.toolCallId.value))
        is RuntimeEventPayload.ToolReturnObserved -> DraftSignal.Report(Activity.ToolReturn(event.toolCallId.value, event.status.name))
        else -> DraftSignal.Ignored
    }
}

private fun RuntimeEventPayload.RunLifecycleChanged.toSignal(): DraftSignal = when (status) {
    RuntimeRunStatus.Completed -> DraftSignal.Terminal(TurnTerminal.Completed)
    RuntimeRunStatus.Failed -> DraftSignal.Terminal(TurnTerminal.Failed(reason ?: "failed"))
    RuntimeRunStatus.Cancelled -> DraftSignal.Terminal(TurnTerminal.Cancelled(reason ?: "cancelled"))
    RuntimeRunStatus.Started, RuntimeRunStatus.Running -> DraftSignal.Ignored
}
