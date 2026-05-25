package com.letta.mobile.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object AdminRoute
@Serializable data object ConversationsRoute
@Serializable data object AgentListRoute
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
@Serializable data object ArchivesRoute
@Serializable data object FoldersRoute
@Serializable data object GroupsRoute
@Serializable data object ProvidersRoute
@Serializable data object BlocksRoute
@Serializable data object IdentitiesRoute
@Serializable data object SchedulesRoute
@Serializable data object RunsRoute
@Serializable data object JobsRoute
@Serializable data object MessageBatchesRoute
@Serializable data object McpRoute
@Serializable data object AboutRoute
@Serializable data object ModelsRoute
@Serializable data object AllToolsRoute
@Serializable data object UsageRoute
@Serializable data class ShareToAgentRoute(val sharedText: String)
@Serializable data object ProjectsRoute
@Serializable data object CreateProjectRoute
@Serializable data object TelemetryRoute
@Serializable data object SystemAccessRoute
@Serializable data object VibesyncDebugRoute
@Serializable
data class ProjectIssuesRoute(
    val projectId: String,
    val projectName: String? = null,
)

@Serializable
data class ProjectIssueDetailRoute(
    val projectId: String,
    val issueId: String,
    val projectName: String? = null,
)

@Serializable
data class McpServerToolsRoute(val serverId: String)

@Serializable
data class EditAgentRoute(val agentId: String)

@Serializable
data class ToolDetailRoute(val toolId: String)

@Serializable
data class ArchivalRoute(val agentId: String)
