// This file keeps its baselined name: platform-code-baseline.txt only shrinks,
// so "AgentChatRoute.kt" can never be given its own cap, and with the dead
// project-chat branch (ProjectChatStartAction) deleted, the single remaining
// declaration is AgentChatRoute — hence this targeted suppress.
@file:Suppress("MatchingDeclarationName")

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
)
