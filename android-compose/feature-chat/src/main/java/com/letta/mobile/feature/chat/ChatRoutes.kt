package com.letta.mobile.feature.chat

import kotlinx.serialization.Serializable

@Serializable
data class AgentChatRoute(
    val agentId: String,
    val agentName: String? = null,
    val conversationId: String? = null,
    val freshRouteKey: Long? = null,
    val initialMessage: String? = null,
    val scrollToMessageId: String? = null,
    val projectIdentifier: String? = null,
    val projectName: String? = null,
    val projectLettaFolderId: String? = null,
    val projectFilesystemPath: String? = null,
    val projectGitUrl: String? = null,
    val projectLastSyncAt: String? = null,
    val projectActiveCodingAgents: String? = null,
    val projectStartAction: String? = null,
)

object ProjectChatStartAction {
    const val ActiveAgents = "active_agents"
    const val ProjectBrief = "project_brief"
    const val BugReport = "bug_report"
}
