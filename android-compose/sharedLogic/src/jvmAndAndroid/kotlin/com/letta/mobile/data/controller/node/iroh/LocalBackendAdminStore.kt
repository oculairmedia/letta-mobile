package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * lgns8.9: native admin-query tier that reads the letta-code on-disk backend
 * store DIRECTLY, replacing the lettashim HTTP proxy for reads. lettashim is not
 * a proxy — its admin queries are `admin-shim/lib/store.ts` functions reading
 * `<baseDir>/agents/{id}.json` + `<baseDir>/memfs/<agentId>/memory/system/{label}.md`. This
 * ports the `agent.list` read + the `translate.ts:agentToLettaState` projection so
 * the wrapper serves it without the shim.
 *
 * SAFE BY CONSTRUCTION: constructed only when [baseDir] is explicitly configured
 * (the wrapper leaves it null unless LETTA_LOCAL_BACKEND_DIR is set), and every
 * public reader returns null on ANY failure so the caller falls back to the shim
 * proxy. Until a live parity check flips the wrapper flag on, this code cannot
 * affect production.
 *
 * Field mapping is a faithful port of admin-shim `agentToLettaState` /
 * `readBlocksForAgent` — including the locked wire invariants (e.g. `metadata`
 * is `null`, not `{}`; block ids are sha256(`agentId:label`)[..24]).
 */
