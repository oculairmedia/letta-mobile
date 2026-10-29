package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One agent's `conversation_list` page cut from the App Server's full list, by the App Server's own
 * rules (letta-code 0.34.14 `listLocalConversations`): the full list is already filtered (no
 * `default`, no hidden rows) and sorted newest first, so this keeps [agent]'s rows in that order,
 * starts after the row with id [after] (from the top when no row has it), and takes [limit] rows,
 * 20 when the request gave no whole number. A negative limit drops that many from the end, as JavaScript's
 * `slice(0, n)` does. `archive_status`, `order` and `order_by` are ignored there and so here.
 *
 * One difference: an agent-free row (`agent_id: null` on the wire) is never in an agent's page here,
 * where the App Server matches it by its hidden execution agent. Clients drop those rows anyway.
 */
internal fun agentConversationPage(full: JsonArray, agent: AgentId, after: ConversationId?, limit: String?): JsonArray {
    var rows = full.filter { row -> row.field("agent_id") == agent.value }
    if (after != null) {
        val index = rows.indexOfFirst { row -> row.field("id") == after.value }
        if (index >= 0) rows = rows.drop(index + 1)
    }
    // queryOf sends a limit that parses as a Long as a number, anything else as a string (then 20).
    val count = limit?.toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
        ?: DEFAULT_APP_SERVER_PAGE
    return JsonArray(
        if (count >= 0) rows.take(count)
        else rows.take((rows.size.toLong() + count).coerceAtLeast(0L).toInt()),
    )
}

private fun kotlinx.serialization.json.JsonElement.field(name: String): String? =
    ((this as? JsonObject)?.get(name))?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

/** The App Server's page size when a list request names none (`typeof limit === "number" ? limit : 20`). */
private const val DEFAULT_APP_SERVER_PAGE = 20
