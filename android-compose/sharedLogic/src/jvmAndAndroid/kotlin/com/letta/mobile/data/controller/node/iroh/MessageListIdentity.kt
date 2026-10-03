package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.TurnIdentityLedger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Adds the turn identity ledger's `logical_message_id` / `turn_id` to a projected `message.list` page. */
internal object MessageListIdentity {
    /**
     * [page] with each row the ledger knows carrying both ids, e.g.
     * `{"id":"ui-msg-9184506","message_type":"assistant_message","logical_message_id":"ui-msg-9184506","turn_id":"capture-turn1-3ed6..."}`.
     * Anything that is not a page of row objects, and a missing [ledger], pass through unchanged.
     */
    suspend fun enrich(ledger: TurnIdentityLedger?, conversationId: String, page: JsonElement): JsonElement {
        val rows = (page as? JsonArray)?.takeIf { it.all { row -> row is JsonObject } } ?: return page
        ledger ?: return page
        return JsonArray(ledger.enrich(conversationId, rows.map { it as JsonObject }))
    }
}