class LocalBackendAdminStore(
    private val baseDir: File,
    /** Mirrors admin-shim's `process.env.LMSTUDIO_BASE_URL || "https://api.openai.com/v1"`. */
    private val lmstudioBaseUrl: String =
        System.getenv("LMSTUDIO_BASE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ENDPOINT,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Port of admin-shim `GET /v1/agents` (non-slim): read `agents/{id}.json`, sort
     * by file mtime desc, page by offset/limit, project each via [agentToLettaState].
     * The LIST endpoint never hydrates transcripts (message_ids -> []). Returns null
     * on any error so the caller falls back to the shim proxy.
     */
    fun listAgentsProjected(limit: Int?, offset: Int?): JsonArray? = runCatching {
        val records = readAgentRecords().sortedByDescending { it.mtimeMs }
        val from = (offset ?: 0).coerceAtLeast(0)
        val windowed = if (from >= records.size) {
            emptyList()
        } else {
            val end = if (limit != null) (from + limit.coerceAtLeast(0)).coerceAtMost(records.size) else records.size
            records.subList(from, end)
        }
        buildJsonArray {
            windowed.forEach { rec ->
                add(agentToLettaState(rec, blocks = readBlocksForAgent(rec.id)))
            }
        }
    }.getOrNull()

    private data class AgentRecord(
        val id: String,
        val obj: JsonObject,
        val mtimeMs: Long,
        val ctimeMs: Long,
    )

    private fun readAgentRecords(): List<AgentRecord> {
        val dir = File(baseDir, "agents")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<AgentRecord>(files.size)
        for (f in files) {
            val obj = runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: continue
            val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
            // ctime is not portably readable via java.io; mtime drives ordering
            // (admin-shim sorts by mtime) and both timestamps, matching the
            // shim's behavior closely enough for decode (created<=updated).
            val mtime = f.lastModified()
            out += AgentRecord(id = id, obj = obj, mtimeMs = mtime, ctimeMs = mtime)
        }
        return out
    }

    /**
     * Faithful port of admin-shim `translate.ts:agentToLettaState` with
     * `messages = []` (list endpoint). Emits the wire JSON directly rather than
     * decoding into a model, so the client's existing Agent decoder consumes it
     * byte-compatibly.
     */
    private fun agentToLettaState(rec: AgentRecord, blocks: JsonArray): JsonObject {
        val record = rec.obj
        val handle = record["model"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_MODEL_HANDLE
        val (provider, model) = parseModelHandle(handle)
        val settings = record["model_settings"] as? JsonObject ?: JsonObject(emptyMap())
        val created = isoMillis(rec.ctimeMs)
        val updated = isoMillis(rec.mtimeMs)

        val providerType = settings["provider_type"]?.stringOrNull()
        val contextWindow = settings["context_window_limit"]?.longOrNull() ?: 200_000L
        val temperature = settings["temperature"]?.doubleOrNull() ?: 1.0
        val maxTokens = settings["max_tokens"]?.longOrNull() ?: 16_384L

        val llmConfig = buildJsonObject {
            put("model", model)
            put("display_name", model)
            put("model_endpoint_type", if (providerType == "lmstudio") "openai" else (providerType ?: "openai"))
            put("model_endpoint", lmstudioBaseUrl)
            put("provider_name", provider)
            put("provider_category", "base")
            put("model_wrapper", JsonNull)
            put("context_window", contextWindow)
            put("put_inner_thoughts_in_kwargs", false)
            put("handle", handle)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("enable_reasoner", false)
            put("reasoning_effort", JsonNull)
        }

        val embeddingConfig = buildJsonObject {
            put("embedding_endpoint_type", "openai")
            put("embedding_endpoint", lmstudioBaseUrl)
            put("embedding_model", "text-embedding-3-small")
            put("embedding_dim", 1536)
            put("embedding_chunk_size", 300)
            put("handle", "openai/text-embedding-3-small")
            put("batch_size", 32)
        }

        val memory = buildJsonObject {
            put("agent_type", "memgpt_agent")
            put("git_enabled", true)
            put("blocks", blocks)
            put("file_blocks", JsonArray(emptyList()))
            put("prompt_template", JsonNull)
        }

        return buildJsonObject {
            put("id", record["id"] ?: JsonPrimitive(rec.id))
            put("name", record["name"]?.nonNullOr { JsonPrimitive("Untitled") } ?: JsonPrimitive("Untitled"))
            put("description", record["description"] ?: JsonNull)
            put("system", record["system"] ?: JsonPrimitive(""))
            put("agent_type", "memgpt_agent")
            put("tags", record["tags"] as? JsonArray ?: JsonArray(emptyList()))
            // Locked invariant (admin-shim Phase 2a audit): metadata is null, not {}.
            put("metadata", JsonNull)
            put("created_at", created)
            put("updated_at", updated)
            put("created_by_id", JsonNull)
            put("last_updated_by_id", JsonNull)
            put("project_id", JsonNull)
            put("template_id", JsonNull)
            put("base_template_id", JsonNull)
            put("deployment_id", JsonNull)
            put("entity_id", JsonNull)
            put("tool_rules", JsonArray(emptyList()))
            put("message_ids", JsonArray(emptyList()))
            put("llm_config", llmConfig)
            put("embedding_config", embeddingConfig)
            put("model", model)
            put("embedding", "openai/text-embedding-3-small")
            put("model_settings", settings)
            put("compaction_settings", record["compaction_settings"] ?: JsonNull)
            put("response_format", JsonNull)
            put("memory", memory)
            put("blocks", blocks)
            put("tools", JsonArray(emptyList()))
            put("sources", JsonArray(emptyList()))
            put("tool_exec_environment_variables", JsonArray(emptyList()))
            put("secrets", JsonArray(emptyList()))
            put("identity_ids", JsonArray(emptyList()))
            put("identities", JsonArray(emptyList()))
            put("pending_approval", JsonNull)
            put("message_buffer_autoclear", false)
            put("enable_sleeptime", false)
            put("multi_agent_group", JsonNull)
            put("managed_group", JsonNull)
            put("last_run_completion", JsonNull)
            put("last_run_duration_ms", JsonNull)
            put("last_stop_reason", "user")
            put("timezone", "UTC")
            put("max_files_open", 10)
            put("per_file_view_window_char_limit", 40_000)
            put("hidden", JsonNull)
            put("webhook_url", JsonNull)
            put("webhook_secret", JsonNull)
            put("webhook_events", JsonArray(emptyList()))
            put("webhook_enabled", false)
        }
    }

    /**
     * Port of admin-shim `store.ts:readBlocksForAgent`. Blocks live as
     * `memfs/<agentId>/memory/system/<label>.md`; each file is one Block. The
     * id MUST be sha256(`agentId:label`)[..24] (the shim switched off a base64
     * slice that collided and crashed mobile's blocks screen on duplicate keys).
     */
    private fun readBlocksForAgent(agentId: String): JsonArray {
        val dir = File(File(File(File(baseDir, "memfs"), agentId), "memory"), "system")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: return JsonArray(emptyList())
        // Deterministic order (readdir order is fs-dependent; sort for stable output).
        return buildJsonArray {
            files.sortedBy { it.name }.forEach { f ->
                val label = f.name.removeSuffix(".md")
                val value = runCatching { f.readText() }.getOrDefault("")
                add(
                    buildJsonObject {
                        put("id", "block-" + sha256Hex("$agentId:$label").take(24))
                        put("label", label)
                        put("value", value)
                        put("description", JsonNull)
                        put("metadata", JsonNull)
                        put("limit", 5000)
                        put("created_by_id", JsonNull)
                        put("last_updated_by_id", JsonNull)
                        put("is_template", false)
                        put("template_name", JsonNull)
                        put("preserve_on_migration", false)
                        put("read_only", false)
                        put("tags", JsonArray(emptyList()))
                        put("hidden", JsonNull)
                        put("project_id", JsonNull)
                        put("template_id", JsonNull)
                        put("base_template_id", JsonNull)
                        put("deployment_id", JsonNull)
                        put("entity_id", JsonNull)
                    },
                )
            }
        }
    }

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
        val root = File(baseDir, "conversations")
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<ConvRecord>()
        for (d in dirs) {
            val key = runCatching { b64UrlDecode(d.name) }.getOrNull() ?: continue
            if (agentId != null && key != "default:$agentId" && !key.startsWith("conversation:")) continue
            val obj = runCatching {
                File(d, "conversation.json").takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
            }.getOrNull() ?: continue
            val convId = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
            val convAgent = obj["agent_id"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
            if (agentId != null && convAgent != agentId) continue
            out += ConvRecord(
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
        return out
    }

    /**
     * Port of store.ts withRealTimes: overlay the sidecar max message time. If the
     * on-disk last/updated are CLI sentinels (2026-01-01T...), substitute
     * max -> created_at -> (skipped: current time, unavailable deterministically);
     * bump last/updated to the sidecar max when it is greater.
     */
    private fun withRealTimes(c: ConvRecord): ConvRecord {
        val max = maxRealMessageTime(c.id, c.agentId)
        var last = c.lastMessageAt ?: ""
        var updated = c.updatedAt ?: ""
        val created = c.createdAt ?: ""
        if (isSentinelDate(last)) last = max.ifEmpty { created }
        if (isSentinelDate(updated)) updated = max.ifEmpty { created }
        if (max.isNotEmpty() && max > last) last = max
        if (max.isNotEmpty() && max > updated) updated = max
        return c.copy(lastMessageAt = last.ifEmpty { c.lastMessageAt }, updatedAt = updated.ifEmpty { c.updatedAt })
    }

    /** Port of maxRealMessageTime: legacy _real-times.json map, then overlay _real-times.jsonl (later wins), max iso. */
    private fun maxRealMessageTime(conversationId: String, agentId: String): String {
        val dir = File(File(baseDir, "conversations"), b64UrlEncode(conversationKey(conversationId, agentId)))
        val map = HashMap<String, String>()
        runCatching {
            File(dir, "_real-times.json").takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
                ?.forEach { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { map[k] = it.content } }
        }
        runCatching {
            File(dir, "_real-times.jsonl").takeIf { it.isFile }?.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEachLine
                runCatching {
                    val o = json.parseToJsonElement(t).jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNullSafe()
                    val iso = o["iso"]?.jsonPrimitive?.contentOrNullSafe()
                    if (id != null && iso != null) map[id] = iso
                }
            }
        }
        var max = ""
        for (iso in map.values) if (iso > max) max = iso
        return max
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

    // ──────────────────────────────────────────────────────────────────
    // message.list (lgns8.9 slice 3)
    //
    // Faithful port of admin-shim `handleConversationMessagesList`
    // (server.ts) + `store.ts:listMessages`/`normalizeMessage` +
    // `translate.ts:localMessageToConversationMessages` (the 1:N fan-out).
    // Emits already-projected wire messages so the caller passes the output
    // straight through MessageListPageGuard.bound (the pointer-diet /
    // page-size layer runs on wire messages regardless of source, exactly
    // as it does on the shim proxy response).
    //
    // Deliberate divergences from the live shim, each safe for a cold
    // on-disk reader:
    //   - `after` is accepted but NOT applied: the shim's /messages route
    //     reads only limit/before/order (parsePagination + before + order);
    //     `after` never reaches store.listMessages. We mirror that exactly.
    //   - in-flight run filtering (inFlightMessageIds) is skipped: it drops
    //     messages owned by an ACTIVE run in the shim's process. A client
    //     reading the disk store has no active runs, so the set is always
    //     empty — omitting it cannot change output for a settled transcript.
    // ──────────────────────────────────────────────────────────────────

    /**
     * Port of `GET /v1/conversations/{id}/messages`. Resolves the external
     * conv id, reads + normalizes messages.jsonl, applies before/limit/order
     * exactly as the shim route does, then fans each LocalMessage out via
     * [localMessageToConversationMessages] with the realTimes/otid/attachment/
     * runId sidecar scope. Returns null on ANY error so the caller falls back
     * to the shim proxy.
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
        val dir = File(File(baseDir, "conversations"), b64UrlEncode(conversationKey(internalConvId, resolvedAgentId)))
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
                localMessageToConversationMessages(m, data.realTimes, data.otid, data.runIds, data.attachments)
                    .forEach { add(it) }
            }
        }
    }.getOrNull()

    private data class MessageData(
        val messages: List<JsonObject>,
        val realTimes: Map<String, String>,
        val otid: Map<String, String>,
        val attachments: Map<String, JsonArray>,
        val runIds: Map<String, String>,
    )

    private data class CachedMessageData(val signature: String, val data: MessageData)

    private val messageCache = java.util.concurrent.ConcurrentHashMap<String, CachedMessageData>()

    /**
     * lgns8.9: cache the parsed transcript + sidecar maps per conversation, keyed
     * on a composite file signature (messages.jsonl + sidecars + runs dir mtime),
     * so repeated polls on a large transcript (the ~87MB main conversation) don't
     * re-parse the whole file every call — mirrors the shim's messagesCache. An
     * append (new message) bumps messages.jsonl mtime/size → the signature changes
     * → the entry is recomputed. Cross-process: this reader isn't the writer, so
     * a plain file-signature check (not writer-invalidation) is the correct
     * staleness gate. Bounded so a many-conversation host can't grow it without limit.
     */
    private fun loadMessageData(dir: File, agentId: String, internalConvId: String): MessageData {
        val sig = messageCacheSignature(dir)
        val key = dir.path
        messageCache[key]?.let { if (it.signature == sig) return it.data }
        val data = MessageData(
            messages = readLocalMessages(File(dir, "messages.jsonl")),
            realTimes = readRealTimesMap(dir),
            otid = readStringMap(File(dir, "_otid-map.json")),
            attachments = readAttachmentMap(File(dir, "_attachments.json")),
            runIds = readRunIdsByMessageId(agentId, internalConvId),
        )
        if (messageCache.size > MESSAGE_CACHE_MAX) messageCache.clear()
        messageCache[key] = CachedMessageData(sig, data)
        return data
    }

    private fun messageCacheSignature(dir: File): String {
        fun stamp(f: File): String = if (f.isFile) "${f.lastModified()}:${f.length()}" else "-"
        val runs = File(baseDir, "runs").let { if (it.isDirectory) it.lastModified().toString() else "-" }
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
        val dir = File(File(baseDir, "conversations"), b64UrlEncode("conversation:$externalId"))
        val obj = runCatching {
            File(dir, "conversation.json").takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
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
            val el = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject ?: return@forEachLine
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
     * Port of store.ts readMessageTimestamps: legacy `_real-times.json` map
     * first, then overlay `_real-times.jsonl` (later lines win). Returns the
     * full id->iso map (the projection joins it per message; conversation.list
     * only needs the max, which [maxRealMessageTime] keeps separate).
     */
    private fun readRealTimesMap(dir: File): Map<String, String> {
        val map = HashMap<String, String>()
        runCatching {
            File(dir, "_real-times.json").takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
                ?.forEach { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { map[k] = it.content } }
        }
        runCatching {
            File(dir, "_real-times.jsonl").takeIf { it.isFile }?.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEachLine
                runCatching {
                    val o = json.parseToJsonElement(t).jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNullSafe()
                    val iso = o["iso"]?.jsonPrimitive?.contentOrNullSafe()
                    if (id != null && iso != null) map[id] = iso
                }
            }
        }
        return map
    }

    /** Read a flat string->string JSON map (e.g. `_otid-map.json`); {} on any fault. */
    private fun readStringMap(file: File): Map<String, String> {
        val obj = runCatching {
            file.takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, String>()
        obj.forEach { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { map[k] = it.content } }
        return map
    }

    /** Read `_attachments.json`: id -> JsonArray of attachment refs; {} on any fault. */
    private fun readAttachmentMap(file: File): Map<String, JsonArray> {
        val obj = runCatching {
            file.takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, JsonArray>()
        obj.forEach { (k, v) -> (v as? JsonArray)?.let { map[k] = it } }
        return map
    }

    /**
     * Port of runs.ts buildMessageRunMap: walk `<baseDir>/runs/<runId>/run.json`
     * (skipping the `_archive` subdir, matching the live-only walk in
     * listRuns), keep runs matching BOTH agentId and the INTERNAL conversation
     * id, sort by created_at ascending, and map each message id -> run id with
     * the OLDEST run winning (`if (!map[id]) map[id] = run.id`).
     */
    private fun readRunIdsByMessageId(agentId: String, internalConvId: String): Map<String, String> {
        val root = File(baseDir, "runs")
        val dirs = root.listFiles { f -> f.isDirectory && f.name != "_archive" } ?: return emptyMap()
        data class RunRec(val id: String, val createdAt: String, val messageIds: List<String>)
        val runs = ArrayList<RunRec>()
        for (d in dirs) {
            val obj = runCatching {
                File(d, "run.json").takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
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

    // ── translate.ts:localMessageToConversationMessages (1:N fan-out) ──────

    private val typeOffsetMs = mapOf(
        "user_message" to 0L,
        "system_message" to 0L,
        "reasoning_message" to 10L,
        "tool_call_message" to 20L,
        "tool_return_message" to 30L,
        "assistant_message" to 40L,
    )

    /** Port of translate.ts withTypeOffset: add the per-type ms offset to the ISO date. */
    private fun withTypeOffset(createdIso: String, messageType: String): String {
        val off = typeOffsetMs[messageType] ?: 0L
        if (off == 0L) return createdIso
        val t = runCatching { Instant.parse(createdIso).toEpochMilli() }.getOrNull() ?: return createdIso
        return isoMillis(t + off)
    }

    /** Port of translate.ts partsToText: concatenate all `text` parts. */
    private fun partsToText(parts: JsonArray): String {
        val sb = StringBuilder()
        for (p in parts) {
            val o = p as? JsonObject ?: continue
            if (o["type"]?.stringOrNull() == "text") sb.append(o["text"]?.stringOrNull() ?: "")
        }
        return sb.toString()
    }

    /** Port of translate.ts stripSystemReminders (user-role only). */
    private fun stripSystemReminders(text: String): String =
        text
            .replace(Regex("<system-reminder>[\\s\\S]*?</system-reminder>"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    /** Port of translate.ts flattenToolOutput. */
    private fun flattenToolOutput(value: JsonElement?): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> if (value.isString) value.content else value.toString()
        is JsonArray -> value.joinToString("") { p ->
            when {
                p is JsonPrimitive && p.isString -> p.content
                p is JsonObject && p["type"]?.stringOrNull() == "text" && p["text"]?.stringOrNull() != null ->
                    p["text"]!!.stringOrNull()!!
                else -> p.toString()
            }
        }
        is JsonObject -> value.toString()
    }

    /** JS `JSON.stringify(value ?? {})` — compact; string args pass through verbatim. */
    private fun jsonStringifyArgs(value: JsonElement?): String = when (value) {
        null, is JsonNull -> "{}"
        is JsonPrimitive -> if (value.isString) value.content else value.toString()
        else -> value.toString()
    }

    private fun toStringArrayOrNull(value: JsonElement?): JsonElement = when (value) {
        null, is JsonNull -> JsonNull
        is JsonArray -> JsonArray(value.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { JsonPrimitive(it.content) })
        is JsonPrimitive -> if (value.isString) JsonArray(listOf(JsonPrimitive(value.content))) else JsonNull
        else -> JsonNull
    }

    /**
     * Faithful port of translate.ts `localMessageToConversationMessages`.
     * One LocalMessage projects to one or more wire messages. Wire field sets
     * and key order match the TS object literals byte-for-byte.
     */
    private fun localMessageToConversationMessages(
        localMsg: JsonObject,
        realTimes: Map<String, String>,
        otidMap: Map<String, String>,
        runIds: Map<String, String>,
        attachments: Map<String, JsonArray>,
    ): List<JsonObject> {
        val id = localMsg["id"]?.stringOrNull()
        val sentinel = (localMsg["metadata"] as? JsonObject)?.get("created_at")?.stringOrNull()
        val real = id?.let { realTimes[it] }
        val created = real ?: sentinel ?: isoMillis(System.currentTimeMillis())
        val role = localMsg["role"]?.stringOrNull() ?: "system"
        val parts = localMsg["parts"] as? JsonArray ?: JsonArray(emptyList())
        val projectedOtid = (id?.let { otidMap[it] }) ?: id
        val projectedRunId: JsonElement = (id?.let { runIds[it] })?.let { JsonPrimitive(it) } ?: JsonNull
        val attachmentRefs = id?.let { attachments[it] }

        // User / system: collapse text parts into one wire message.
        if (role == "user" || role == "system") {
            var text = partsToText(parts)
            if (role == "user") text = stripSystemReminders(text)
            if (text.isEmpty()) return emptyList()
            val wireType = if (role == "user") "user_message" else "system_message"
            val wire = buildJsonObject {
                put("id", id ?: "")
                put("date", withTypeOffset(created, wireType))
                put("name", JsonNull)
                put("message_type", wireType)
                put("otid", projectedOtid?.let { JsonPrimitive(it) } ?: JsonNull)
                put("sender_id", JsonNull)
                put("step_id", JsonNull)
                put("is_err", JsonNull)
                put("seq_id", JsonNull)
                put("run_id", projectedRunId)
                put("content", text)
                // attachRefsToWireMessage: only user_message, only when refs present.
                if (wireType == "user_message" && attachmentRefs != null && attachmentRefs.isNotEmpty()) {
                    put("attachments", attachmentRefs)
                }
            }
            return listOf(wire)
        }

        // toolResult top-level row (letta-code 0.25.x).
        if (role == "toolResult") {
            val callId = localMsg["toolCallId"]?.stringOrNull() ?: ""
            val toolName = localMsg["toolName"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
            val isError = (localMsg["isError"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
            val returnText = partsToText(parts)
            val status = if (isError) "error" else "success"
            val tr = buildJsonObject {
                put("tool_call_id", callId)
                put("status", status)
                put("stdout", JsonNull)
                put("stderr", JsonNull)
                put("func_response", returnText)
                put("type", "tool")
            }
            val trMsg = buildJsonObject {
                put("id", if (callId.isNotEmpty()) "toolreturn-$callId" else (id ?: ""))
                put("date", withTypeOffset(created, "tool_return_message"))
                put("name", toolName?.let { JsonPrimitive(it) } ?: JsonNull)
                put("message_type", "tool_return_message")
                put("otid", projectedOtid?.let { JsonPrimitive(it) } ?: JsonNull)
                put("sender_id", JsonNull)
                put("step_id", JsonNull)
                put("is_err", if (isError) JsonPrimitive(true) else JsonNull)
                put("seq_id", JsonNull)
                put("run_id", projectedRunId)
                put("tool_call_id", callId)
                put("status", status)
                put("tool_return", returnText)
                put("stdout", JsonNull)
                put("stderr", JsonNull)
                put("tool_returns", JsonArray(listOf(tr)))
            }
            return listOf(trMsg)
        }

        // Assistant / tool: walk parts, grouping consecutive text.
        val out = ArrayList<JsonObject>()
        val pendingText = StringBuilder()
        var pendingTextStartIndex = -1
        fun flushText() {
            if (pendingText.isEmpty()) return
            val isFirst = out.isEmpty()
            out += buildJsonObject {
                put("id", if (isFirst) (id ?: "") else "$id:assistant:$pendingTextStartIndex")
                put("date", withTypeOffset(created, "assistant_message"))
                put("name", JsonNull)
                put("message_type", "assistant_message")
                put("otid", id?.let { JsonPrimitive(it) } ?: JsonNull)
                put("sender_id", JsonNull)
                put("step_id", JsonNull)
                put("is_err", JsonNull)
                put("seq_id", JsonNull)
                put("run_id", projectedRunId)
                put("content", pendingText.toString())
            }
            pendingText.setLength(0)
            pendingTextStartIndex = -1
        }

        for (i in 0 until parts.size) {
            val part = parts[i] as? JsonObject ?: continue
            val type = part["type"]?.stringOrNull() ?: continue

            if (type == "text" && part["text"]?.stringOrNull() != null) {
                if (pendingTextStartIndex == -1) pendingTextStartIndex = i
                pendingText.append(part["text"]!!.stringOrNull())
                continue
            }

            if (type == "reasoning" && part["text"]?.stringOrNull() != null) {
                flushText()
                val signature = (part["providerMetadata"] as? JsonObject)?.get("signature")?.stringOrNull()
                out += buildJsonObject {
                    put("id", "$id:reasoning:$i")
                    put("date", withTypeOffset(created, "reasoning_message"))
                    put("name", JsonNull)
                    put("message_type", "reasoning_message")
                    put("otid", id?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("sender_id", JsonNull)
                    put("step_id", JsonNull)
                    put("is_err", JsonNull)
                    put("seq_id", JsonNull)
                    put("run_id", projectedRunId)
                    put("source", "reasoner_model")
                    put("reasoning", part["text"]!!.stringOrNull()!!)
                    put("signature", signature?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                continue
            }

            // Legacy `tool-call` + new camelCase `toolCall`.
            if (type == "tool-call" || type == "toolCall") {
                flushText()
                val argsStr = jsonStringifyArgs(part["arguments"])
                val callId = part["toolCallId"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
                    ?: part["id"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
                    ?: ""
                val name = part["name"]?.stringOrNull()?.takeIf { it.isNotEmpty() } ?: "tool"
                val tc = buildJsonObject {
                    put("name", name)
                    put("arguments", argsStr)
                    put("tool_call_id", callId)
                }
                out += buildJsonObject {
                    put("id", if (callId.isNotEmpty()) "toolcall-$callId" else "$id:tool:$i:call")
                    put("date", withTypeOffset(created, "tool_call_message"))
                    put("name", name)
                    put("message_type", "tool_call_message")
                    put("otid", id?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("sender_id", JsonNull)
                    put("step_id", JsonNull)
                    put("is_err", JsonNull)
                    put("seq_id", JsonNull)
                    put("run_id", projectedRunId)
                    put("tool_call", tc)
                    put("tool_calls", JsonArray(listOf(tc)))
                }
                continue
            }

            // Native LocalBackend tool part: `tool-<name>` with toolCallId.
            if (type.startsWith("tool-") && type != "tool-call" && type != "tool-return" &&
                part["toolCallId"]?.stringOrNull() != null
            ) {
                flushText()
                val toolCallId = part["toolCallId"]!!.stringOrNull()!!
                val toolName = type.substring("tool-".length)
                val argsStr = jsonStringifyArgs(part["input"])
                val tc = buildJsonObject {
                    put("name", toolName)
                    put("arguments", argsStr)
                    put("tool_call_id", toolCallId)
                }
                out += buildJsonObject {
                    put("id", "toolcall-$toolCallId")
                    put("date", withTypeOffset(created, "tool_call_message"))
                    put("name", toolName)
                    put("message_type", "tool_call_message")
                    put("otid", id?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("sender_id", JsonNull)
                    put("step_id", JsonNull)
                    put("is_err", JsonNull)
                    put("seq_id", JsonNull)
                    put("run_id", projectedRunId)
                    put("tool_call", tc)
                    put("tool_calls", JsonArray(listOf(tc)))
                }
                val state = part["state"]?.stringOrNull()
                if (state == "output-available" || state == "output-error" || state == "output-denied") {
                    val isError = state != "output-available"
                    val returnText = if (isError) {
                        part["errorText"]?.stringOrNull() ?: flattenToolOutput(part["output"])
                    } else {
                        flattenToolOutput(part["output"])
                    }
                    val status = if (isError) "error" else "success"
                    val tr = buildJsonObject {
                        put("tool_call_id", toolCallId)
                        put("status", status)
                        put("stdout", JsonNull)
                        put("stderr", JsonNull)
                        put("func_response", returnText)
                        put("type", "tool")
                    }
                    out += buildJsonObject {
                        put("id", "toolreturn-$toolCallId")
                        put("date", withTypeOffset(created, "tool_return_message"))
                        put("name", toolName)
                        put("message_type", "tool_return_message")
                        put("otid", id?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("sender_id", JsonNull)
                        put("step_id", JsonNull)
                        put("is_err", if (isError) JsonPrimitive(true) else JsonNull)
                        put("seq_id", JsonNull)
                        put("run_id", projectedRunId)
                        put("tool_call_id", toolCallId)
                        put("status", status)
                        put("tool_return", returnText)
                        put("stdout", JsonNull)
                        put("stderr", JsonNull)
                        put("tool_returns", JsonArray(listOf(tr)))
                    }
                }
                continue
            }

            if (type == "tool-return") {
                flushText()
                val callId = part["toolCallId"]?.stringOrNull() ?: ""
                val status = if (part["status"]?.stringOrNull() == "error") "error" else "success"
                val returnRaw = part["tool_return"]
                val returnText = when {
                    returnRaw is JsonPrimitive && returnRaw.isString -> returnRaw.content
                    returnRaw == null || returnRaw is JsonNull -> "\"\""
                    else -> returnRaw.toString()
                }
                val stdout = toStringArrayOrNull(part["stdout"])
                val stderr = toStringArrayOrNull(part["stderr"])
                val name = part["name"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
                val tr = buildJsonObject {
                    put("tool_call_id", callId)
                    put("status", status)
                    put("stdout", stdout)
                    put("stderr", stderr)
                    put("func_response", returnText)
                    put("type", "tool")
                }
                out += buildJsonObject {
                    put("id", if (callId.isNotEmpty()) "toolreturn-$callId" else "$id:tool:$i:return")
                    put("date", withTypeOffset(created, "tool_return_message"))
                    put("name", name?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("message_type", "tool_return_message")
                    put("otid", id?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("sender_id", JsonNull)
                    put("step_id", JsonNull)
                    put("is_err", if (part["status"]?.stringOrNull() == "error") JsonPrimitive(true) else JsonNull)
                    put("seq_id", JsonNull)
                    put("run_id", projectedRunId)
                    put("tool_call_id", callId)
                    put("status", status)
                    put("tool_return", returnText)
                    put("stdout", stdout)
                    put("stderr", stderr)
                    put("tool_returns", JsonArray(listOf(tr)))
                }
                continue
            }
            // Unknown tool-shaped part: skip (shim logs + skips).
        }
        flushText()
        return out
    }

    private fun conversationKey(conversationId: String, agentId: String): String =
        if (conversationId == "default") "default:$agentId" else "conversation:$conversationId"

    private fun b64UrlEncode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun b64UrlDecode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    private fun isSentinelDate(iso: String): Boolean = iso.startsWith("2026-01-01T")

    private fun parseModelHandle(handle: String): Pair<String, String> {
        val idx = handle.indexOf('/')
        return if (idx < 0) "unknown" to handle else handle.substring(0, idx) to handle.substring(idx + 1)
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** JS `new Date(ms).toISOString()` — always UTC, always 3-digit millis, 'Z'. */
    private fun isoMillis(ms: Long): String =
        ISO_MILLIS.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.longOrNull(): Long? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong()

    private fun JsonElement.doubleOrNull(): Double? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    private inline fun JsonElement.nonNullOr(fallback: () -> JsonElement): JsonElement =
        if (this is JsonNull) fallback() else this

    companion object {
        const val DEFAULT_MODEL_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_MODEL_HANDLE = "lmstudio/opus-4-7"
        private const val MESSAGE_CACHE_MAX = 16
        private val ARCHIVE_STATUSES = setOf("active", "archived", "all")
        // admin-shim translate.ts: canned user uuid for created_by/last_updated_by.
        private val CANNED_USER_ID = JsonPrimitive("user-00000000-0000-4000-8000-000000000000")
        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    }
}
