package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Single owner of the on-device letta.js local-backend storage format
 * (LETTA_LOCAL_BACKEND_DIR). Everything that reads or writes letta.js's
 * files — agent records, conversation records, transcripts — must go
 * through this class so the file-format coupling stays in one place,
 * versioned against the pinned letta-code release.
 *
 * Layout (mirrors letta.js src/backend/local/local-store.ts):
 *  - agents/<base64url(agentId)>.json
 *  - conversations/<base64url(key)>/conversation.json (+ messages.jsonl)
 *    where key = "default:<agentId>" for the per-agent default
 *    conversation, "conversation:<id>" otherwise.
 *
 * letta.js runs with strictAgentAccess/strictConversationAccess, so both
 * records must exist before a turn; [seedAgent] creates them. letta.js
 * only loads this store at process start — external writes are invisible
 * to a running session.
 */
@Singleton
class LettaCodeLocalBackendStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocalRuntimeConversationSource, LocalRuntimeAgentSource {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val storageDirectory: File
        get() = File(context.filesDir, "embedded-lettacode/local-backend")

    /**
     * App-owned sidecar records holding the full [Agent] model. letta.js
     * normalizes (and rewrites) its own agents/<b64>.json down to a handful
     * of fields, so app-level state — runtime binding metadata, the
     * user-chosen name, embedding config — must live in a sibling directory
     * letta.js never touches.
     */
    private val appAgentsDirectory: File
        get() = File(storageDirectory, "app-agents")

