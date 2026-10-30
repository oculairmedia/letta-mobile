package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationRecency
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Visible recents are filtered and scoped before cursor/limit, independently of legacy pages. */
internal fun visibleConversationPage(
    full: JsonArray,
    hiddenAgents: Set<String>,
    context: AdminRpcRequestContext,
    after: ConversationId?,
    limit: String?,
): JsonArray {
    var rows = full.filterIsInstance<JsonObject>().filter { row ->
        val agent = row.text("agent_id") ?: return@filter false
        agent !in hiddenAgents && !row.isHiddenConversation() &&
            (row["archived"] as? JsonPrimitive)?.booleanOrNull != true &&
            (context.authorizedAgentIds?.contains(agent) != false) &&
            (context.authorizedConversationIds?.contains(row.text("id")) != false)
    }.sortedWith(ConversationRecency.comparator(
        id = { it.text("id").orEmpty() },
        lastMessageAt = { it.text("last_message_at") },
        updatedAt = { it.text("updated_at") },
        createdAt = { it.text("created_at") },
    ))
    if (after != null) {
        val index = rows.indexOfFirst { it.text("id") == after.value }
        if (index >= 0) rows = rows.drop(index + 1)
    }
    val count = limit?.toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
        ?: VISIBLE_PAGE_DEFAULT
    return JsonArray(if (count >= 0) rows.take(count) else rows.take((rows.size.toLong() + count).coerceAtLeast(0).toInt()))
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private const val VISIBLE_PAGE_DEFAULT = 30

/** Opt-in only; agent slices and summary searches retain their original contract. */
internal fun wantsVisibleConversationPage(params: JsonObject?): Boolean =
    (params?.get("visible_only") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true &&
        param(params, AdminParamKey("agent_id")) == null && param(params, AdminParamKey("summary_search")) == null
