package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Base64
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
    private val conversationHealer = LocalConversationHealer()

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
     * to accept --agent <id> turns. Existing agent records keep their
     * non-model fields, but the selected embedded model is authoritative and
     * must update the record before a restarted node resumes the agent.
     */
    suspend fun seedAgent(agentId: String, modelHandle: String) = withContext(Dispatchers.IO) {
        val agentFile = File(File(storageDirectory, "agents").apply { mkdirs() }, "${base64Url(agentId)}.json")
        val existing = agentFile.takeIf { it.isFile }
            ?.let { runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
        val record = buildJsonObject {
            existing?.forEach { (key, value) -> put(key, value) }
            put("id", agentId)
            put("name", existing?.stringField("name") ?: "Letta Mobile")
            put("model", modelHandle)
        }
        agentFile.writeText(json.encodeToString(JsonObject.serializer(), record))

        val conversationDirectory =
            File(File(storageDirectory, "conversations"), base64Url("default:$agentId")).apply { mkdirs() }
        val conversationFile = File(conversationDirectory, "conversation.json")
        if (!conversationFile.isFile) {
            val now = java.time.Instant.now().toString()
            val record = buildJsonObject {
                put("id", "default")
                put("agent_id", agentId)
                put("archived", false)
                put("archived_at", null as String?)
                put("created_at", now)
                put("updated_at", now)
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
        directories.mapNotNull { directory -> readDefaultConversation(directory) }
            .sortedByDescending { it.lastMessageAt ?: it.updatedAt ?: it.createdAt ?: "" }
    }

    override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation = withContext(Dispatchers.IO) {
        val agentRecord = File(File(storageDirectory, "agents"), "${base64Url(agentId.value)}.json")
        if (!agentRecord.isFile) {
            throw IllegalStateException("Local agent ${agentId.value} is not persisted in the embedded LettaCode store")
        }

        val conversationDirectory = File(
            File(storageDirectory, "conversations"),
            base64Url("default:${agentId.value}"),
        ).apply { mkdirs() }
        val conversationFile = File(conversationDirectory, "conversation.json")
        if (!conversationFile.isFile) {
            val now = java.time.Instant.now().toString()
            val record = buildJsonObject {
                put("id", "default")
                put("agent_id", agentId.value)
                put("archived", false)
                put("archived_at", null as String?)
                put("created_at", now)
                put("updated_at", now)
                put("last_message_at", null as String?)
                put("summary", summary)
                putJsonArray("in_context_message_ids") {}
            }
            conversationFile.writeText(json.encodeToString(JsonObject.serializer(), record))
        } else if (!summary.isNullOrBlank()) {
            val existing = runCatching { json.parseToJsonElement(conversationFile.readText()).jsonObject }.getOrNull()
                ?: throw IllegalStateException("Local conversation record for ${agentId.value} is unreadable")
            val updated = buildJsonObject {
                existing.forEach { (key, value) -> put(key, value) }
                put("summary", summary)
                put("updated_at", java.time.Instant.now().toString())
            }
            conversationFile.writeText(json.encodeToString(JsonObject.serializer(), updated))
        }

        readDefaultConversation(conversationDirectory)
            ?: throw IllegalStateException("Local conversation record for ${agentId.value} could not be created")
    }

    private fun readDefaultConversation(directory: File): Conversation? {
        val record = runCatching {
            json.parseToJsonElement(File(directory, "conversation.json").readText()).jsonObject
        }.getOrNull() ?: return null
        val diskId = record.stringField("id") ?: return null
        val agentId = record.stringField("agent_id") ?: return null
        if (diskId != "default") return null
        return Conversation(
            id = ConversationId(localConversationIdFor(agentId)),
            agentId = AgentId(agentId),
            summary = record.stringField("summary"),
            createdAt = record.stringField("created_at"),
            updatedAt = record.stringField("updated_at"),
            lastMessageAt = record.stringField("last_message_at"),
            archived = record["archived"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
        )
    }

    /**
     * The agent's stored model handle from its letta.js record. The
     * controller passes this back as --model on session start: letta.js
     * overwrites the agent's model with whatever --model says
     * (updateAgentLLMConfig on resume), so anything other than the stored
     * value would clobber per-agent model selection.
     */
    suspend fun storedModelHandle(agentId: String): String? = withContext(Dispatchers.IO) {
        val recordFile = File(File(storageDirectory, "agents"), "${base64Url(agentId)}.json")
        if (!recordFile.isFile) return@withContext null
        val record = runCatching { json.parseToJsonElement(recordFile.readText()).jsonObject }.getOrNull()
        record?.stringField("model")?.takeIf { it.isNotBlank() }
    }

    /**
     * Reads the agent's default-conversation transcript (messages.jsonl,
     * pi-ai local-message rows maintained by letta.js) as timeline messages,
     * so the chat screen can hydrate history across app restarts. Synthetic
     * context rows (system-reminder user messages letta.js injects) are
     * skipped — they were never user-visible.
     *
     * Resolves image_ref pointers back to normal image parts before typed
     * parsing (letta-mobile-xybm2 embedded image hydration). The typed model
     * never sees image_ref — it's a JSON-only persistence concept.
     */
    suspend fun readTranscript(agentId: String): List<LettaMessage> = withContext(Dispatchers.IO) {
        val conversationDir = File(File(storageDirectory, "conversations"), base64Url("default:$agentId"))
        val transcript = File(conversationDir, "messages.jsonl")
        if (!transcript.isFile) return@withContext emptyList()
        val blobStore = LocalImageBlobStore(conversationDir)
        transcript.useLines { lines ->
            lines.filter { it.isNotBlank() }.flatMapIndexed { index, line ->
                val row = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@flatMapIndexed emptyList()
                val id = row.stringField("id") ?: return@flatMapIndexed emptyList()
                val role = row.stringField("role") ?: return@flatMapIndexed emptyList()
                val date = (row["metadata"] as? JsonObject)?.stringField("created_at")
                // Resolve image_ref parts before typed parsing
                val rawContentParts = (row["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                val contentParts = rawContentParts.map { part -> resolveImageRef(part, blobStore) }
                val text = contentParts
                    .filter { it.stringField("type") == "text" }
                    .mapNotNull { it.stringField("text") }
                    .joinToString("\n")
                    .takeIf { it.isNotBlank() }
                when (role) {
                    "user" -> {
                        if (text == null || text.trimStart().startsWith("<system-reminder>")) {
                            return@flatMapIndexed emptyList()
                        }
                        listOf(
                            UserMessage(
                                id = id,
                                contentRaw = JsonPrimitive(text),
                                date = date,
                                seqId = index + 1,
                            )
                        )
                    }
                    "assistant" -> buildList {
                        if (text != null) {
                            add(
                                AssistantMessage(
                                    id = id,
                                    contentRaw = JsonPrimitive(text),
                                    date = date,
                                    seqId = index + 1,
                                )
                            )
                        }
                        // pi-ai records tool invocations as toolCall content
                        // parts on the assistant row; surface them so history
                        // shows the same tool chips the live stream renders
                        // (letta-mobile-bm6x2).
                        contentParts
                            .filter { it.stringField("type") == "toolCall" }
                            .forEachIndexed { callIndex, part ->
                                val callId = part.stringField("id") ?: return@forEachIndexed
                                val name = part.stringField("name") ?: return@forEachIndexed
                                add(
                                    ToolCallMessage(
                                        id = "$id-tool-$callIndex",
                                        toolCall = ToolCall(
                                            id = callId,
                                            name = name,
                                            arguments = part["arguments"]?.toString(),
                                        ),
                                        date = date,
                                        seqId = index + 1,
                                    )
                                )
                            }
                    }
                    "toolResult" -> {
                        val callId = row.stringField("toolCallId") ?: return@flatMapIndexed emptyList()
                        listOf(
                            ToolReturnMessage(
                                id = id,
                                toolCallId = callId,
                                status = if (row["isError"]?.jsonPrimitive?.content == "true") "error" else "success",
                                toolReturnRaw = JsonPrimitive(text.orEmpty()),
                                date = date,
                                seqId = index + 1,
                            )
                        )
                    }
                    else -> emptyList()
                }
            }.toList()
        }
    }

    /**
     * Tool results letta.js has persisted for the agent's default
     * conversation, keyed by tool_call_id. The live stream announces tool
     * calls but never their returns, so the headless client attaches these
     * at turn end (letta-mobile-bm6x2).
     */
    suspend fun readToolResults(agentId: String): Map<String, StoredToolResult> = withContext(Dispatchers.IO) {
        val transcript = File(
            File(File(storageDirectory, "conversations"), base64Url("default:$agentId")),
            "messages.jsonl",
        )
        if (!transcript.isFile) return@withContext emptyMap()
        transcript.useLines { lines ->
            lines.filter { it.isNotBlank() }.mapNotNull { line ->
                val row = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                if (row.stringField("role") != "toolResult") return@mapNotNull null
                val callId = row.stringField("toolCallId") ?: return@mapNotNull null
                val body = (row["content"] as? JsonArray)
                    ?.mapNotNull { part ->
                        (part as? JsonObject)?.takeIf { it.stringField("type") == "text" }?.stringField("text")
                    }
                    ?.joinToString("\n")
                    .orEmpty()
                callId to StoredToolResult(
                    toolCallId = callId,
                    body = body,
                    isError = row["isError"]?.jsonPrimitive?.content == "true",
                )
            }.toMap()
        }
    }

    /**
     * Heals the agent's default-conversation transcript by settling any
     * dangling tool calls (an assistant toolCall part with no matching
     * toolResult row) — the on-device analogue of the shim's lcp-ezv healer.
     * Safe to call before every turn (belt) AND after an interrupted turn
     * (suspenders); idempotent and a no-op on a well-formed transcript.
     * Returns the heal report so callers can log what was settled.
     */
    suspend fun healDanglingToolCalls(agentId: String): LocalConversationHealer.HealReport =
        withContext(Dispatchers.IO) {
            val transcript = File(
                File(File(storageDirectory, "conversations"), base64Url("default:$agentId")),
                "messages.jsonl",
            )
            conversationHealer.healTranscript(transcript)
        }

    /**
     * Strips heavy base64 image data from the agent's persisted default
     * conversation, persisting bytes to the blob store and leaving an image_ref
     * pointer (or text placeholder as fallback). Prevents already-sent images
     * from re-bloating the context and re-sending every turn (letta-mobile-87itk).
     * Idempotent; safe to call pre-turn.
     */
    suspend fun stripPersistedImageData(agentId: String): LocalImageContextStripper.StripReport =
        withContext(Dispatchers.IO) {
            val conversationDir = File(File(storageDirectory, "conversations"), base64Url("default:$agentId"))
            val transcript = File(conversationDir, "messages.jsonl")
            val blobStore = LocalImageBlobStore(conversationDir)
            val stripper = LocalImageContextStripper(blobStore = blobStore)
            stripper.stripTranscript(transcript)
        }

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun JsonObject.intField(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()

    /**
     * Resolve an image_ref part back to a normal image part by loading bytes
     * from the blob store. Falls open to "[image unavailable]" text if the blob
     * is missing (never crashes, never produces empty image).
     */
    private fun resolveImageRef(part: JsonObject, blobStore: LocalImageBlobStore): JsonObject {
        // The on-disk rehydration pointer is a TEXT placeholder carrying an
        // "image_ref" metadata field (NOT a type:"image_ref" part — letta.js
        // replays messages.jsonl to the provider and would mangle an unknown
        // image part into data:undefined → strict-provider 2013). Detect the
        // text-part-with-image_ref and restore the original image for the UI.
        val ref = part["image_ref"]?.jsonPrimitive?.content ?: return part
        val mediaType = part["mediaType"]?.jsonPrimitive?.content ?: "image/jpeg"
        val bytes = blobStore.getBytes(ref) ?: return unavailableImagePlaceholder()
        val base64Data = Base64.getEncoder().encodeToString(bytes)
        // Reconstruct the flat image shape letta.js persists/reads on disk
        // ({type:image, mimeType, data}) so downstream typed mapping produces a
        // normal image attachment with no model/timeline changes.
        return buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("mimeType", JsonPrimitive(mediaType))
            put("data", JsonPrimitive(base64Data))
        }
    }

    private fun unavailableImagePlaceholder(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive("[image unavailable]"))
    }

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

/** A persisted pi-ai toolResult row (see [LettaCodeLocalBackendStore.readToolResults]). */
data class StoredToolResult(
    val toolCallId: String,
    val body: String,
    val isError: Boolean,
)
