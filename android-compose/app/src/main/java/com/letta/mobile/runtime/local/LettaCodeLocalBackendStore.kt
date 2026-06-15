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
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
     * The agent's stored model handle from its letta.js record. The
     * controller passes this back as --model on session start: letta.js
     * overwrites the agent's model with whatever --model says
     * (updateAgentLLMConfig on resume), so anything other than the stored
     * value would clobber per-agent model selection.
     */
    fun storedModelHandle(agentId: String): String? {
        val recordFile = File(File(storageDirectory, "agents"), "${base64Url(agentId)}.json")
        if (!recordFile.isFile) return null
        val record = runCatching { json.parseToJsonElement(recordFile.readText()).jsonObject }.getOrNull()
        return record?.stringField("model")?.takeIf { it.isNotBlank() }
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
            lines.filter { it.isNotBlank() }.flatMapIndexed { index, line ->
                val row = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@flatMapIndexed emptyList()
                val id = row.stringField("id") ?: return@flatMapIndexed emptyList()
                val role = row.stringField("role") ?: return@flatMapIndexed emptyList()
                val date = (row["metadata"] as? JsonObject)?.stringField("created_at")
                val contentParts = (row["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
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
     * Rewrites letta.js's persisted transcript so historical image payloads do
     * not bloat context replay. This runs only after a turn has been submitted
     * to letta.js, so live in-flight model input still receives the full image.
     */
    suspend fun stripPersistedImagePayloads(agentId: String) = withContext(Dispatchers.IO) {
        val transcript = File(
            File(File(storageDirectory, "conversations"), base64Url("default:$agentId")),
            "messages.jsonl",
        )
        if (!transcript.isFile) return@withContext
        val lines = transcript.readLines()
        val rows = lines.map { line ->
            if (line.isBlank()) null else runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        }
        val latestImageUserIndex = rows.indexOfLast { row -> row?.isUserImageMessage() == true }
        var changed = false
        val stripped = lines.mapIndexed { index, line ->
            val row = rows.getOrNull(index) ?: return@mapIndexed line
            if (index == latestImageUserIndex) return@mapIndexed line
            val scrubbed = row.stripImagePayloads()
            if (scrubbed !== row) {
                changed = true
                json.encodeToString(JsonObject.serializer(), scrubbed)
            } else {
                line
            }
        }
        if (changed) {
            transcript.writeText(stripped.joinToString("\n") + "\n")
        }
    }

    private fun JsonObject.isUserImageMessage(): Boolean =
        stringField("role") == "user" && (this["content"]?.hasImagePayload() == true)

    private fun JsonElement.hasImagePayload(): Boolean = when (this) {
        is JsonArray -> any { it.hasImagePayload() }
        is JsonObject -> {
            when {
                stringField("type") == "image" && (
                    stringField("data")?.let { looksLikeBase64Payload(it) } == true ||
                        ((this["source"] as? JsonObject)?.stringField("type") == "base64" &&
                            (this["source"] as? JsonObject)?.stringField("data")?.let { looksLikeBase64Payload(it) } == true)
                    ) -> true
                stringField("type") == "image_url" &&
                    parseImageDataUrl(((this["image_url"] as? JsonObject)?.stringField("url")) ?: stringField("url")) != null -> true
                stringField("type") == "text" &&
                    stringField("text")?.contains("data:image/", ignoreCase = true) == true -> true
                else -> values.any { it.hasImagePayload() }
            }
        }
        is JsonPrimitive -> contentOrNull?.contains("data:image/", ignoreCase = true) == true
        else -> false
    }

    private fun JsonObject.stripImagePayloads(): JsonObject {
        var changed = false
        val scrubbed = buildJsonObject {
            this@stripImagePayloads.forEach { (key, value) ->
                val replacement = when (key) {
                    "content" -> value.stripContentPayloads()
                    else -> value
                }
                if (replacement !== value) changed = true
                put(key, replacement)
            }
        }
        return if (changed) scrubbed else this
    }

    private fun JsonElement.stripContentPayloads(): JsonElement = when (this) {
        is JsonArray -> {
            var changed = false
            val scrubbed = JsonArray(map { part ->
                val replacement = (part as? JsonObject)?.stripImagePart() ?: part
                if (replacement !== part) changed = true
                replacement
            })
            if (changed) scrubbed else this
        }
        is JsonPrimitive -> {
            val text = contentOrNull ?: return this
            val stripped = replaceDataImageUrls(text)
            if (stripped == text) this else JsonPrimitive(stripped)
        }
        else -> this
    }

    private fun JsonObject.stripImagePart(): JsonElement {
        if (stringField("type") == "text") {
            val text = stringField("text") ?: return this
            val stripped = replaceDataImageUrls(text)
            if (stripped != text) {
                return buildJsonObject {
                    this@stripImagePart.forEach { (key, value) ->
                        put(key, if (key == "text") JsonPrimitive(stripped) else value)
                    }
                }
            }
            return this
        }
        if (stringField("type") == "image") {
            stringField("data")?.takeIf { looksLikeBase64Payload(it) }?.let { data ->
                return imagePlaceholderTextPart(
                    mediaType = stringField("mimeType") ?: stringField("media_type"),
                    base64 = data,
                )
            }
            val source = this["source"] as? JsonObject
            if (source?.stringField("type") == "base64") {
                val data = source.stringField("data")
                if (data != null && looksLikeBase64Payload(data)) {
                    return imagePlaceholderTextPart(mediaType = source.stringField("media_type"), base64 = data)
                }
            }
        }
        if (stringField("type") == "image_url") {
            val url = ((this["image_url"] as? JsonObject)?.stringField("url")) ?: stringField("url")
            val dataUrl = parseImageDataUrl(url)
            if (dataUrl != null) return imagePlaceholderTextPart(dataUrl.mediaType, dataUrl.base64)
        }
        return this
    }

    private fun imagePlaceholderTextPart(mediaType: String?, base64: String): JsonObject {
        val metadata = omittedImageMetadata(mediaType, base64)
        val summary = "[image omitted from persisted history: " +
            "${metadata.mediaType}, approx ${metadata.approxBytes} bytes, sha256=${metadata.sha256Prefix}]"
        return buildJsonObject {
            put("type", "text")
            put("text", summary)
            put("omitted_image", buildJsonObject {
                put("omitted", true)
                put("media_type", metadata.mediaType)
                put("approx_bytes", metadata.approxBytes)
                put("sha256", metadata.sha256Prefix)
                put("base64_chars", base64.length)
            })
        }
    }

    private data class OmittedImageMetadata(
        val mediaType: String,
        val approxBytes: Int,
        val sha256Prefix: String,
    )

    private data class ImageDataUrl(val mediaType: String, val base64: String)

    private fun omittedImageMetadata(mediaType: String?, base64: String): OmittedImageMetadata = OmittedImageMetadata(
        mediaType = mediaType?.takeIf { it.isNotBlank() } ?: "image/unknown",
        approxBytes = approximateBase64Bytes(base64),
        sha256Prefix = sha256Prefix(base64),
    )

    private fun parseImageDataUrl(value: String?): ImageDataUrl? {
        val text = value ?: return null
        if (!text.startsWith("data:image/", ignoreCase = true)) return null
        val separator = ";base64,"
        val separatorIndex = text.indexOf(separator, ignoreCase = true)
        if (separatorIndex <= "data:".length) return null
        val mediaType = text.substring("data:".length, separatorIndex)
        val base64 = text.substring(separatorIndex + separator.length)
        if (base64.isBlank() || !looksLikeBase64Payload(base64)) return null
        return ImageDataUrl(mediaType, base64)
    }

    private fun replaceDataImageUrls(text: String): String {
        var cursor = 0
        val out = StringBuilder()
        var changed = false
        while (cursor < text.length) {
            val start = text.indexOf("data:image/", cursor, ignoreCase = true)
            if (start < 0) {
                out.append(text.substring(cursor))
                break
            }
            val marker = text.indexOf(";base64,", start, ignoreCase = true)
            if (marker < 0) {
                out.append(text.substring(cursor))
                break
            }
            var end = marker + ";base64,".length
            while (end < text.length && isBase64Char(text[end])) end++
            val parsed = parseImageDataUrl(text.substring(start, end))
            if (parsed == null) {
                out.append(text.substring(cursor, end))
                cursor = end
                continue
            }
            val metadata = omittedImageMetadata(parsed.mediaType, parsed.base64)
            out.append(text.substring(cursor, start))
            out.append("[image omitted from persisted history: ")
            out.append(metadata.mediaType)
            out.append(", approx ")
            out.append(metadata.approxBytes)
            out.append(" bytes, sha256=")
            out.append(metadata.sha256Prefix)
            out.append("]")
            cursor = end
            changed = true
        }
        return if (changed) out.toString() else text
    }

    private fun looksLikeBase64Payload(value: String): Boolean = value.isNotBlank() && value.all(::isBase64Char)

    private fun isBase64Char(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char == '+' || char == '/' || char == '=' || char == '-' || char == '_'

    private fun approximateBase64Bytes(base64: String): Int {
        val cleanLength = base64.count { it != '\n' && it != '\r' && it != ' ' && it != '\t' }
        val padding = base64.takeLast(2).count { it == '=' }
        return ((cleanLength * 3) / 4 - padding).coerceAtLeast(0)
    }

    private fun sha256Prefix(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

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

/** A persisted pi-ai toolResult row (see [LettaCodeLocalBackendStore.readToolResults]). */
data class StoredToolResult(
    val toolCallId: String,
    val body: String,
    val isError: Boolean,
)
