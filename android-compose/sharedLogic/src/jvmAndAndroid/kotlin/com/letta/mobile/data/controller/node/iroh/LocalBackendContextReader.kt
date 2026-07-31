package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * lgns8.9: on-disk `agent.context` reader — the native owner for the context
 * window overview mobile renders on the agent detail screen.
 *
 * Faithful port of admin-shim `server.ts:handleAgentContext`:
 *  - resolve the agent record (404 when unknown);
 *  - read the conversation's `system-prompt.json` sidecar, falling back to the
 *    agent record's `system` field, then to `""`;
 *  - fan the transcript out to the same wire messages `message.list` serves;
 *  - report admin-shim's ESTIMATED token counts. They are deliberately crude
 *    (`ceil(systemPrompt.length / 4)` and `messages * 50`) — this is a port of
 *    the numbers the mobile UI already displays, NOT a re-derivation. Changing
 *    the arithmetic would silently move every number on that screen.
 *
 * READ-ONLY by construction: no method here opens a file for writing.
 */
internal class LocalBackendContextReader(
    private val support: LocalBackendStoreSupport,
    private val messageReader: LocalBackendMessageReader,
) {

    /**
     * Port of `GET /v1/agents/{id}/context`. Returns null when the agent is
     * unknown or the store cannot be read, so the caller fails closed rather
     * than serving a hollow context window.
     */
    fun agentContextProjected(agentId: String, conversationId: String?): JsonObject? = runCatching {
        val agent = readAgentRecord(agentId) ?: return@runCatching null
        val transcript = messageReader.contextTranscript(conversationId, agentId) ?: return@runCatching null
        val systemPrompt = readSystemPrompt(transcript.conversationDir)
            ?: agent["system"]?.stringOrNull()
            ?: ""
        val messageCount = transcript.storedMessageCount
        val systemTokens = estimateTokens(systemPrompt)
        val messageTokens = messageCount * TOKENS_PER_MESSAGE

        buildJsonObject {
            put("context_window_size_current", systemTokens + messageTokens)
            put("context_window_size_max", CONTEXT_WINDOW_MAX)
            put("num_messages", messageCount)
            put("num_archival_memory", 0)
            put("num_recall_memory", messageCount)
            put("num_tokens_external_memory_summary", 0)
            put("num_tokens_system", systemTokens)
            put("num_tokens_core_memory", 0)
            put("num_tokens_summary_memory", 0)
            put("num_tokens_messages", messageTokens)
            put("num_tokens_functions_definitions", 0)
            put("num_tokens_memory_filesystem", 0)
            put("num_tokens_tool_usage_rules", 0)
            put("num_tokens_directories", 0)
            put("external_memory_summary", "")
            put("system_prompt", systemPrompt)
            put("core_memory", "")
            put("summary_memory", JsonNull)
            put("memory_filesystem", JsonNull)
            put("tool_usage_rules", JsonNull)
            put("directories", JsonArray(emptyList()))
            put("messages", transcript.projected)
            put("functions_definitions", JsonArray(emptyList()))
        }
    }.getOrNull()

    /** admin-shim's estimate: `Math.ceil(text.length / 4)`. */
    private fun estimateTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** Port of `store.ts:readSystemPrompt` — `<convDir>/system-prompt.json`, `{ content }`. */
    private fun readSystemPrompt(conversationDir: File): String? = runCatching {
        File(conversationDir, "system-prompt.json").takeIf { it.isFile }
            ?.readText()
            ?.let { support.json.parseToJsonElement(it).jsonObject }
            ?.get("content")
            ?.stringOrNull()
    }.getOrNull()

    private fun readAgentRecord(agentId: String): JsonObject? = runCatching {
        File(File(support.baseDir, "agents"), "$agentId.json").takeIf { it.isFile }
            ?.readText()
            ?.let { support.json.parseToJsonElement(it).jsonObject }
    }.getOrNull()

    private companion object {
        const val CONTEXT_WINDOW_MAX = 200_000
        const val TOKENS_PER_MESSAGE = 50
        const val CHARS_PER_TOKEN = 4
    }
}
