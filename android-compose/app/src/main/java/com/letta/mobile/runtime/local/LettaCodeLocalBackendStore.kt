package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
) : LocalRuntimeConversationSource {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val storageDirectory: File
        get() = File(context.filesDir, "embedded-lettacode/local-backend")

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

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

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
    }
}
