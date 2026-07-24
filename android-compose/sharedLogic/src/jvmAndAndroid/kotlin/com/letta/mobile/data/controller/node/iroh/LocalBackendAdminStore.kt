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
        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    }
}
