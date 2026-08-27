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
    state.value = enrichTimelineFromSnapshot(state.value, snapshot)
}
