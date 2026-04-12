package com.letta.mobile.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object ConversationsRoute
@Serializable data object AgentListRoute
@Serializable data object ConfigRoute
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

@Serializable
data class McpServerToolsRoute(val serverId: String)

@Serializable
data class AgentChatRoute(
    val agentId: String,
    val conversationId: String? = null,
    val initialMessage: String? = null,
    val scrollToMessageId: String? = null,
)

@Serializable
data class EditAgentRoute(val agentId: String)

@Serializable
data class ToolDetailRoute(val toolId: String)

@Serializable
data class ArchivalRoute(val agentId: String)
