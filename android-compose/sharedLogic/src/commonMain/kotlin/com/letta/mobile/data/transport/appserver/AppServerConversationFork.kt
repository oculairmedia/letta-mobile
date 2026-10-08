package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * letta-mobile-bzvro.15 (F15): `conversation_fork` (letta-code 0.29.12+, `protocol_v2.ts`
 * `ConversationForkCommand`). Forks [conversationId] into a new conversation; the options sit
 * in [body], because a fork without them copies the whole conversation.
 *
 * Kept in its own file (not inside [AppServerCommand]) so the device-status, memory and
 * conversation PRs each add their commands without editing the same block.
 */
@Serializable
@SerialName(AppServerConversationFork.TYPE)
data class AppServerConversationFork(
    @SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String,
    val body: AppServerConversationForkBody? = null,
) : AppServerCommand {
    companion object {
        const val TYPE = "conversation_fork"
        const val RESPONSE_TYPE = "conversation_fork_response"
    }
}

/** `ConversationForkBody`: every field optional; omitted fields are not sent. */
@Serializable
data class AppServerConversationForkBody(
    /** The agent for agent-direct mode with the `default` conversation. */
    @SerialName("agent_id") val agentId: String? = null,
    /** Fork through this projected message id, inclusive; null forks the whole history. */
    @SerialName("message_id") val messageId: String? = null,
    /** Whether the fork is hidden from conversation lists. */
    val hidden: Boolean? = null,
)

/**
 * `conversation_fork_response`. Upstream answers with a reference ([conversationId]) only, so
 * callers retrieve the conversation afterwards when they need more than its id.
 *
 * The frame is not part of the typed [AppServerInboundFrame] set (that set is closed over by the
 * runtime event mapper); it arrives as [AppServerInboundFrame.Unknown], correlated by
 * `request_id`, and is read here from its raw envelope.
 */
data class AppServerConversationForkResponse(
    val requestId: String,
    val success: Boolean,
    val conversationId: String?,
    val error: String?,
) {
    companion object {
        /** The fork response inside [frame], or null when [frame] is something else. */
        fun from(frame: AppServerInboundFrame): AppServerConversationForkResponse? {
            val unknown = frame as? AppServerInboundFrame.Unknown ?: return null
            if (unknown.type != AppServerConversationFork.RESPONSE_TYPE) return null
            val raw = unknown.raw
            return AppServerConversationForkResponse(
                requestId = unknown.requestId.orEmpty(),
                success = (raw["success"] as? JsonPrimitive)?.contentOrNull == "true",
                conversationId = ((raw["conversation"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull,
                error = (raw["error"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}
