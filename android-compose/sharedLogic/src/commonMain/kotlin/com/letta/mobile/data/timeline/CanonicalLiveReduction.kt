package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage

/** One streamed frame folded into a live turn's resident reduction. */
internal fun TimelineReducerState.reduceLive(message: LettaMessage, agentId: String?): TimelineReducerState {
    val output = reduceStreamFrame(TimelineReducerInput(timeline, message, pendingToolReturnsByCallId, agentId = agentId))
    return copy(timeline = output.next, pendingToolReturnsByCallId = output.updatedPendingToolReturnsByCallId)
}

/** The tool call a return frame answers, when it names one. */
internal fun LettaMessage.returnedCallId(): String? =
    (this as? ToolReturnMessage)?.toolReturn?.toolCallId?.takeIf { it.isNotBlank() }

/** Removes the first element matching [predicate]; true when there was one. */
internal inline fun <T> MutableList<T>.removeFirstMatching(predicate: (T) -> Boolean): Boolean {
    val index = indexOfFirst(predicate)
    if (index < 0) return false
    removeAt(index)
    return true
}
