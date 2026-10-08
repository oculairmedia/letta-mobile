package com.letta.mobile.data.chat.branch

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-bzvro.15: the host's `conversation.fork` admin RPC, as clients call it and the
 * host's handler reads it. Params: `conversation_id` (also carried by the path), and the
 * optional `agent_id` and `message_id` (fork through it, inclusive).
 */
object IrohConversationForkRpc {
    const val METHOD = "conversation.fork"
    const val CONVERSATION_ID = "conversation_id"
    const val AGENT_ID = "agent_id"
    const val MESSAGE_ID = "message_id"

    fun path(conversationId: String): String = "/v1/conversations/$conversationId/fork"

    fun params(request: ConversationForkRequest): JsonObject = buildJsonObject {
        put(CONVERSATION_ID, request.conversationId)
        request.agentId?.takeIf { it.isNotBlank() }?.let { put(AGENT_ID, it) }
        request.throughMessageId?.takeIf { it.isNotBlank() }?.let { put(MESSAGE_ID, it) }
    }
}
