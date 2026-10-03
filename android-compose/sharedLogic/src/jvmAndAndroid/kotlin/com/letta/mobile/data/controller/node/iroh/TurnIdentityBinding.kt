package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.SettledTurnCollector
import com.letta.mobile.data.runtime.TurnIdentityLedger

/**
 * One relayed turn's link to the host's [TurnIdentityLedger]: collects the stamped frames the
 * fanout relays, holds `message.list` back once the turn starts ending, and settles the turn's
 * logical messages against the stored rows when it is over.
 */
internal class TurnIdentityBinding(
    private val ledger: TurnIdentityLedger,
    private val conversationId: String,
    clientMessageId: String?,
) {
    private val collector = SettledTurnCollector(clientMessageId)

    fun noteFrame(body: String) = collector.observe(body)

    fun noteUser(clientMessageId: String) = collector.noteUser(clientMessageId)

    suspend fun settle() {
        val turn = collector.settled() ?: return ledger.abandonSettle(conversationId)
        ledger.settleTurn(conversationId, turn)
    }

    fun abandon() = ledger.abandonSettle(conversationId)
}
