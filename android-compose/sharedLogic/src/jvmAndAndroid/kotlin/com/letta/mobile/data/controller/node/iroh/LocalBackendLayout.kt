package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Base64

/**
 * letta-mobile-by4vm.21: the ONE description of the letta-code local-backend on-disk layout
 * (letta.js `src/backend/local/local-store.ts`), shared by the native admin-query readers and the
 * Android embedded runtime's store. A layout change upstream is fixed here, once.
 *
 *  - `agents/<base64url(agentId)>.json` (older stores and test fixtures also hold `agents/<id>.json`)
 *  - `conversations/<base64url(key)>/conversation.json` + `messages.jsonl` (+ `system-prompt.json`,
 *    `blobs/`, real-time / otid / attachment sidecars), where key is `default:<agentId>` for an
 *    agent's default conversation and `conversation:<id>` otherwise.
 *  - `messages.jsonl` rows are either legacy flat messages or session-log v3 envelopes
 *    (`{"type":"message","message":{…}}`), see [messageBody].
 */
object LocalBackendLayout {
    const val AGENTS_DIR: String = "agents"
    const val CONVERSATIONS_DIR: String = "conversations"
    const val CONVERSATION_FILE: String = "conversation.json"
    const val TRANSCRIPT_FILE: String = "messages.jsonl"
    const val SYSTEM_PROMPT_FILE: String = "system-prompt.json"
    const val DEFAULT_CONVERSATION_ID: String = IdFormats.DEFAULT_CONVERSATION_ID

    fun b64UrlEncode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun b64UrlDecode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    fun conversationKey(conversationId: ConversationId, agentId: AgentId): String =
        if (conversationId.value == DEFAULT_CONVERSATION_ID) {
            "$DEFAULT_CONVERSATION_ID:${agentId.value}"
        } else {
            "conversation:${conversationId.value}"
        }

    /** Where letta.js writes (and the embedded store seeds) an agent's record. */
    fun agentRecordFile(baseDir: File, agentId: AgentId): File =
        File(File(baseDir, AGENTS_DIR), "${b64UrlEncode(agentId.value)}.json")

    /** The agent's existing record: a bare `<id>.json` first, then the base64url name; null if neither exists. */
    fun findAgentRecordFile(baseDir: File, agentId: AgentId): File? =
        File(File(baseDir, AGENTS_DIR), "${agentId.value}.json").takeIf { it.isFile }
            ?: agentRecordFile(baseDir, agentId).takeIf { it.isFile }

    fun conversationDir(baseDir: File, conversationId: ConversationId, agentId: AgentId): File =
        File(File(baseDir, CONVERSATIONS_DIR), b64UrlEncode(conversationKey(conversationId, agentId)))

    fun defaultConversationDir(baseDir: File, agentId: AgentId): File =
        conversationDir(baseDir, ConversationId(DEFAULT_CONVERSATION_ID), agentId)

    /** True for a session-log v3 message envelope: `type == "message"` and `message` is an object. */
    fun isMessageEnvelope(row: JsonObject): Boolean =
        (row["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "message" && row["message"] is JsonObject

    /** The message body of a transcript row: the envelope's `message`, or the flat row itself. */
    fun messageBody(row: JsonObject): JsonObject =
        if (isMessageEnvelope(row)) row["message"] as JsonObject else row
}
