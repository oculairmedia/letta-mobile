package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.data.controller.node.agentfiles.AgentFileIndex
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * On-disk `agent.list` reader. Faithful port of admin-shim
 * `GET /v1/agents` (non-slim) + `translate.ts:agentToLettaState` /
 * `store.ts:readBlocksForAgent`. Split out of the former monolithic
 * `LocalBackendAdminStore` as pure code motion — no behavior change.
 */
internal class LocalBackendAgentReader(
    private val support: LocalBackendStoreSupport,
    private val blockReader: LocalBackendBlockReader,
) {

    /**
     * Port of admin-shim `GET /v1/agents` (non-slim): read `agents/{id}.json`, sort
     * by file mtime desc, page by offset/limit, project each via [agentToLettaState].
     * The LIST endpoint never hydrates transcripts (message_ids -> []). Returns null
     * on any error so the caller fails closed.
     *
     * [countAgents] returns the authoritative roster size
     * from the same on-disk records without projecting full objects. Completeness
     * is proven by the directory listing itself (no page / offset).
     */
    fun countAgents(): Int? = runCatching { support.agentIndex.entries().size }.getOrNull()

    /**
     * Ids of hidden (subagent) agents; null when the store cannot be read. Agents past the index cap
     * were never read, so they count as hidden: their conversations stay out of the list rather
     * than a subagent's leaking into it.
     */
    fun hiddenAgentIds(): Set<String>? = runCatching {
        val scan = support.agentIndex.scan()
        scan.entries.filter { it.hidden }.mapTo(HashSet()) { it.id } + scan.uncoveredIds
    }.getOrNull()

    fun listAgentsProjected(limit: Int?, offset: Int?): JsonArray? = runCatching {
        val entries = support.agentIndex.entries()
        val from = (offset ?: 0).coerceAtLeast(0)
        val pageSize = limit?.coerceAtLeast(0) ?: MAX_PROJECTED_AGENTS
        if (limit == null && entries.size - from > MAX_PROJECTED_AGENTS) {
            Telemetry.event(
                "LocalBackendAgents", "unpaged_list_truncated",
                "agents" attr entries.size, "max" attr MAX_PROJECTED_AGENTS, level = Telemetry.Level.WARN,
            )
        }
        // A record that vanished or cannot be parsed since the index saw it is skipped and the
        // next one fills its place, so a page is short only at the end of the roster.
        val page = entries.asSequence().drop(from).mapNotNull(::readAgentRecord).take(pageSize)
        buildJsonArray {
            page.forEach { rec -> add(agentToLettaState(rec, blocks = readBlocksForAgent(rec.id))) }
        }
    }.getOrNull()

    private data class AgentRecord(
        val id: String,
        val obj: JsonObject,
        val mtimeMs: Long,
        val ctimeMs: Long,
    )

    /**
     * One agent's record, parsed now: only the page being served is ever read, never the roster.
     * ctime is not portably readable via java.io; mtime drives ordering (admin-shim sorts by mtime)
     * and both timestamps, matching the shim's behavior closely enough for decode (created<=updated).
     */
    private fun readAgentRecord(entry: AgentFileIndex.Entry): AgentRecord? {
        val obj = runCatching { support.json.parseToJsonElement(entry.file.readText()).jsonObject }.getOrNull() ?: return null
        return AgentRecord(id = entry.id, obj = obj, mtimeMs = entry.mtimeMs, ctimeMs = entry.mtimeMs)
    }

    /** Provider/model/handle triple parsed from a model handle; bundled to keep [buildLlmConfig] out of primitive-obsession territory. */
    private data class ModelInfo(val provider: String, val model: String, val handle: String)

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
        val info = parseModelHandle(handle)
        val settings = record["model_settings"] as? JsonObject ?: JsonObject(emptyMap())
        val created = support.isoMillis(rec.ctimeMs)
        val updated = support.isoMillis(rec.mtimeMs)

        val llmConfig = buildLlmConfig(settings, info)
        val embeddingConfig = buildEmbeddingConfig()
        val memory = buildAgentMemory(blocks)

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
            put("model", info.model)
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

    /** llm_config sub-object of [agentToLettaState]. */
    private fun buildLlmConfig(settings: JsonObject, info: ModelInfo): JsonObject {
        val providerType = settings["provider_type"]?.stringOrNull()
        val contextWindow = settings["context_window_limit"]?.longOrNull() ?: 200_000L
        val temperature = settings["temperature"]?.doubleOrNull() ?: 1.0
        val maxTokens = settings["max_tokens"]?.longOrNull() ?: 16_384L
        return buildJsonObject {
            put("model", info.model)
            put("display_name", info.model)
            put("model_endpoint_type", if (providerType == "lmstudio") "openai" else (providerType ?: "openai"))
            put("model_endpoint", support.lmstudioBaseUrl)
            put("provider_name", info.provider)
            put("provider_category", "base")
            put("model_wrapper", JsonNull)
            put("context_window", contextWindow)
            put("put_inner_thoughts_in_kwargs", false)
            put("handle", info.handle)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("enable_reasoner", false)
            put("reasoning_effort", JsonNull)
        }
    }

    /** embedding_config sub-object of [agentToLettaState]. */
    private fun buildEmbeddingConfig(): JsonObject = buildJsonObject {
        put("embedding_endpoint_type", "openai")
        put("embedding_endpoint", support.lmstudioBaseUrl)
        put("embedding_model", "text-embedding-3-small")
        put("embedding_dim", 1536)
        put("embedding_chunk_size", 300)
        put("handle", "openai/text-embedding-3-small")
        put("batch_size", 32)
    }

    /** memory sub-object of [agentToLettaState]. */
    private fun buildAgentMemory(blocks: JsonArray): JsonObject = buildJsonObject {
        put("agent_type", "memgpt_agent")
        put("git_enabled", true)
        put("blocks", blocks)
        put("file_blocks", JsonArray(emptyList()))
        put("prompt_template", JsonNull)
    }

    /**
     * Port of admin-shim `store.ts:readBlocksForAgent`, now owned by
     * [LocalBackendBlockReader] so `block.list`/`block.get` and the agent
     * projection cannot drift apart (the block id is a locked wire invariant).
     */
    private fun readBlocksForAgent(agentId: String): JsonArray = blockReader.blocksForAgent(agentId)

    private fun parseModelHandle(handle: String): ModelInfo {
        val idx = handle.indexOf('/')
        return if (idx < 0) {
            ModelInfo(provider = "unknown", model = handle, handle = handle)
        } else {
            ModelInfo(provider = handle.substring(0, idx), model = handle.substring(idx + 1), handle = handle)
        }
    }

    companion object {
        const val DEFAULT_MODEL_HANDLE = "lmstudio/opus-4-7"

        /** How many agents an unpaged list projects at most; every agent's blocks are read for each. */
        const val MAX_PROJECTED_AGENTS: Int = 10_000
    }
}
