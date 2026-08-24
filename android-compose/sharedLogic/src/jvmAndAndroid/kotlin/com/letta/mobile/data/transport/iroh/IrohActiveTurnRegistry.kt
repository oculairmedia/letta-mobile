package com.letta.mobile.data.transport.iroh

import java.util.concurrent.ConcurrentHashMap

/**
 * Atomically retains an in-flight turn for [conversationId] or installs [newTurn]
 * after a prior terminal has completed.
 */
internal fun ConcurrentHashMap<String, IrohChannelTransport.ActiveTurn>.registerUnlessInFlight(
    conversationId: String,
    newTurn: IrohChannelTransport.ActiveTurn,
): IrohChannelTransport.ActiveTurn? {
    var collision: IrohChannelTransport.ActiveTurn? = null
    compute(conversationId) { _, existing ->
        if (existing != null && !existing.terminalReached.isCompleted) {
            collision = existing
            existing
        } else {
            newTurn
        }
    }
    return collision
}