    override suspend fun listAgents(): List<Agent> = withContext(Dispatchers.IO) {
        val sidecars = appAgentsDirectory.listFiles { file -> file.extension == "json" }.orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString(Agent.serializer(), file.readText()) }.getOrNull()
            }
        val sidecarIds = sidecars.map { it.id.value }.toSet()
        // Agents that exist only as letta.js records (seeded before sidecars
        // existed) still surface, with whatever name the record carries.
        val recordOnly = File(storageDirectory, "agents").listFiles { file -> file.extension == "json" }.orEmpty()
            .mapNotNull { file ->
                val record = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                val id = record.stringField("id") ?: return@mapNotNull null
                if (id in sidecarIds) return@mapNotNull null
                Agent(
                    id = AgentId(id),
                    name = record.stringField("name") ?: id.take(12),
                    model = record.stringField("model"),
                )
            }
        sidecars + recordOnly
    }

    override suspend fun persistAgent(agent: Agent) = withContext(Dispatchers.IO) {
        appAgentsDirectory.mkdirs()
        File(appAgentsDirectory, "${base64Url(agent.id.value)}.json")
            .writeText(json.encodeToString(Agent.serializer(), agent))
        // Keep letta.js's own record in sync for the fields it understands,
        // preserving anything else it has written (it normalizes unknown
        // keys away on its own rewrites, so only known fields are merged).
        val agentsDirectory = File(storageDirectory, "agents").apply { mkdirs() }
        val recordFile = File(agentsDirectory, "${base64Url(agent.id.value)}.json")
        val existing = recordFile.takeIf { it.isFile }
            ?.let { runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
        val merged = buildJsonObject {
            existing?.forEach { (key, value) -> put(key, value) }
            put("id", agent.id.value)
            put("name", agent.name)
            agent.system?.let { put("system", it) }
            agent.model?.let { put("model", it) }
        }
        recordFile.writeText(json.encodeToString(JsonObject.serializer(), merged))
    }

    override suspend fun contextWindowOverview(agentId: AgentId): ContextWindowOverview? =
        withContext(Dispatchers.IO) {
            val recordFile =
                File(File(storageDirectory, "agents"), "${base64Url(agentId.value)}.json")
            val record = recordFile.takeIf { it.isFile }
                ?.let { runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
                ?: return@withContext null
            val system = record.stringField("system").orEmpty()
            val transcript = File(
                File(File(storageDirectory, "conversations"), base64Url("default:${agentId.value}")),
                "messages.jsonl",
            )
            val transcriptBytes = transcript.takeIf { it.isFile }?.length() ?: 0L
            val messageCount = transcript.takeIf { it.isFile }
                ?.useLines { lines -> lines.count { it.isNotBlank() } } ?: 0
            // Same heuristic letta.js applies for local providers
            // (estimateSerializedTokens): ~4 chars per token.
            val systemTokens = system.length / 4
            val messageTokens = (transcriptBytes / 4L).toInt()
            ContextWindowOverview(
                // letta.js's customOpenAICompatibleModel defaults the context
                // window to 128k unless model_settings overrides it.
                contextWindowSizeMax = record.intField("context_window_limit")
                    ?: DEFAULT_LOCAL_CONTEXT_WINDOW,
                contextWindowSizeCurrent = systemTokens + messageTokens,
                numMessages = messageCount,
                numTokensSystem = systemTokens,
                numTokensMessages = messageTokens,
            )
        }

    /**
     * Seeds the minimal agent + default-conversation records letta.js needs
     * to accept --agent <id> turns. No-ops for records that already exist;
     * letta.js's normalizeAgentRecord fills every other field with defaults.
     */
    fun seedAgent(agentId: String, modelHandle: String) {
        val agentFile = File(File(storageDirectory, "agents").apply { mkdirs() }, "${base64Url(agentId)}.json")
        if (!agentFile.isFile) {
            val record = buildJsonObject {
                put("id", agentId)
                put("name", "Letta Mobile")
                put("model", modelHandle)
            }
            agentFile.writeText(json.encodeToString(JsonObject.serializer(), record))
        }

        val conversationDirectory =
            File(File(storageDirectory, "conversations"), base64Url("default:$agentId")).apply { mkdirs() }
        val conversationFile = File(conversationDirectory, "conversation.json")
        if (!conversationFile.isFile) {
            val record = buildJsonObject {
                put("id", "default")
                put("agent_id", agentId)
                put("archived", false)
                put("archived_at", null as String?)
                put("created_at", "2026-01-01T00:00:00.000Z")
                put("updated_at", "2026-01-01T00:00:00.000Z")
                put("last_message_at", null as String?)
                put("summary", null as String?)
                putJsonArray("in_context_message_ids") {}
            }
            conversationFile.writeText(json.encodeToString(JsonObject.serializer(), record))
        }
    }

    /**
     * Lists conversations letta.js has persisted, mapped to app-facing
     * [Conversation]s. The on-disk default conversation is exposed under the
     * stable id "local-conv-<agentId>" — the same id the chat send path
     * uses — so list rows open the timeline the chat screen renders.
     * Works with no node process running (cold app start).
     */
    override suspend fun listConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val conversationsDir = File(storageDirectory, "conversations")
        val directories = conversationsDir.listFiles { file -> file.isDirectory } ?: return@withContext emptyList()
        directories.mapNotNull { directory ->
            val record = runCatching {
                json.parseToJsonElement(File(directory, "conversation.json").readText()).jsonObject
            }.getOrNull() ?: return@mapNotNull null
            val diskId = record.stringField("id") ?: return@mapNotNull null
            val agentId = record.stringField("agent_id") ?: return@mapNotNull null
            // Only the per-agent default conversation exists today; forks
            // ("conversation:<id>" keys) have no chat-side binding yet.
            if (diskId != "default") return@mapNotNull null
            Conversation(
                id = ConversationId(localConversationIdFor(agentId)),
                agentId = AgentId(agentId),
                summary = record.stringField("summary"),
                createdAt = record.stringField("created_at"),
                updatedAt = record.stringField("updated_at"),
                lastMessageAt = record.stringField("last_message_at"),
                archived = record["archived"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            )
        }.sortedByDescending { it.lastMessageAt ?: it.updatedAt ?: it.createdAt ?: "" }
    }

    /**
     * Reads the agent's default-conversation transcript (messages.jsonl,
     * pi-ai local-message rows maintained by letta.js) as timeline messages,
     * so the chat screen can hydrate history across app restarts. Synthetic
     * context rows (system-reminder user messages letta.js injects) are
     * skipped — they were never user-visible.
     */
    suspend fun readTranscript(agentId: String): List<LettaMessage> = withContext(Dispatchers.IO) {
        val transcript = File(
            File(File(storageDirectory, "conversations"), base64Url("default:$agentId")),
            "messages.jsonl",
        )
        if (!transcript.isFile) return@withContext emptyList()
        transcript.useLines { lines ->
            lines.filter { it.isNotBlank() }.mapIndexedNotNull { index, line ->
                val row = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@mapIndexedNotNull null
                val id = row.stringField("id") ?: return@mapIndexedNotNull null
                val role = row.stringField("role") ?: return@mapIndexedNotNull null
                val text = (row["content"] as? JsonArray)
                    ?.mapNotNull { part ->
                        (part as? JsonObject)?.takeIf { it.stringField("type") == "text" }?.stringField("text")
                    }
                    ?.joinToString("\n")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                val date = (row["metadata"] as? JsonObject)?.stringField("created_at")
                when (role) {
                    "user" -> {
                        if (text.trimStart().startsWith("<system-reminder>")) return@mapIndexedNotNull null
                        UserMessage(
                            id = id,
                            contentRaw = JsonPrimitive(text),
                            date = date,
                            seqId = index + 1,
                        )
                    }
                    "assistant" -> AssistantMessage(
                        id = id,
                        contentRaw = JsonPrimitive(text),
                        date = date,
                        seqId = index + 1,
                    )
                    else -> null
                }
            }.toList()
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun JsonObject.intField(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()

    private fun base64Url(value: String): String = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    companion object {
        /**
         * Stable app-facing conversation id for a local agent's on-disk
         * "default" conversation. Every embedded session of an agent IS the
         * same underlying letta.js conversation, so the id must not vary
         * per app session (random suffixes fragment the timeline).
         */
        fun localConversationIdFor(agentId: String): String = "local-conv-$agentId"

        // letta.js customOpenAICompatibleModel default when model_settings
        // carries no context_window_limit.
        private const val DEFAULT_LOCAL_CONTEXT_WINDOW = 128_000
    }
}
