package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Pagination shape for [LocalBackendMessageReader.listMessagesProjected] (mirrors the shim's message.list query). */
data class MessagePage(val limit: Int?, val before: String?, val after: String?, val order: String?)

/** Transcript inputs for the `agent.context` projection — see [LocalBackendMessageReader.contextTranscript]. */
internal data class ContextTranscript(
    val conversationDir: File,
    val storedMessageCount: Int,
    val projected: JsonArray,
)

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
internal class LocalBackendMessageReader(
    private val support: LocalBackendStoreSupport,
    // Bounded-input guard (audit P3.3 / gn7kr.22): a pathological transcript must
    // degrade gracefully instead of OOMing the whole reader. The caps sit far above
    // any real transcript (the largest known on-disk conversation is ~87 MB), so an
    // in-budget transcript parses to a byte-identical projection — parity with the
    // admin-shim is preserved. Injectable only so tests can trip the cap cheaply.
    private val maxTranscriptBytes: Long = MAX_TRANSCRIPT_BYTES,
    private val maxTranscriptMessages: Int = MAX_TRANSCRIPT_MESSAGES,
) {

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
        page: MessagePage,
    ): JsonArray? = runCatching {
        val resolved = resolveConversation(conversationId, agentId) ?: return@runCatching JsonArray(emptyList())
        val (internalConvId, resolvedAgentId) = resolved
        val dir = File(File(support.baseDir, "conversations"), support.b64UrlEncode(support.conversationKey(internalConvId, resolvedAgentId)))
        // Cached parse of the whole transcript + sidecars; pagination/projection
        // stay per-call on the cached (read-only) list.
        val data = loadMessageData(dir, resolvedAgentId, internalConvId)
        buildJsonArray {
            paginate(data.messages, page).forEach { m ->
                projection.localMessageToConversationMessages(m, data.sidecars).forEach { add(it) }
            }
        }
    }.getOrNull()

    /**
     * lgns8.9: the transcript inputs `agent.context` needs — the RAW stored
     * message count (admin-shim's `messages.length`, which drives every token
     * estimate in the context response) plus the SAME wire fan-out `message.list`
     * serves. Kept here so the context reader reuses this reader's cache,
     * sidecar loading, and conversation resolution rather than re-implementing
     * them. `null` when the conversation cannot be resolved.
     */
    fun contextTranscript(conversationId: String?, agentId: String): ContextTranscript? = runCatching {
        // admin-shim: `url.searchParams.get("conversation_id") ?? "default"`, and
        // the bare literal "default" resolves to (default, <agentId>).
        val requested = conversationId?.takeIf { it.isNotEmpty() } ?: "default"
        val (internalConvId, resolvedAgentId) = if (requested == "default") {
            "default" to agentId
        } else {
            resolveConversation(requested, agentId) ?: ("default" to agentId)
        }
        val dir = File(
            File(support.baseDir, "conversations"),
            support.b64UrlEncode(support.conversationKey(internalConvId, resolvedAgentId)),
        )
        val data = loadMessageData(dir, resolvedAgentId, internalConvId)
        ContextTranscript(
            conversationDir = dir,
            storedMessageCount = data.messages.size,
            projected = buildJsonArray {
                data.messages.forEach { m ->
                    projection.localMessageToConversationMessages(m, data.sidecars).forEach { add(it) }
                }
            },
        )
    }.getOrNull()

    /** Apply `before` cursor, newest-`limit` window, then `order` — the shim's message.list paging. */
    private fun paginate(messages: List<JsonObject>, page: MessagePage): List<JsonObject> {
        var scoped = messages
        if (!page.before.isNullOrEmpty()) {
            val idx = scoped.indexOfFirst { it["id"]?.stringOrNull() == page.before }
            if (idx >= 0) scoped = scoped.subList(0, idx)
        }
        val limit = page.limit
        if (limit != null && limit > 0 && scoped.size > limit) {
            scoped = scoped.subList(scoped.size - limit, scoped.size)
        }
        return if ((page.order ?: "asc").lowercase() == "desc") scoped.asReversed() else scoped
    }

    private data class MessageData(
        val messages: List<JsonObject>,
        val sidecars: MessageSidecars,
    )

    private data class CachedMessageData(val signature: String, val data: MessageData)

    // P3.2: access-ordered LRU (evict the single least-recently-used entry on
    // overflow) instead of clearing the whole map, which caused a thundering-herd
    // re-parse of every hot conversation on the (MAX+1)th distinct conversation.
    private val messageCache: MutableMap<String, CachedMessageData> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, CachedMessageData>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, CachedMessageData>): Boolean =
                    size > MESSAGE_CACHE_MAX
            },
        )

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
        // LRU eviction is handled by removeEldestEntry on insert.
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
     *
     * Bounded (audit P3.3): reading stops once the scanned input exceeds
     * [maxTranscriptBytes] or the kept-message count reaches
     * [maxTranscriptMessages], emitting a single WARN. Both caps sit far above
     * any real transcript, so an in-budget file is read to completion exactly as
     * before — the projection stays byte-identical to the shim. A pathological
     * transcript is truncated (a bounded prefix) instead of exhausting the heap.
     */
    internal fun readLocalMessages(file: File): List<JsonObject> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<JsonObject>()
        var scannedBytes = 0L
        var capTripped = false
        file.bufferedReader().use { reader ->
            for (line in reader.lineSequence()) {
                // +1 approximates the stripped line terminator; this bounds the
                // *input* we agree to parse before the check, so an in-budget
                // transcript never trips (cumulative stays <= cap) and its output
                // is unchanged.
                scannedBytes += line.length.toLong() + 1L
                if (atLineBudget(out.size, scannedBytes)) {
                    capTripped = true
                    break
                }
                val t = line.trim()
                if (t.isEmpty()) continue
                val el = runCatching { support.json.parseToJsonElement(t) }.getOrNull() as? JsonObject ?: continue
                val norm = normalizeMessage(el) ?: continue
                if (isLocalMessage(norm)) out += norm
            }
        }
        if (capTripped) logTruncation(file, out.size, scannedBytes)
        return out
    }

    /**
     * True once the transcript scanned so far has reached either cap. Checked
     * before parsing each line, so an in-budget file (both caps sit far above any
     * real transcript) never trips and reads to completion — byte-parity with the
     * admin-shim projection is preserved.
     */
    private fun atLineBudget(keptMessages: Int, scannedBytes: Long): Boolean =
        scannedBytes > maxTranscriptBytes || keptMessages >= maxTranscriptMessages

    /** Emit the single WARN describing a truncated (pathological) transcript. */
    private fun logTruncation(file: File, keptMessages: Int, scannedBytes: Long) {
        Telemetry.event(
            "LocalBackendMessageReader",
            "message.list.transcript_truncated",
            "path" to file.path,
            "keptMessages" to keptMessages,
            "scannedBytes" to scannedBytes,
            "maxBytes" to maxTranscriptBytes,
            "maxMessages" to maxTranscriptMessages,
            level = Telemetry.Level.WARN,
        )
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
    private data class RunRec(val id: String, val createdAt: String, val messageIds: List<String>)

    private fun readRunIdsByMessageId(agentId: String, internalConvId: String): Map<String, String> {
        val root = File(support.baseDir, "runs")
        val dirs = root.listFiles { f -> f.isDirectory && f.name != "_archive" } ?: return emptyMap()
        val runs = dirs.mapNotNull { parseRunRec(it, agentId, internalConvId) }.sortedBy { it.createdAt }
        val map = HashMap<String, String>()
        // Oldest run owning a message id wins (first-write); mirrors the shim.
        for (r in runs) for (mid in r.messageIds) if (!map.containsKey(mid)) map[mid] = r.id
        return map
    }

    /** Read one runs/<id>/run.json, keeping it only if it belongs to this agent+conversation. */
    private fun parseRunRec(dir: File, agentId: String, internalConvId: String): RunRec? {
        val obj = runCatching {
            File(dir, "run.json").takeIf { it.isFile }?.readText()?.let { support.json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: return null
        if (obj["agent_id"]?.stringOrNull() != agentId) return null
        if (obj["conversation_id"]?.stringOrNull() != internalConvId) return null
        val id = obj["id"]?.stringOrNull() ?: return null
        val mids = (obj["message_ids"] as? JsonArray)?.mapNotNull { it.stringOrNull() } ?: emptyList()
        return RunRec(id, obj["created_at"]?.stringOrNull() ?: "", mids)
    }

    companion object {
        private const val MESSAGE_CACHE_MAX = 16

        // Bounded-input guard defaults (audit P3.3). ~3x the largest known
        // on-disk transcript (~87 MB) so real conversations are never clipped;
        // a runaway file is truncated well before it can OOM the reader.
        const val MAX_TRANSCRIPT_BYTES: Long = 256L * 1024 * 1024
        const val MAX_TRANSCRIPT_MESSAGES: Int = 1_000_000
    }
}
