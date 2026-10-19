package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The App Server's `listLocalConversations` drops rows
 * whose CONVERSATION is hidden, but not rows whose owning AGENT is hidden, so
 * every subagent / reflection run that kept a non-hidden conversation (62 on
 * the live host) reached the chat list. The client cannot filter them: it
 * never learns hidden agents' ids (agent.list omits them). The host can.
 *
 * Same rule as letta-code's `isHiddenLocalAgentRecord`: `hidden: true`, or
 * `hidden` unset and the `role:subagent` tag present.
 */
internal fun JsonObject.isHiddenAgentRecord(): Boolean {
    val hidden = (this["hidden"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    if (hidden != null) return hidden
    val tags = (this["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    return SUBAGENT_TAG in tags
}

private const val SUBAGENT_TAG = "role:subagent"

/** [rows] without the conversations owned by an agent in [hiddenAgentIds]. */
internal fun withoutHiddenAgentConversations(rows: List<JsonElement>, hiddenAgentIds: Set<String>): List<JsonElement> {
    if (hiddenAgentIds.isEmpty()) return rows
    return rows.filterNot { row ->
        val agentId = ((row as? JsonObject)?.get("agent_id") as? JsonPrimitive)?.contentOrNull
        agentId != null && agentId in hiddenAgentIds
    }
}

/**
 * The hidden-agent id set, read from the local backend store and cached for
 * [ttlMs] so a chat-list refresh does not rescan every agent record. A read
 * failure keeps the last good set (or none): it never hides more than the
 * store says and never fails the list.
 */
internal class HiddenAgentIndex(
    private val load: () -> Set<String>?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    @Volatile private var cached: Set<String> = emptySet()

    @Volatile private var loadedAtMs: Long? = null

    fun hiddenAgentIds(): Set<String> {
        val now = clock()
        val loadedAt = loadedAtMs
        if (loadedAt != null && now - loadedAt < ttlMs) return cached
        load()?.let { cached = it }
        loadedAtMs = now
        return cached
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 30_000L
    }
}
