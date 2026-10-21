package com.letta.mobile.data.chat.send

import com.letta.mobile.util.Telemetry

/** Local ownership retirement under the coordinator mutex, before releasing its pending queue.
 * Lock order remains lifecycle mutex -> registry index -> identity. No scope or copied index.
 */
internal class TurnSettlementOwnership(
    private val registry: ConversationTurnRegistry,
    private val queuedSends: ChatSendQueue,
) {
    fun retainSettledOtid(otid: QueuedSendId) = registry.retainSettledOtid(otid)

    /**
     * Clears ONE conversation's turn state. Callers must pass the entry they own —
     * the old unkeyed version wiped every conversation's identity, which is how
     * conversation A's terminal froze conversation B's in-flight send.
     */
    fun clear(state: ConversationTurnState, reason: String) {
        // SENSING (letta-mobile-or40x): an entry dropped before its terminal.
        if (state.isTracking && !state.reachedTerminal) {
            Telemetry.event(
                "AdminChatVM", "ws.turnState.clearedBeforeTerminal",
                "conversationId" to state.conversationId,
                "reason" to reason,
                "turnId" to (state.turnId ?: ""),
                "runId" to (state.runId ?: ""),
                "otid" to (state.otid ?: ""),
            )
        }
        state.retainSettledRun()
        state.otid?.let { retainSettledOtid(QueuedSendId(it)) }
        state.otid = null
        state.inFlightSend?.let { queuedSends.clearQueuedOnServer(it.conversationId) }
        state.inFlightSend = null
        state.identity.clear()
        state.localConversationId = null
        state.serverConversationId = null
        state.turnId = null
        state.runId = null
        state.resetDeliveryEvidence()
    }
}
