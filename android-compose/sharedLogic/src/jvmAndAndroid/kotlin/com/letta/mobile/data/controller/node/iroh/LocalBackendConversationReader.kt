package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * lgns8.9: on-disk `conversation.list` reader. Faithful port of admin-shim
 * `GET /v1/conversations` (handleConversationsList) + `store.ts` scans +
 * `withRealTimes` overlay + `translate.ts:conversationToLetta`. Split out of the
 * former monolithic `LocalBackendAdminStore` as pure code motion — no behavior change.
 */
internal class LocalBackendConversationReader(private val support: LocalBackendStoreSupport) {

    /**
     * Port of admin-shim `GET /v1/conversations` (handleConversationsList):
     * read the conversation records (agent-scoped or all), apply the withRealTimes
     * ordering overlay, filter by archive status, sort by last_message_at desc, page,
     * and project each via conversationToLetta. Returns null on any error so the
     * caller falls back to the shim proxy.
     *
     * NOTE: no cache here — correctness first. Callers must gate live use on the
     * measured latency over the real store (~1.4k conversations); a bounded TTL
     * cache is the follow-up if the uncached scan is too slow to serve on poll.
     */
    fun listConversationsProjected(
        agentId: String?,
        archiveStatus: String?,
        limit: Int?,
        offset: Int?,
    ): JsonArray? = runCatching {
        val status = archiveStatus?.takeIf { it in ARCHIVE_STATUSES } ?: "active"
        val convs = readConversations(agentId)
            .map { withRealTimes(it) }
            .filter { c ->
                val archived = c.archived == true
                when (status) {
                    "archived" -> archived
                    "all" -> true
                    else -> !archived
                }
            }
            .sortedWith(compareByDescending { it.lastMessageAt ?: "" })
        val from = (offset ?: 0).coerceAtLeast(0)
        val windowed = if (from >= convs.size) {
            emptyList()
        } else {
            val end = if (limit != null) (from + limit.coerceAtLeast(0)).coerceAtMost(convs.size) else convs.size
            convs.subList(from, end)
        }
        buildJsonArray { windowed.forEach { add(conversationToLetta(it)) } }
    }.getOrNull()

    private data class ConvRecord(
        val id: String,
        val agentId: String,
        val createdAt: String?,
        val updatedAt: String?,
        val lastMessageAt: String?,
        val summary: String?,
        val archived: Boolean?,
        val archivedAt: String?,
        val inContextMessageIds: JsonArray,
        val raw: JsonObject,
    )

    /**
     * Port of store.ts listConversationsForAgent / listAllConversations. Scans
     * conversations/<b64url(key)>/conversation.json; when agentId is given, keep
     * only that agent's records (key `default:<agentId>` or any `conversation:`
     * whose agent_id matches).
     */
    private fun readConversations(agentId: String?): List<ConvRecord> {
        val root = File(support.baseDir, "conversations")
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { parseConversationRecord(it, agentId) }
    }

    /**
     * True when the decoded dir key cannot belong to [agentId]: the shim keeps
     * `default:<agentId>` or any `conversation:` key (agent match verified later
     * from conversation.json), so anything else is pruned before the file read.
     */
    private fun keyExcludedForAgent(key: String, agentId: String): Boolean =
        key != "default:$agentId" && !key.startsWith("conversation:")

    /** Parse one conversations/<b64url(key)>/ dir into a ConvRecord, or null to skip it. */
    private fun parseConversationRecord(d: File, agentId: String?): ConvRecord? {
        val key = runCatching { support.b64UrlDecode(d.name) }.getOrNull() ?: return null
        if (agentId != null && keyExcludedForAgent(key, agentId)) return null
        val obj = runCatching {
            File(d, "conversation.json").takeIf { it.isFile }?.readText()?.let { support.json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: return null
        val convId = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val convAgent = obj["agent_id"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        if (agentId != null && convAgent != agentId) return null
        return ConvRecord(
            id = convId,
            agentId = convAgent,
            createdAt = obj["created_at"]?.stringOrNull(),
            updatedAt = obj["updated_at"]?.stringOrNull(),
            lastMessageAt = obj["last_message_at"]?.stringOrNull(),
            summary = obj["summary"]?.stringOrNull(),
            archived = (obj["archived"] as? JsonPrimitive)?.let { if (it.isString) null else it.content.toBooleanStrictOrNull() },
            archivedAt = obj["archived_at"]?.stringOrNull(),
            inContextMessageIds = obj["in_context_message_ids"] as? JsonArray ?: JsonArray(emptyList()),
            raw = obj,
        )
    }

    /**
     * Port of store.ts withRealTimes: overlay the sidecar max message time. If the
     * on-disk last/updated are CLI sentinels (2026-01-01T...), substitute
     * max -> created_at -> (skipped: current time, unavailable deterministically);
     * bump last/updated to the sidecar max when it is greater.
     */
    private fun withRealTimes(c: ConvRecord): ConvRecord {
        val max = support.maxRealMessageTime(c.id, c.agentId)
        val created = c.createdAt ?: ""
        val last = resolveRealTime(c.lastMessageAt ?: "", max, created)
        val updated = resolveRealTime(c.updatedAt ?: "", max, created)
        return c.copy(lastMessageAt = last.ifEmpty { c.lastMessageAt }, updatedAt = updated.ifEmpty { c.updatedAt })
    }

    /**
     * Resolve one CLI timestamp against the sidecar max: substitute a sentinel
     * date with (max, else created); then bump to max when max is greater.
     */
    private fun resolveRealTime(current: String, sidecarMax: String, created: String): String {
        var v = current
        if (support.isSentinelDate(v)) v = sidecarMax.ifEmpty { created }
        if (sidecarMax.isNotEmpty() && sidecarMax > v) v = sidecarMax
        return v
    }

    private fun conversationToLetta(c: ConvRecord): JsonObject = buildJsonObject {
        put("id", if (c.id == "default") "conv-default-${c.agentId}" else c.id)
        put("agent_id", c.agentId)
        put("created_at", c.createdAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("updated_at", c.updatedAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("last_message_at", c.lastMessageAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("created_by_id", CANNED_USER_ID)
        put("last_updated_by_id", CANNED_USER_ID)
        put("summary", c.summary?.let { JsonPrimitive(it) } ?: JsonNull)
        put("archived", c.archived?.let { JsonPrimitive(it) } ?: JsonNull)
        put("archived_at", c.archivedAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("in_context_message_ids", c.inContextMessageIds)
        put("isolated_block_ids", JsonArray(emptyList()))
        put("model", JsonNull)
        put("model_settings", JsonNull)
    }

    companion object {
        private val ARCHIVE_STATUSES = setOf("active", "archived", "all")
        // admin-shim translate.ts: canned user uuid for created_by/last_updated_by.
        private val CANNED_USER_ID = JsonPrimitive("user-00000000-0000-4000-8000-000000000000")
    }
}
