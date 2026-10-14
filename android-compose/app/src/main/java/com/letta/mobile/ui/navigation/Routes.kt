package com.letta.mobile.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object AdminRoute
@Serializable data object ConversationsRoute
@Serializable data object AgentListRoute
/** letta-mobile-ckmub.3.24: the shared agent editor for [agentId]; without one, the new-agent flow. */
@Serializable data class EditAgentRoute(val agentId: String? = null)
@Serializable data class ConfigRoute(
    /**
     * When true, the screen opens with an empty form and saves as a brand-new
     * config (fresh UUID) instead of overwriting the currently active one.
     * Used by the backend-switcher sheet's "+ Add server" affordance
     * (letta-mobile-cdlk). Defaults to false so every existing call site —
     * Settings nav, the post-clear bootstrap, etc. — keeps the original
     * "edit active config" behaviour.
     */
    val createNew: Boolean = false,
)
@Serializable data object ConfigListRoute
@Serializable data object TemplatesRoute
@Serializable data object ProvidersRoute
@Serializable data class SchedulesRoute(val agentId: String? = null)
@Serializable data object ChannelsRoute
@Serializable data object RunsRoute
@Serializable data object AboutRoute
@Serializable data object AllToolsRoute
/** letta-mobile-zxk2n.32: the shared Skills page; [agentId] is whose skills install / remove act on. */
@Serializable data class SkillsRoute(val agentId: String? = null)
@Serializable data class MemoryRoute(val agentId: String? = null)
@Serializable data class ShareToAgentRoute(val sharedText: String)
@Serializable data object ProjectsRoute
/** letta-mobile-cdgpr.9: the shared Plugins page (install with consent, enable, disable, remove). */
@Serializable data object PluginsRoute
@Serializable data object TelemetryRoute
@Serializable data object SystemAccessRoute
@Serializable data object PairingScanRoute
@Serializable data object PairingInviteRoute
@Serializable data object CanvasDebugRoute
@Serializable
data class CanvasRoute(
    val canvasId: String = "",
    val conversationId: String? = null,
    /** The conversation's agent, granted write access on a canvas this route creates. */
    val agentId: String? = null,
    /** The chat screen a share lands in; without one there is nowhere to share to. */
    val shareRecipient: String? = null,
)
