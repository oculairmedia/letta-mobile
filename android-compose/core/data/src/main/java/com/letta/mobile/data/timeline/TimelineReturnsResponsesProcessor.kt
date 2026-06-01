package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Walk a server message snapshot and apply any approval_response +
 * tool_return hints to existing TOOL_CALL events in the timeline. Flips
 * approvalDecided=true and attaches toolReturnContent as appropriate.
 * Must be invoked inside writeMutex.
 */
internal fun applyReturnsAndResponsesFromSnapshot(
    snapshot: List<LettaMessage>,
    state: MutableStateFlow<Timeline>,
) {
    val decidedIds = snapshot.filterIsInstance<ApprovalResponseMessage>()
        .mapNotNull { it.approvalRequestId }
        .filter { it.isNotBlank() }
        .toSet()
    val returnsByCallId: Map<String, ToolReturnMessage> =
        snapshot.filterIsInstance<ToolReturnMessage>()
            .mapNotNull { r ->
                val callId = r.toolCallId
                if (!callId.isNullOrBlank()) callId to r else null
            }
            .toMap()
    if (decidedIds.isEmpty() && returnsByCallId.isEmpty()) return
    val returnedToolCallIds = returnsByCallId.keys
    val newEvents = state.value.events.map { ev ->
        if (ev !is TimelineEvent.Confirmed || ev.messageType != TimelineMessageType.TOOL_CALL) {
            return@map ev
        }
        val matchingReturns = ev.toolCalls.mapNotNull { tc ->
            val callId = tc.effectiveId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val toolReturn = returnsByCallId[callId] ?: return@mapNotNull null
            callId to toolReturn
        }
        val matchingReturn = matchingReturns.firstOrNull()?.second
        val byResponse = ev.approvalRequestId != null && ev.approvalRequestId in decidedIds
        val byReturn = ev.toolCalls.any { toolCall ->
            toolCall.effectiveId.takeIf { it.isNotBlank() }?.let { it in returnedToolCallIds } == true
        }
        if (matchingReturn == null && !byResponse && !byReturn) return@map ev
        val returnContentByCallId = ev.toolReturnContentByCallId + matchingReturns.mapNotNull { (callId, toolReturn) ->
            toolReturn.toolReturn.funcResponse?.let { callId to it }
        }.toMap()
        val returnIsErrorByCallId = ev.toolReturnIsErrorByCallId + matchingReturns.associate { (callId, toolReturn) ->
            callId to (toolReturn.isErr == true || toolReturn.status == "error")
        }
        ev.copy(
            approvalDecided = byResponse || byReturn || ev.approvalDecided,
            toolReturnContent = matchingReturn?.toolReturn?.funcResponse
                ?: ev.toolReturnContent,
            toolReturnIsError = matchingReturn?.let { it.isErr == true || it.status == "error" }
                ?: ev.toolReturnIsError,
            toolReturnContentByCallId = returnContentByCallId,
            toolReturnIsErrorByCallId = returnIsErrorByCallId,
        )
    }
    if (newEvents !== state.value.events) {
        state.value = state.value.copy(events = newEvents)
    }
}
