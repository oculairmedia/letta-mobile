package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry

/*
 * letta-mobile-qygvv.2: the engine-side glue around [TurnBoundaryGate], kept out of
 * AppServerTurnEngine so the engine's collect loop only sequences these steps.
 */

/**
 * Decides [input]'s boundary. `turn_finished` and an idle loop status after evidence are the
 * server's own turn boundaries. Returns null when the frame closes a turn this key already settled
 * (a duplicate `turn_id`, a superseded or settled run): the caller skips it.
 */
internal fun TurnBoundaryGate.decideOrDrop(
    input: TurnBoundaryInput,
    scope: AppServerRuntimeScope,
): TurnBoundaryDecision? {
    val decision = decide(input)
    if (decision !is TurnBoundaryDecision.Drop) return decision
    Telemetry.event(
        "AppServerTurnEngine", "terminal.boundary_dropped",
        "frameType" to (input.received.frame.type ?: "<unknown>"),
        "reason" to decision.reason,
        "conversationId" to scope.conversationId,
        "eventSeq" to input.received.eventSeqOrNull(),
    )
    return null
}

/** Promotes the first run id the projected [drafts] carry, unless [boundary] forbids it. */
internal fun TurnRunIdGate.promoteAtBoundary(
    boundary: TurnBoundaryDecision,
    drafts: List<RuntimeEventDraft>,
    leaseToken: Long,
) {
    if (!boundary.allowsRunPromotion) return
    drafts.firstOrNull { it.runId != null }?.runId?.value?.let { runId -> promote(runId, leaseToken) }
}

/**
 * The drafts to process for this frame: the projected [drafts], plus the terminal an idle loop
 * status synthesizes for the lease's promoted run ([leaseRunId]).
 */
internal fun TurnBoundaryDecision.withLoopIdleTerminal(
    drafts: List<RuntimeEventDraft>,
    command: TurnCommand,
    leaseRunId: String?,
): List<RuntimeEventDraft> =
    if (this is TurnBoundaryDecision.LoopIdle) drafts + command.loopIdleTerminal(status, leaseRunId) else drafts

private fun TurnCommand.loopIdleTerminal(status: RuntimeRunStatus, leaseRunId: String?): RuntimeEventDraft {
    val runId = leaseRunId?.takeIf { it.isNotBlank() }?.let(::RunId)
    return when (status) {
        RuntimeRunStatus.Cancelled -> runLifecycleDraft(status, runId = runId, reason = "App Server loop idle after abort")
        else -> runLifecycleDraft(RuntimeRunStatus.Completed, runId = runId)
    }
}

/** A terminal [draft] settled its run (or, lacking one, the lease's [leaseRunId]). */
internal fun TurnBoundaryGate.noteSettledTerminal(draft: RuntimeEventDraft, leaseRunId: String?) =
    noteSettled(draft.runId?.value ?: leaseRunId)

internal fun AppServerReceivedFrame.eventSeqOrNull(): Long? =
    when (val f = frame) {
        is AppServerInboundFrame.StreamDelta -> f.eventSeq
        is AppServerInboundFrame.UpdateLoopStatus -> f.eventSeq
        is AppServerInboundFrame.TurnFinished -> f.eventSeq
        is AppServerInboundFrame.UpdateDeviceStatus -> f.eventSeq
        is AppServerInboundFrame.UpdateQueue -> f.eventSeq
        is AppServerInboundFrame.UpdateSubagentState -> f.eventSeq
        else -> null
    }
