package com.letta.mobile.feature.chat.route

import kotlinx.serialization.Serializable

@Serializable
data class AgentChatRoute(
    val agentId: String,
    val agentName: String? = null,
    val conversationId: String? = null,
    val freshRouteKey: Long? = null,
    val initialMessage: String? = null,
    // letta-mobile-aw0dv: the chat display mode the route should OPEN in
    // ("simple" | "interactive" | "debug"). Null = use the screen default.
    // Subagent-chip navigation sets this to "interactive" so a subagent's
    // own conversation opens with full tool/turn detail rather than simple.
    val initialChatMode: String? = null,
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
    const val ACTIVE_AGENTS = "active_agents"
    const val PROJECT_BRIEF = "project_brief"
    const val BUG_REPORT = "bug_report"
}
