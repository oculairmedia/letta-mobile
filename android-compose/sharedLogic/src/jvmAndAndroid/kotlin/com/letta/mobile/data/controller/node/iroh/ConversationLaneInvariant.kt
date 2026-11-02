package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

private val uniqueLaneTags = setOf("lane:inbox", "lane:home")
private const val LANE_PAGE_SIZE = 500

internal fun laneTags(body: JsonObject): Set<String> {
    val value = body["tags"]?.takeUnless { it is JsonNull } ?: return emptySet()
    val tags = value as? JsonArray ?: adminError("invalid_tags: expected an array of strings")
    return tags.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content
        ?: adminError("invalid_tags: expected an array of strings") }.toSet().intersect(uniqueLaneTags)
}

private fun laneOwnerInRows(rows: JsonArray, tags: Set<String>, target: Pair<String, String?>): String? {
    for (element in rows) {
        laneOwnerInRow(element, tags, target)?.let { return it }
    }
    return null
}

private fun laneOwnerInRow(element: kotlinx.serialization.json.JsonElement, tags: Set<String>, target: Pair<String, String?>): String? {
    val row = element as? JsonObject ?: adminError("lane_check_invalid_row")
    val id = requiredLaneField(row, "id")
    val owner = requiredLaneField(row, "agent_id")
    val (agent, exceptId) = target
    if (owner != agent || id == exceptId) return null
    if (id == "conv-default-$agent" && exceptId == "default") return null
    return if (laneTags(row).any { it in tags }) id else null
}

private fun requiredLaneField(row: JsonObject, field: String): String =
    (row[field] as? JsonPrimitive)?.contentOrNull ?: adminError("lane_check_invalid_row")

internal suspend fun laneOwner(
    client: AppServerClient,
    store: LocalBackendAdminStore?,
    tags: Set<String>,
    target: Pair<String, String?>,
): String? {
    if (tags.isEmpty()) return null
    val localStore = store ?: adminError("lane_check_unavailable: local backend required")
    val local = withContext(Dispatchers.IO) { localStore.listConversationsProjected(target.first, "all", null, null) }
        ?: adminError("lane_check_unavailable")
    laneOwnerInRows(local, tags, target)?.let { return it }
    return laneOwnerInPages(client, tags, target)
}

private suspend fun laneOwnerInPages(
    client: AppServerClient,
    tags: Set<String>,
    target: Pair<String, String?>,
): String? {
    var after: String? = null
    val seen = mutableSetOf<String>()
    while (true) {
        val rows = lanePage(client, target.first, after)
        laneOwnerInRows(rows, tags, target)?.let { return it }
        if (rows.size < LANE_PAGE_SIZE) return null
        val last = (rows.last() as? JsonObject)?.get("id") as? JsonPrimitive
        after = last?.contentOrNull ?: adminError("lane_check_invalid_row")
        if (!seen.add(after)) adminError("lane_check_pagination_failed")
    }
}

private suspend fun lanePage(client: AppServerClient, agent: String, after: String?): JsonArray {
    val query = buildJsonObject {
        put("agent_id", JsonPrimitive(agent))
        put("limit", JsonPrimitive(LANE_PAGE_SIZE))
        put("include_hidden", JsonPrimitive(true))
        after?.let { put("after", JsonPrimitive(it)) }
    }
    val response = client.conversationList(AppServerCommand.ConversationList(NativeAdmin.requestId(), query))
    if (!response.success || response.conversations == null) adminError("lane_check_unavailable")
    return response.conversations
}

/** Refuses a write that would give [target]'s agent a second owner of a unique lane tag. */
internal suspend fun requireLaneFree(
    client: AppServerClient,
    store: LocalBackendAdminStore?,
    tags: Set<String>,
    target: Pair<String, String?>,
) {
    if (laneOwner(client, store, tags, target) != null) adminError("lane_already_assigned")
}

/** The update form: the owning agent is read from the conversation being tagged. */
internal suspend fun requireLaneFreeForUpdate(
    client: AppServerClient,
    store: LocalBackendAdminStore?,
    conversationId: String,
    tags: Set<String>,
) {
    if (tags.isEmpty()) return
    val current = client.conversationRetrieve(AppServerCommand.ConversationRetrieve(NativeAdmin.requestId(), conversationId))
    val agent = (current.conversation?.get("agent_id") as? JsonPrimitive)?.contentOrNull
    if (!current.success || agent == null) adminError("lane_check_unavailable")
    requireLaneFree(client, store, tags, agent to conversationId)
}
