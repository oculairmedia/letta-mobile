package com.letta.mobile.data.runtime

import kotlinx.serialization.json.JsonObject

/**
 * Assigns identities to rows stored before the ledger existed (plan 1.4, "prompt adjacency"): a
 * user row opens a turn and every row after it, until the next user row, belongs to that turn.
 * A user row keeps its `otid` as its logical id so a later live echo of it lines up; every other
 * row is identified as `<id>:<MESSAGE_TYPE>`, the same id a client derives for an unidentified row.
 * Pure and deterministic, so running it again over the same rows yields the same map.
 */
internal object HistoryTurnAssigner {
    /** The id a client derives for a row with no identity, `<id>:<MESSAGE_TYPE>`. */
    fun provisionalId(ref: StoredRowRef): String = "${ref.durableId}:${ref.messageType}"

    fun assign(orderedRows: List<JsonObject>, skip: Set<StoredRowRef>): Map<StoredRowRef, RowIdentity> {
        val result = LinkedHashMap<StoredRowRef, RowIdentity>()
        var turnId = ""
        for (row in orderedRows) {
            val ref = row.ref() ?: continue
            val otid = row.stringField("otid").takeIf { ref.messageType == "user_message" }
            turnId = nextTurnId(turnId, ref, otid)
            if (ref !in skip) result[ref] = RowIdentity(otid ?: provisionalId(ref), turnId)
        }
        return result
    }

    /** A user row, or the first row of all, opens a turn; any other row stays in the current one. */
    private fun nextTurnId(current: String, ref: StoredRowRef, otid: String?): String =
        if (ref.messageType == "user_message" || current.isEmpty()) otid ?: "turn-${ref.durableId}" else current
}
