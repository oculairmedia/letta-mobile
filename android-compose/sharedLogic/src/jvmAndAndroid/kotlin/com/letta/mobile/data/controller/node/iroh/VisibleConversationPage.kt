package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationRecency
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Where a visible page starts and how many rows it takes, as the request named them. */
internal class VisiblePageRequest(val after: ConversationId?, val limit: String?)

/** Visible recents are filtered and scoped before cursor/limit, independently of legacy pages. */
internal fun visibleConversationPage(
    full: JsonArray,
    hiddenAgents: Set<String>,
    context: AdminRpcRequestContext,
    request: VisiblePageRequest,
): JsonArray = pageOf(visibleRows(full, hiddenAgents, context), request)

/** Rows a caller may see in recents, newest first. */
private fun visibleRows(full: JsonArray, hiddenAgents: Set<String>, context: AdminRpcRequestContext): List<JsonObject> =
    full.filterIsInstance<JsonObject>()
        .filter { row -> row.isListedFor(hiddenAgents) && row.isInScope(context) }
        .sortedWith(
            ConversationRecency.comparator(
                id = { it.text("id").orEmpty() },
                lastMessageAt = { it.text("last_message_at") },
                updatedAt = { it.text("updated_at") },
                createdAt = { it.text("created_at") },
            ),
        )

/** Owned by a visible agent, not hidden and not archived. */
private fun JsonObject.isListedFor(hiddenAgents: Set<String>): Boolean {
    val agent = text("agent_id") ?: return false
    return agent !in hiddenAgents && !isHiddenConversation() && (this["archived"] as? JsonPrimitive)?.booleanOrNull != true
}

/** Within the caller's agent and conversation scopes (an absent scope admits everything). */
private fun JsonObject.isInScope(context: AdminRpcRequestContext): Boolean =
    context.authorizedAgentIds?.contains(text("agent_id")) != false &&
        context.authorizedConversationIds?.contains(text("id")) != false

/** Rows after the cursor (from the top when it is unknown), cut to the limit with App Server semantics. */
private fun pageOf(rows: List<JsonObject>, request: VisiblePageRequest): JsonArray {
    val cursor = request.after?.let { after -> rows.indexOfFirst { it.text("id") == after.value } } ?: -1
    val remaining = if (cursor >= 0) rows.drop(cursor + 1) else rows
    val count = request.limit?.toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
        ?: VISIBLE_PAGE_DEFAULT
    val kept = if (count >= 0) count else (remaining.size.toLong() + count).coerceAtLeast(0).toInt()
    return JsonArray(remaining.take(kept))
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private const val VISIBLE_PAGE_DEFAULT = 30

/** Opt-in only; agent slices and summary searches retain their original contract. */
internal fun wantsVisibleConversationPage(params: JsonObject?): Boolean =
    (params?.get("visible_only") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true &&
        param(params, AdminParamKey("agent_id")) == null && param(params, AdminParamKey("summary_search")) == null
