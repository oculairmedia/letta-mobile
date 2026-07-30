package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Walk a server message snapshot and apply any approval_response +
 * tool_return hints to existing TOOL_CALL events in the timeline. Flips
 * approvalDecided=true and attaches toolReturnContent as appropriate.
 * Must be invoked inside writeMutex.
 */
fun applyReturnsAndResponsesFromSnapshot(
    snapshot: List<LettaMessage>,
    state: MutableStateFlow<Timeline>,
) {
    val evidence = approvalTimelineEvidence(snapshot)
    if (evidence.responsesByRequestId.isEmpty() && evidence.returnsByCallId.isEmpty()) return
    val newEvents = state.value.events.map { ev ->
        if (ev !is TimelineEvent.Confirmed || ev.messageType != TimelineMessageType.TOOL_CALL) {
            return@map ev
        }
        val matchingReturns = ev.matchingToolReturns(evidence)
        val matchingReturn = matchingReturns.firstOrNull()?.second
        val byResponse = ev.hasExplicitApprovalResponse(evidence)
        val byReturn = if (ev.approvalRequestId == null) matchingReturns.isNotEmpty()
            else ev.allApprovalCallsReturned(matchingReturns)
        if (matchingReturn == null && !byResponse && !byReturn) return@map ev
        // letta-mobile-fe51r: shared fold keeps projected previews from
        // clobbering full bodies and tracks truncation markers per call id.
        val fold = foldToolReturnBodies(ev.toolReturnContentByCallId, ev.toolReturnTruncationByCallId, matchingReturns)
        val returnIsErrorByCallId = ev.toolReturnIsErrorByCallId + matchingReturns.associate { (callId, toolReturn) ->
            callId to (toolReturn.isErr == true || toolReturn.status == "error")
        }
        val firstCallId = matchingReturns.firstOrNull()?.first
        ev.copy(
            approvalDecided = byResponse || byReturn || ev.approvalDecided,
            toolReturnContent = firstCallId?.let { fold.contentByCallId[it] }
                ?: ev.toolReturnContent,
            toolReturnIsError = matchingReturn?.let { it.isErr == true || it.status == "error" }
                ?: ev.toolReturnIsError,
            toolReturnContentByCallId = fold.contentByCallId.toTimelinePersistentMap(),
            toolReturnIsErrorByCallId = returnIsErrorByCallId.toTimelinePersistentMap(),
            toolReturnTruncationByCallId = fold.truncationByCallId.toTimelinePersistentMap(),
        )
    }
    if (newEvents !== state.value.events) {
        // Recompute the stable-prefix fingerprint: this reconcile can attach a
        // tool return/approval to a NON-tail tool-call event. data class copy()
        // reuses the existing stablePrefixVersion, so without this the projector's
        // replace-tail fast path would treat the (unchanged size/version/tail)
        // timeline as a no-op and never repaint the updated card (Codex review).
        val persisted = newEvents.toTimelinePersistentList()
        state.value = state.value.copy(
            events = persisted,
            stablePrefixVersion = persisted.stablePrefixFingerprint(),
        )
    }
}
