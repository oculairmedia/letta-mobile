package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry

/** The turn/run a stop-reason or usage tail frame belongs to. */
internal data class TurnTail(val turnId: String?, val runId: String?)

/**
 * A stop/usage tail for a turn the coordinator already RETIRED (its TurnDone won
 * the race — e.g. the Iroh observer fallback claimed the terminal before the
 * engine's own settle flushed). It must still reach the timeline projection so
 * the run folds with its stop reason and usage, but it never touches live turn
 * state.
 */
internal fun forwardRetiredTurnTail(
    batcher: RuntimeEventBatcher,
    event: WsTimelineEvent,
    tail: TurnTail,
    conversationId: String?,
) {
    batcher.enqueue(event, conversationId)
    Telemetry.event(
        "AdminChatVM", "ws.event.retiredTurnTailForwarded",
        "eventType" to (event::class.simpleName ?: ""),
        "turnId" to (tail.turnId ?: ""),
        "runId" to (tail.runId ?: ""),
        "conversationId" to (conversationId ?: ""),
    )
}
