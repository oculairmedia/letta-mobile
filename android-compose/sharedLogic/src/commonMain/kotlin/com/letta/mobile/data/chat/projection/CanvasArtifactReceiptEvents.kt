package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType

/**
 * The narrating message for the compose call at [index] of [events] (see [CanvasArtifactReceipts]),
 * or [index] itself. Scans only the call's own run (or turn), so the cost is bounded by one turn.
 */
internal class ReceiptNarration(private val events: List<TimelineEvent>, private val index: Int) {
    private val runId = events[index].runIdOrNull()
    private val stepId = events[index].stepIdOrNull()

    fun target(): Int = firstAfter() ?: lastBeforeInStep() ?: index

    /** The first narration after the call in its run, one in the call's step first. */
    private fun firstAfter(): Int? {
        var first: Int? = null
        for (j in turnAfter()) {
            if (!isNarrationInScope(events[j])) continue
            if (inStep(events[j])) return j
            if (first == null) first = j
        }
        return first
    }

    /** The last narration before the call in its step. */
    private fun lastBeforeInStep(): Int? {
        if (stepId == null) return null
        return turnBefore().firstOrNull { isNarrationInScope(events[it]) && inStep(events[it]) }
    }

    /** The events after the call, up to the next user message. */
    private fun turnAfter(): Sequence<Int> = (index + 1 until events.size).asSequence().takeWhile { !events[it].isUserTurn() }

    /** The events before the call, nearest first, back to the user message that opened its turn. */
    private fun turnBefore(): Sequence<Int> = (index - 1 downTo 0).asSequence().takeWhile { !events[it].isUserTurn() }

    private fun isNarrationInScope(event: TimelineEvent): Boolean =
        (runId == null || event.runIdOrNull() == runId) && event.isNarration()

    private fun inStep(event: TimelineEvent): Boolean = stepId != null && event.stepIdOrNull() == stepId
}

internal fun TimelineEvent.hasComposeCall(): Boolean =
    messageTypeOf() == TimelineMessageType.TOOL_CALL && toolCallList().any(CanvasArtifactReceipts::isComposeCall)

internal fun TimelineEvent.toolCallList(): List<ToolCall> = when (this) {
    is TimelineEvent.Confirmed -> toolCalls
    is TimelineEvent.Local -> toolCalls
}

/** The return of the call [callId] (or, for the event's first call, the event's own return). */
internal fun TimelineEvent.returnText(callId: String?, ordinal: Int): String? =
    callId?.let { returnByCallId()[it] } ?: returnContent().takeIf { ordinal == 0 }

internal fun TimelineEvent.returnIsError(callId: String?): Boolean =
    callId?.let { returnIsErrorByCallId()[it] } ?: eventReturnIsError()

/** Whether the stored return of [callId] is a truncated preview. */
internal fun TimelineEvent.returnTruncated(callId: String?): Boolean =
    callId != null && this is TimelineEvent.Confirmed && callId in toolReturnTruncationByCallId

private fun TimelineEvent.messageTypeOf(): TimelineMessageType = when (this) {
    is TimelineEvent.Confirmed -> messageType
    is TimelineEvent.Local -> messageType
}

private fun TimelineEvent.returnByCallId(): Map<String, String> = when (this) {
    is TimelineEvent.Confirmed -> toolReturnContentByCallId
    is TimelineEvent.Local -> toolReturnContentByCallId
}

private fun TimelineEvent.returnIsErrorByCallId(): Map<String, Boolean> = when (this) {
    is TimelineEvent.Confirmed -> toolReturnIsErrorByCallId
    is TimelineEvent.Local -> toolReturnIsErrorByCallId
}

private fun TimelineEvent.returnContent(): String? = when (this) {
    is TimelineEvent.Confirmed -> toolReturnContent
    is TimelineEvent.Local -> toolReturnContent
}

private fun TimelineEvent.eventReturnIsError(): Boolean = when (this) {
    is TimelineEvent.Confirmed -> toolReturnIsError
    is TimelineEvent.Local -> toolReturnIsError
}

private fun TimelineEvent.runIdOrNull(): String? = (this as? TimelineEvent.Confirmed)?.runId?.takeIf { it.isNotBlank() }

private fun TimelineEvent.stepIdOrNull(): String? = (this as? TimelineEvent.Confirmed)?.stepId?.takeIf { it.isNotBlank() }

private fun TimelineEvent.isUserTurn(): Boolean = messageTypeOf() == TimelineMessageType.USER

private fun TimelineEvent.isNarration(): Boolean = messageTypeOf() == TimelineMessageType.ASSISTANT && content.isNotBlank()
