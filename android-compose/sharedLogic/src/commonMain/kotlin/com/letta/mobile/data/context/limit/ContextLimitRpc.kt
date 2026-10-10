package com.letta.mobile.data.context.limit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-joigh: the `conversation.context_limit` admin_rpc contract shared by the Iroh host
 * that serves it and the clients that call it.
 *
 * Params: `agent_id` (required), `conversation_id` (optional, the agent's `default` when absent),
 * `tokens` (required, a positive token count). The host runs letta-code's own `/context-limit
 * <tokens>` (`execute_command context-limit`), so letta-code validates the value against the
 * model's catalog window and its 30,000-token floor, and picks the scope itself: on `default` it
 * updates the agent (`context_window_limit`, which the local backend stores as
 * `model_settings.context_window_limit`), on any other conversation it sets that conversation's
 * own `context_window_limit`, which wins over the agent's.
 */
object ContextLimitRpc {
    const val METHOD: String = "conversation.context_limit"
    const val AGENT_ID: String = "agent_id"
    const val CONVERSATION_ID: String = "conversation_id"
    const val TOKENS: String = "tokens"

    /** letta-code's slash command (`/set-max-context` is its alias). */
    const val COMMAND_ID: String = "context-limit"
}

/** Where letta-code applied a limit: `applySetMaxContext`'s `appliedTo`. */
enum class ContextLimitScope(val wire: String) {
    /** The agent's limit: its default conversation and every conversation without its own limit. */
    Agent("agent"),

    /** This conversation's own limit; the agent's is unchanged. */
    Conversation("conversation"),
    ;

    companion object {
        fun fromWire(value: String?): ContextLimitScope? = entries.firstOrNull { it.wire == value }

        /** letta-code's rule: the bare `default` conversation means the agent. */
        fun forConversation(isDefault: Boolean): ContextLimitScope = if (isDefault) Agent else Conversation
    }
}

/** What `/context-limit` set. [contextWindow] is the applied limit in tokens. */
@Serializable
data class ContextLimitResult(
    @SerialName("context_window") val contextWindow: Int,
    @SerialName("applied_to") val appliedTo: String? = null,
    /** `execute_command`'s text output, kept for display. */
    val output: String? = null,
) {
    val scope: ContextLimitScope? get() = ContextLimitScope.fromWire(appliedTo)
}

/**
 * Reads letta-code's `/context-limit` output (`formatSetMaxContextResult`): "Agent max context set
 * to 128,000 tokens." or "Current conversation max context set to 1,000,000 tokens[ with
 * override]." (a reset says "reset to"). [requested] stands in when the figure cannot be read.
 */
object ContextLimitCommandOutput {
    private val applied = Regex("""max context (?:set|reset) to ([\d,]+) tokens""")
    private const val AGENT_PREFIX = "Agent "
    private const val CONVERSATION_PREFIX = "Current conversation "

    fun parse(output: String?, requested: Int): ContextLimitResult {
        val text = output.orEmpty().trim()
        val tokens = applied.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val scope = when {
            text.startsWith(AGENT_PREFIX) -> ContextLimitScope.Agent
            text.startsWith(CONVERSATION_PREFIX) -> ContextLimitScope.Conversation
            else -> null
        }
        return ContextLimitResult(contextWindow = tokens ?: requested, appliedTo = scope?.wire, output = output)
    }
}
