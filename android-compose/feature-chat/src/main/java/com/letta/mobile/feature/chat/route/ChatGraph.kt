package com.letta.mobile.feature.chat.route

import android.os.Bundle
import androidx.core.os.bundleOf

/** One chat view model per route: its agent, conversation, freshness and project. */
fun AgentChatRoute.toViewModelKey(): String =
    listOf(agentId, conversationId.orEmpty(), freshRouteKey?.toString().orEmpty(), projectIdentifier.orEmpty()).joinToString(":")

/**
 * letta-mobile-aw0dv: a subagent's registry conversation is almost always the bare "default", which the
 * host addresses as "conv-default-<agentId>"; the bare form would open a fresh conversation, not its transcript.
 */
internal fun subagentConversation(agentId: String, conversationId: String): String? =
    conversationId.takeIf { it.isNotBlank() }?.let { if (it == "default") "conv-default-$agentId" else it }

/** The route as the chat view model's arguments, as a navigation route would hand them over. */
fun AgentChatRoute.toArgs(): Bundle = bundleOf(
    "agentId" to agentId,
    "agentName" to agentName,
    "conversationId" to conversationId,
    "freshRouteKey" to freshRouteKey,
    "initialMessage" to initialMessage,
    "initialChatMode" to initialChatMode,
    "scrollToMessageId" to scrollToMessageId,
    "projectIdentifier" to projectIdentifier,
    "projectName" to projectName,
    "projectLettaFolderId" to projectLettaFolderId,
    "projectFilesystemPath" to projectFilesystemPath,
    "projectGitUrl" to projectGitUrl,
    "projectLastSyncAt" to projectLastSyncAt,
    "projectActiveCodingAgents" to projectActiveCodingAgents,
)
