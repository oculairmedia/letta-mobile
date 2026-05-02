package com.letta.mobile.bot.config

import com.letta.mobile.bot.core.ConversationMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for a single bot **transport** — Kotlin equivalent of lettabot's LettaBotConfig.
 *
 * letta-mobile-w2hx.4: this used to bind one config to one specific agent
 * via `agentId`. That was a layer violation: the bot is a transport (HTTP
 * or WebSocket pipe to a lettabot/Letta backend), not an agent binder.
 * The agent identity travels per-message on `ChannelMessage.targetAgentId`
 * and is multiplexed on the wire by the per-agent session pool (.3).
 *
 * The only remaining agent-bound knob is [heartbeatAgentId], which exists
 * because heartbeat schedules need a deterministic target (no "current
 * chat" exists when WorkManager fires offline).
 */
@Serializable
data class BotConfig(
    /** Unique config ID for persistence. Sessions are keyed on this, not on any agent ID. */
    val id: String,

    /** Human-readable display name for this bot. */
    @SerialName("display_name") val displayName: String = "",

    /** Whether this bot runs locally or connects to a remote server. */
    val mode: Mode = Mode.LOCAL,

    /** For REMOTE mode: the bot server URL (e.g., http://192.168.1.100:3000). */
    @SerialName("remote_url") val remoteUrl: String? = null,

    /** For REMOTE mode: authentication token for the bot server API. */
    @SerialName("remote_token") val remoteToken: String? = null,

    val transport: Transport = Transport.HTTP,

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

    /**
     * Optional Letta agent ID that scheduled heartbeats should target.
     *
     * Unlike interactive chat, where the active chat row supplies the
     * target agent per-message, heartbeats fire from WorkManager when no
     * UI exists. They need a deterministic agent. `null` means heartbeat
     * is disabled even if `heartbeatEnabled=true` — guarded at the
     * scheduler.
     */
    @SerialName("heartbeat_agent_id") val heartbeatAgentId: String? = null,

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

    @Serializable
    enum class Transport {
        @SerialName("http") HTTP,
        @SerialName("ws") WS,
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
