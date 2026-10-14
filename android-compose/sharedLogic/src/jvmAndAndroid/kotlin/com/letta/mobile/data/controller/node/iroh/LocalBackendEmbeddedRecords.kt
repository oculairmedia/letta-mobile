package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * letta-mobile-by4vm.21: agent and default-conversation records of an embedded (on-device)
 * local backend — the read/seed half that used to live in the app's `LettaCodeLocalBackendStore`.
 *
 * Unlike the admin-query readers this class WRITES: on the embedded runtime the app is the only
 * writer while letta.js is stopped (letta.js loads the store at process start), so it seeds the
 * records letta.js requires (strictAgentAccess / strictConversationAccess) and keeps the fields it
 * understands in sync with the app's own [Agent] sidecars under `app-agents/`.
 */
internal class LocalBackendEmbeddedRecords(private val baseDir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * App-owned sidecar records holding the full [Agent] model. letta.js normalizes (and rewrites)
     * its own agents/<b64>.json down to a handful of fields, so app-level state must live in a
     * sibling directory letta.js never touches.
     */
    private val appAgentsDirectory: File get() = File(baseDir, APP_AGENTS_DIR)

    fun readAgentRecord(agentId: AgentId): JsonObject? =
        LocalBackendLayout.findAgentRecordFile(baseDir, agentId)?.let(::readJsonObject)

    fun listAgents(): List<Agent> {
        val sidecars = appAgentsDirectory.listFiles { file -> file.extension == "json" }.orEmpty()
            .mapNotNull { file -> runCatching { json.decodeFromString(Agent.serializer(), file.readText()) }.getOrNull() }
        val sidecarIds = sidecars.map { it.id.value }.toSet()
        // Agents that exist only as letta.js records (seeded before sidecars existed) still
        // surface, with whatever name the record carries.
        val recordOnly = File(baseDir, LocalBackendLayout.AGENTS_DIR).listFiles { file -> file.extension == "json" }.orEmpty()
            .mapNotNull { file -> readJsonObject(file)?.let(::recordOnlyAgent) }
            .filter { it.id.value !in sidecarIds }
        return sidecars + recordOnly
    }

    private fun recordOnlyAgent(record: JsonObject): Agent? {
        val id = record.string("id") ?: return null
        return Agent(id = AgentId(id), name = record.string("name") ?: id.take(12), model = record.string("model"))
    }

    fun persistAgent(agent: Agent) {
        appAgentsDirectory.mkdirs()
        File(appAgentsDirectory, "${LocalBackendLayout.b64UrlEncode(agent.id.value)}.json")
            .writeText(json.encodeToString(Agent.serializer(), agent))
        // Keep letta.js's own record in sync for the fields it understands, preserving anything
        // else it has written (it normalizes unknown keys away on its own rewrites).
        mergeAgentRecord(agent.id) {
            put("id", agent.id.value)
            put("name", agent.name)
            agent.system?.let { put("system", it) }
            agent.model?.let { put("model", it) }
        }
    }

    /**
     * Seeds the minimal agent + default-conversation records letta.js needs to accept
     * `--agent <id>` turns. The selected embedded model is authoritative and overwrites the
     * record's model; every other existing field is kept.
     */
    fun seedAgent(agentId: AgentId, modelHandle: String, now: () -> String) {
        mergeAgentRecord(agentId) { existing ->
            put("id", agentId.value)
            put("name", existing?.string("name") ?: "Letta Mobile")
            put("model", modelHandle)
        }
        ensureDefaultConversation(agentId, summary = null, now = now)
    }

    /** The agent's stored model handle; null when the record is missing or the model is blank. */
    fun storedModelHandle(agentId: AgentId): String? =
        readAgentRecord(agentId)?.string("model")?.takeIf { it.isNotBlank() }

    fun createDefaultConversation(agentId: AgentId, summary: String?, now: () -> String): Conversation {
        check(LocalBackendLayout.agentRecordFile(baseDir, agentId).isFile) {
            "Local agent ${agentId.value} is not persisted in the embedded LettaCode store"
        }
        val directory = ensureDefaultConversation(agentId, summary, now)
        return readDefaultConversation(directory)
            ?: throw IllegalStateException("Local conversation record for ${agentId.value} could not be created")
    }

    /** The default conversation of every agent, newest activity first. */
    fun listDefaultConversations(): List<Conversation> {
        val directories = File(baseDir, LocalBackendLayout.CONVERSATIONS_DIR).listFiles { file -> file.isDirectory }
            ?: return emptyList()
        return directories.mapNotNull(::readDefaultConversation)
            .sortedByDescending { it.lastMessageAt ?: it.updatedAt ?: it.createdAt ?: "" }
    }

    /**
     * Creates the default conversation record when absent; when present and [summary] is given,
     * updates its summary. Returns the conversation directory.
     */
    private fun ensureDefaultConversation(agentId: AgentId, summary: String?, now: () -> String): File {
        val directory = LocalBackendLayout.defaultConversationDir(baseDir, agentId).apply { mkdirs() }
        val file = File(directory, LocalBackendLayout.CONVERSATION_FILE)
        if (!file.isFile) {
            writeJsonObject(file, newDefaultConversation(agentId, summary, now))
        } else if (!summary.isNullOrBlank()) {
            val existing = readJsonObject(file)
                ?: throw IllegalStateException("Local conversation record for ${agentId.value} is unreadable")
            writeJsonObject(
                file,
                buildJsonObject {
                    existing.forEach { (key, value) -> put(key, value) }
                    put("summary", summary)
                    put("updated_at", now())
                },
            )
        }
        return directory
    }

    private fun newDefaultConversation(agentId: AgentId, summary: String?, now: () -> String): JsonObject = buildJsonObject {
        val stamp = now()
        put("id", LocalBackendLayout.DEFAULT_CONVERSATION_ID)
        put("agent_id", agentId.value)
        put("archived", false)
        put("archived_at", null as String?)
        put("created_at", stamp)
        put("updated_at", stamp)
        put("last_message_at", null as String?)
        put("summary", summary)
        putJsonArray("in_context_message_ids") {}
    }

    private fun readDefaultConversation(directory: File): Conversation? {
        val record = readJsonObject(File(directory, LocalBackendLayout.CONVERSATION_FILE)) ?: return null
        val diskId = record.string("id") ?: return null
        val agentId = record.string("agent_id") ?: return null
        if (diskId != LocalBackendLayout.DEFAULT_CONVERSATION_ID) return null
        return Conversation(
            id = ConversationId(localConversationIdFor(agentId)),
            agentId = AgentId(agentId),
            summary = record.string("summary"),
            createdAt = record.string("created_at"),
            updatedAt = record.string("updated_at"),
            lastMessageAt = record.string("last_message_at"),
            archived = (record["archived"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
        )
    }

    private fun mergeAgentRecord(agentId: AgentId, fields: JsonObjectBuilder.(existing: JsonObject?) -> Unit) {
        val file = LocalBackendLayout.agentRecordFile(baseDir, agentId).also { it.parentFile?.mkdirs() }
        val existing = file.takeIf { it.isFile }?.let(::readJsonObject)
        writeJsonObject(
            file,
            buildJsonObject {
                existing?.forEach { (key, value) -> put(key, value) }
                fields(existing)
            },
        )
    }

    private fun readJsonObject(file: File): JsonObject? =
        runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()

    private fun writeJsonObject(file: File, value: JsonObject) {
        file.writeText(json.encodeToString(JsonObject.serializer(), value))
    }

    private fun JsonObject.string(key: String): String? = this[key]?.stringOrNull()

    companion object {
        const val APP_AGENTS_DIR: String = "app-agents"

        /**
         * Stable app-facing conversation id for a local agent's on-disk "default" conversation.
         * Every embedded session of an agent IS the same underlying letta.js conversation, so the
         * id must not vary per app session (random suffixes fragment the timeline).
         */
        fun localConversationIdFor(agentId: String): String = "local-conv-$agentId"
    }
}
