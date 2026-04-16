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

    @SerialName("server_profile_id") val serverProfileId: String? = null,

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

    @SerialName("enabled_skills") val enabledSkills: List<String> = emptyList(),

    /** Whether the bot auto-starts when the app launches. */
    @SerialName("auto_start") val autoStart: Boolean = false,

    @SerialName("heartbeat_enabled") val heartbeatEnabled: Boolean = false,

    @SerialName("heartbeat_interval_minutes") val heartbeatIntervalMinutes: Long = 60,

    @SerialName("heartbeat_message") val heartbeatMessage: String = DEFAULT_HEARTBEAT_MESSAGE,

    @SerialName("heartbeat_requires_charging") val heartbeatRequiresCharging: Boolean = false,

    @SerialName("heartbeat_requires_unmetered_network") val heartbeatRequiresUnmeteredNetwork: Boolean = false,

    @SerialName("scheduled_jobs") val scheduledJobs: List<BotScheduledJob> = emptyList(),

    /** Whether this config is currently enabled. */
    val enabled: Boolean = true,

    @SerialName("api_server_enabled") val apiServerEnabled: Boolean = false,

    @SerialName("api_server_port") val apiServerPort: Int = 8080,

    @SerialName("api_server_token") val apiServerToken: String? = null,
) {
    @Serializable
    enum class Mode {
        @SerialName("local") LOCAL,
        @SerialName("remote") REMOTE,
    }

    companion object {
        const val DEFAULT_HEARTBEAT_MESSAGE = "Check for anything important that needs attention and stay silent if there is nothing worth surfacing."
        const val DEFAULT_SCHEDULE_STALE_GRACE_MINUTES = 120L
    }
}

@Serializable
data class BotScheduledJob(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
    val message: String,
    @SerialName("cron_expression") val cronExpression: String,
    val enabled: Boolean = true,
    @SerialName("requires_charging") val requiresCharging: Boolean = false,
    @SerialName("requires_unmetered_network") val requiresUnmeteredNetwork: Boolean = false,
    @SerialName("stale_grace_minutes") val staleGraceMinutes: Long = BotConfig.DEFAULT_SCHEDULE_STALE_GRACE_MINUTES,
)
