package com.letta.mobile.data.runtime

/**
 * Computes the scope key the App Server uses to index its per-conversation
 * working-directory map (`get_cwd_map` / `runtime_start.cwd`).
 *
 * Mirrors upstream `getWorkingDirectoryScopeKey` (letta-code
 * `src/websocket/listener/cwd.ts`) exactly, so a key computed client-side
 * matches what the server persisted:
 * - A real (non-"default") conversation id scopes by conversation alone:
 *   `conversation:<conversationId>`.
 * - The synthetic "default" conversation (used before a conversation has
 *   actually been created) scopes by agent instead, since many agents can
 *   share that placeholder id: `agent:<agentId>::conversation:default`.
 */
object WorkingDirectoryScopeKey {
    private const val UNKNOWN_AGENT = "__unknown__"

    fun of(agentId: String?, conversationId: String?): String {
        val normalizedConversationId = conversationId?.takeIf { it.isNotEmpty() } ?: "default"
        val normalizedAgentId = agentId?.takeIf { it.isNotEmpty() }
        return if (normalizedConversationId == "default") {
            "agent:${normalizedAgentId ?: UNKNOWN_AGENT}::conversation:default"
        } else {
            "conversation:$normalizedConversationId"
        }
    }
}
