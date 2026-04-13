package com.letta.mobile.bot.config

import com.letta.mobile.bot.core.ConversationMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for a single bot agent — Kotlin equivalent of lettabot's LettaBotConfig.
 *
 * Defines how a bot session should operate: which agent to use, how to route
 * conversations, which channels to enable, and (for local mode) which device
 * context providers to activate.
 */
@Serializable
data class BotConfig(
    /** Unique config ID for persistence. */
    val id: String,

    /** The Letta agent ID this bot manages. */
    @SerialName("agent_id") val agentId: String,

    /** Human-readable display name for this bot. */
    @SerialName("display_name") val displayName: String = "",

    /** Whether this bot runs locally or connects to a remote server. */
    val mode: Mode = Mode.LOCAL,

    /** For REMOTE mode: the bot server URL (e.g., http://192.168.1.100:3000). */
    @SerialName("remote_url") val remoteUrl: String? = null,

    /** For REMOTE mode: authentication token for the bot server API. */
    @SerialName("remote_token") val remoteToken: String? = null,

    /** How conversations are routed for incoming messages. */
    @SerialName("conversation_mode") val conversationMode: ConversationMode = ConversationMode.PER_CHAT,

    /** For SHARED conversation mode: the fixed conversation ID to use. */
    @SerialName("shared_conversation_id") val sharedConversationId: String? = null,

    /** Enabled channel IDs (e.g., "in_app", "notification"). */
    val channels: List<String> = listOf("in_app"),

    /** System-level message envelope template. See [MessageEnvelopeFormatter]. */
    @SerialName("envelope_template") val envelopeTemplate: String? = null,

    /** Whether to parse and execute directives in agent responses. */
    @SerialName("directives_enabled") val directivesEnabled: Boolean = true,

    /** Device context providers to activate in LOCAL mode. */
    @SerialName("context_providers") val contextProviders: List<String> = emptyList(),

    /** Whether the bot auto-starts when the app launches. */
    @SerialName("auto_start") val autoStart: Boolean = false,

    /** Whether this config is currently enabled. */
    val enabled: Boolean = true,
) {
    @Serializable
    enum class Mode {
        @SerialName("local") LOCAL,
        @SerialName("remote") REMOTE,
    }
}
