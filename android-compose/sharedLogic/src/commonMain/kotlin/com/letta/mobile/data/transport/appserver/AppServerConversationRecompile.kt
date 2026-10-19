package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 *  (G92): `conversation_recompile` (letta-code `protocol_v2.ts`
 * `ConversationRecompileCommand`). Recompiles the agent's system prompt for [conversationId] (its
 * memory blocks re-rendered into the prompt), the App Server side of `/recompile`.
 *
 * Kept in its own file, as [AppServerConversationFork] is, so other command PRs do not edit the
 * same block of [AppServerCommand].
 */
@Serializable
@SerialName(AppServerConversationRecompile.TYPE)
data class AppServerConversationRecompile(
    @SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String,
    val body: AppServerConversationRecompileBody? = null,
) : AppServerCommand {
    companion object {
        const val TYPE = "conversation_recompile"
        const val RESPONSE_TYPE = "conversation_recompile_response"
    }
}

/** `ConversationRecompileParams`: both fields optional; omitted fields are not sent. */
@Serializable
data class AppServerConversationRecompileBody(
    /** The agent, required with the `default` conversation (agent-direct mode). */
    @SerialName("agent_id") val agentId: String? = null,
    /** Compile without saving: the answer carries the prompt the agent would get. */
    @SerialName("dry_run") val dryRun: Boolean? = null,
)

/**
 * `conversation_recompile_response`: whether it worked, on failure the server's reason, and the
 * compiled system prompt ([result], letta-code's `result` string; null when it sent none). The Iroh
 * relay hands [result] back to the client for the inspector's preview.
 *
 * Like the fork response, the frame is outside the typed [AppServerInboundFrame] set and is read
 * from [AppServerInboundFrame.Unknown], correlated by `request_id`.
 */
data class AppServerConversationRecompileResponse(
    val requestId: String,
    val success: Boolean,
    val error: String?,
    val result: String? = null,
) {
    companion object {
        /** The recompile response inside [frame], or null when [frame] is something else. */
        fun from(frame: AppServerInboundFrame): AppServerConversationRecompileResponse? {
            val unknown = frame as? AppServerInboundFrame.Unknown ?: return null
            if (unknown.type != AppServerConversationRecompile.RESPONSE_TYPE) return null
            val raw = unknown.raw
            return AppServerConversationRecompileResponse(
                requestId = unknown.requestId.orEmpty(),
                success = (raw["success"] as? JsonPrimitive)?.contentOrNull == "true",
                error = (raw["error"] as? JsonPrimitive)?.contentOrNull,
                result = (raw["result"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}
