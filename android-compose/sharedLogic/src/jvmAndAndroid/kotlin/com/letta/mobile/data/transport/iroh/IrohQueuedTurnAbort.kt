package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.TurnRuntimeKey
import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-qygvv.9: the keyed abort behind a user cancel, skipped while [turn]'s input still
 * waits in the App Server queue. Such a turn has no run of its own, so an `abort_message` would stop
 * the turn ahead of it (another viewer's) and pause the queue. The cancel's synthetic terminal still
 * ends the turn, and ending its job releases the lease, which takes the input off the server queue.
 *
 * A still-synthetic run id means the real run id has not streamed yet: the abort then passes null
 * so the server aborts whatever run is active for the runtime.
 */
internal suspend fun AppServerTurnEngine.abortUnlessQueued(turn: IrohActiveTurn) {
    if (isQueued(TurnRuntimeKey(turn.agentId, turn.conversationId))) {
        Telemetry.event("IrohTransport", "cancel.queued_turn", "conversationId" to turn.conversationId, "turnId" to turn.turnId)
        return
    }
    abort(
        agentId = turn.agentId,
        conversationId = turn.conversationId,
        runId = turn.runId.takeUnless { it.isIrohSyntheticRunId() },
    )
}
