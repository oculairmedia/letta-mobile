package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.TimelineSyncLoop

/**
 * Callbacks for one outbound response stream. Completion releases dual-ingest suppression before
 * ending the turn, including when collection fails or is cancelled.
 */
internal class TimelineOutboundStreamCallbacks(
    val ingestStreamEvent: suspend (LettaMessage) -> Unit,
    /**
     * Invoked when an outbound send's response stream ends
     * (terminal or error). Each [ingestStreamEvent] call latches the loop's
     * external-transport flag (dual-ingest suppression: while OUR turn streams
     * through the send flow, the persistent stream subscriber must not ingest
     * the same frames twice). That latch must be RELEASED when the turn ends —
     * otherwise every later subscriber-delivered frame (e.g. another client's
     * fanned-out turn observed over Iroh) is dropped as `skippedDualIngest`
     * forever, and the desktop renders the prompt but never the reply.
     * Mirrors mobile's ChatSendCoordinator, which clears the latch at turn end.
     */
    val onSendStreamEnded: suspend () -> Unit = {},
    /**
     * The REST/timeline-
     * transport send path (this class) previously never signaled the
     * dangling-tool-call resolver's turn lifecycle — only the WS/iroh
     * ChatSendCoordinator path did, via TimelineRepository.turnStarted /
     * turnEnded. That left a tool_call streamed through THIS path with no
     * eventual return spinning forever, since no sweep was ever scheduled.
     * Called when the outbound send stream begins; mirrors
     * [TimelineSyncLoop.turnStarted].
     */
    val onTurnStarted: suspend () -> Unit = {},
    /**
     * Called when the outbound send stream ends, with [clean] reflecting
     * whether it completed without throwing. Mirrors
     * [TimelineSyncLoop.turnEnded].
     */
    val onTurnEnded: suspend (clean: Boolean) -> Unit = {},
)
