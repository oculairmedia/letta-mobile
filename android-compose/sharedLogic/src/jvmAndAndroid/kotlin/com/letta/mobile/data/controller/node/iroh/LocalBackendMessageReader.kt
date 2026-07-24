package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * lgns8.9 slice 3: on-disk `message.list` reader.
 *
 * Faithful port of admin-shim `handleConversationMessagesList` (server.ts) +
 * `store.ts:listMessages`/`normalizeMessage`. Handles transcript I/O, sidecar
 * loading, per-conversation caching, pagination, and conversation-id resolution;
 * the 1:N wire fan-out lives in [LocalBackendMessageProjection]. Emits
 * already-projected wire messages so the caller passes the output straight
 * through MessageListPageGuard.bound (the pointer-diet / page-size layer runs on
 * wire messages regardless of source, exactly as it does on the shim proxy
 * response). Split out of the former monolithic `LocalBackendAdminStore` as pure
 * code motion — no behavior change.
 *
 * Deliberate divergences from the live shim, each safe for a cold on-disk reader:
 *   - `after` is accepted but NOT applied: the shim's /messages route reads only
 *     limit/before/order (parsePagination + before + order); `after` never
 *     reaches store.listMessages. We mirror that exactly.
 *   - in-flight run filtering (inFlightMessageIds) is skipped: it drops messages
 *     owned by an ACTIVE run in the shim's process. A client reading the disk
 *     store has no active runs, so the set is always empty — omitting it cannot
 *     change output for a settled transcript.
 */
internal class LocalBackendMessageReader(private val support: LocalBackendStoreSupport) {

    private val projection = LocalBackendMessageProjection(support)

    /**
     * Port of `GET /v1/conversations/{id}/messages`. Resolves the external
     * conv id, reads + normalizes messages.jsonl, applies before/limit/order
     * exactly as the shim route does, then fans each LocalMessage out via
     * [LocalBackendMessageProjection.localMessageToConversationMessages] with the
     * realTimes/otid/attachment/runId sidecar scope. Returns null on ANY error so
     * the caller falls back to the shim proxy.
     */
    fun listMessagesProjected(
        conversationId: String,
        agentId: String?,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
    ): JsonArray? = runCatching {
        val resolved = resolveConversation(conversationId, agentId) ?: return@runCatching JsonArray(emptyList())
        val (internalConvId, resolvedAgentId) = resolved
        val dir = File(File(support.baseDir, "conversations"), support.b64UrlEncode(support.conversationKey(internalConvId, resolvedAgentId)))
        // Cached parse of the whole transcript + sidecars; pagination/projection
        // below stay per-call on the cached (read-only) list.
        val data = loadMessageData(dir, resolvedAgentId, internalConvId)

        var scoped = data.messages
        if (!before.isNullOrEmpty()) {
            val idx = scoped.indexOfFirst { it["id"]?.stringOrNull() == before }
            if (idx >= 0) scoped = scoped.subList(0, idx)
        }
        if (limit != null && limit > 0 && scoped.size > limit) {
            scoped = scoped.subList(scoped.size - limit, scoped.size)
        }
        val ordered = if ((order ?: "asc").lowercase() == "desc") scoped.asReversed() else scoped

        buildJsonArray {
            ordered.forEach { m ->
                projection.localMessageToConversationMessages(m, data.sidecars).forEach { add(it) }
            }
        }
    }.getOrNull()

    private data class MessageData(
        val messages: List<JsonObject>,
        val sidecars: MessageSidecars,
    )

    private data class CachedMessageData(val signature: String, val data: MessageData)

    private val messageCache = java.util.concurrent.ConcurrentHashMap<String, CachedMessageData>()

    /**
     * lgns8.9: cache the parsed transcript + sidecar maps per conversation, keyed
     * on a composite file signature (messages.jsonl + sidecars + runs dir mtime),
     * so repeated polls on a large transcript (the ~87MB main conversation) don't
     * re-parse the whole file every call — mirrors the shim's messagesCache. An
     * append (new message) bumps messages.jsonl mtime/size -> the signature changes
     * -> the entry is recomputed. Cross-process: this reader isn't the writer, so
     * a plain file-signature check (not writer-invalidation) is the correct
     * staleness gate. Bounded so a many-conversation host can't grow it without limit.
     */
    private fun loadMessageData(dir: File, agentId: String, internalConvId: String): MessageData {
        val sig = messageCacheSignature(dir)
        val key = dir.path
        messageCache[key]?.let { if (it.signature == sig) return it.data }
        val data = MessageData(
            messages = readLocalMessages(File(dir, "messages.jsonl")),
            sidecars = MessageSidecars(
                realTimes = support.readRealTimesMap(dir),
                otid = support.readStringMap(File(dir, "_otid-map.json")),
                attachments = support.readAttachmentMap(File(dir, "_attachments.json")),
                runIds = readRunIdsByMessageId(agentId, internalConvId),
            ),
        )
        if (messageCache.size > MESSAGE_CACHE_MAX) messageCache.clear()
        messageCache[key] = CachedMessageData(sig, data)
        return data
    }

    private fun messageCacheSignature(dir: File): String {
        fun stamp(f: File): String = if (f.isFile) "${f.lastModified()}:${f.length()}" else "-"
        val runs = File(support.baseDir, "runs").let { if (it.isDirectory) it.lastModified().toString() else "-" }
        return listOf(
            stamp(File(dir, "messages.jsonl")),
            stamp(File(dir, "_real-times.json")),
            stamp(File(dir, "_real-times.jsonl")),
            stamp(File(dir, "_otid-map.json")),
            stamp(File(dir, "_attachments.json")),
            runs,
        ).joinToString("|")
    }

    /**
     * Port of `resolveConversationId` + fast-path `getConversation`. Refuses
     * the bare `"default"` literal (ambiguous across agents). For the
     * external `conv-default-<agentId>` form, splits out the agent id. For a
     * real `conv-...` id, the on-disk dir key is `conversation:<id>` so we
     * read conversation.json directly to recover the agent id (no scan). An
     * explicit [agentIdHint] short-circuits the disk read.
     */
    private fun resolveConversation(externalId: String, agentIdHint: String?): Pair<String, String>? {
        if (externalId.isEmpty() || externalId == "default") return null
        val defaultMatch = Regex("^conv-default-(agent-.+)$").find(externalId)
        if (defaultMatch != null) return "default" to defaultMatch.groupValues[1]
        if (agentIdHint != null) return externalId to agentIdHint
        val dir = File(File(support.baseDir, "conversations"), support.b64UrlEncode("conversation:$externalId"))
        val obj = runCatching {
            File(dir, "conversation.json").takeIf { it.isFile }?.readText()?.let { support.json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: return null
        val agentId = obj["agent_id"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        return externalId to agentId
    }

    /**
     * Port of store.ts loadFilteredMessages pipeline (no cache): read the
     * JSONL, unwrap session-log v3 envelopes, map `content`->`parts`, and
     * keep only records that satisfy `isLocalMessage`. Non-message lines
     * (the `{"type":"session",...}` header) are rejected by the filter.
     */
    private fun readLocalMessages(file: File): List<JsonObject> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<JsonObject>()
        file.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEachLine
            val el = runCatching { support.json.parseToJsonElement(t) }.getOrNull() as? JsonObject ?: return@forEachLine
            val norm = normalizeMessage(el) ?: return@forEachLine
            if (isLocalMessage(norm)) out += norm
        }
        return out
    }

    /** Port of store.ts unwrapSessionEnvelope + normalizeMessage (content->parts). */
    private fun normalizeMessage(value: JsonObject): JsonObject? {
        val unwrapped =
            if (value["type"]?.stringOrNull() == "message" && value["message"] is JsonObject) {
                value["message"] as JsonObject
            } else {
                value
            }
        val parts = unwrapped["parts"]
        val content = unwrapped["content"]
        return if (parts !is JsonArray && content is JsonArray) {
            JsonObject(unwrapped.toMutableMap().apply { this["parts"] = content })
        } else {
            unwrapped
        }
    }

    /** Port of store.ts isLocalMessage: id string, role string, parts OR content array. */
    private fun isLocalMessage(m: JsonObject): Boolean {
        if (m["id"]?.stringOrNull() == null) return false
        if (m["role"]?.stringOrNull() == null) return false
        return m["parts"] is JsonArray || m["content"] is JsonArray
    }

    /**
     * Port of runs.ts buildMessageRunMap: walk `<baseDir>/runs/<runId>/run.json`
     * (skipping the `_archive` subdir, matching the live-only walk in
     * listRuns), keep runs matching BOTH agentId and the INTERNAL conversation
     * id, sort by created_at ascending, and map each message id -> run id with
     * the OLDEST run winning (`if (!map[id]) map[id] = run.id`).
     */
    private fun readRunIdsByMessageId(agentId: String, internalConvId: String): Map<String, String> {
        val root = File(support.baseDir, "runs")
        val dirs = root.listFiles { f -> f.isDirectory && f.name != "_archive" } ?: return emptyMap()
        data class RunRec(val id: String, val createdAt: String, val messageIds: List<String>)
        val runs = ArrayList<RunRec>()
        for (d in dirs) {
            val obj = runCatching {
                File(d, "run.json").takeIf { it.isFile }?.readText()?.let { support.json.parseToJsonElement(it).jsonObject }
            }.getOrNull() ?: continue
            if (obj["agent_id"]?.stringOrNull() != agentId) continue
            if (obj["conversation_id"]?.stringOrNull() != internalConvId) continue
            val id = obj["id"]?.stringOrNull() ?: continue
            val mids = (obj["message_ids"] as? JsonArray)?.mapNotNull { it.stringOrNull() } ?: emptyList()
            runs += RunRec(id, obj["created_at"]?.stringOrNull() ?: "", mids)
        }
        runs.sortBy { it.createdAt }
        val map = HashMap<String, String>()
        for (r in runs) for (mid in r.messageIds) if (!map.containsKey(mid)) map[mid] = r.id
        return map
    }

    companion object {
        private const val MESSAGE_CACHE_MAX = 16
    }
}
