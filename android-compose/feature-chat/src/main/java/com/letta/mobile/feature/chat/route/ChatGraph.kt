package com.letta.mobile.feature.chat.route

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.IdFormats
import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.ui.chat.render.ProjectChatContext
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
    conversationId.takeIf { it.isNotBlank() }?.let {
        if (it == IdFormats.DEFAULT_CONVERSATION_ID) IdFormats.defaultConversationIdFor(agentId) else it
    }

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

/** Project metadata is a route payload, independent of current focus. */
internal fun SavedStateHandle.projectChatContext(): ProjectChatContext? = get<String>("projectIdentifier")?.let { identifier ->
    ProjectChatContext(
        identifier = identifier,
        name = get<String>("projectName") ?: identifier,
        lettaFolderId = get<String>("projectLettaFolderId"),
        filesystemPath = get<String>("projectFilesystemPath"),
        gitUrl = get<String>("projectGitUrl"),
        lastSyncAt = get<String>("projectLastSyncAt"),
        activeCodingAgents = get<String>("projectActiveCodingAgents"),
    )
}

/** Bind repository creation to the Android owner's request before suspending. */
internal fun ChatRouteArgs.focusedCreation(
    repository: com.letta.mobile.data.repository.api.IConversationRepository,
): com.letta.mobile.data.repository.api.IConversationRepository =
    object : com.letta.mobile.data.repository.api.IConversationRepository by repository {
        override suspend fun createConversation(agentId: AgentId, summary: String?): com.letta.mobile.data.model.Conversation {
            val token = requestToken()
            val captured = session?.conversationRepository ?: repository
            require(agentId.value == this@focusedCreation.agentId) { "Creation names another agent" }
            if (!accepts(token)) throw kotlinx.coroutines.CancellationException("Chat focus superseded")
            val created = captured.createConversation(agentId, summary)
            if (!accepts(token)) throw kotlinx.coroutines.CancellationException("Chat focus superseded")
            require(created.agentId == agentId) { "Conversation owner differs from the requested agent" }
            if (token != null && token.mode != com.letta.mobile.data.shell.CurrentFocusChatMode.Exact && !complete(token, created.id)) {
                throw kotlinx.coroutines.CancellationException("Chat focus superseded")
            }
            return created
        }
        override suspend fun forkConversation(
            id: ConversationId, agentId: AgentId, throughMessageId: String?,
        ): com.letta.mobile.data.model.Conversation {
            val token = requestToken()
            val captured = session?.conversationRepository ?: repository
            require(agentId.value == this@focusedCreation.agentId) { "Fork names another agent" }
            if (!accepts(token)) throw kotlinx.coroutines.CancellationException("Chat focus superseded")
            val fork = captured.forkConversation(id, agentId, throughMessageId)
            if (!accepts(token)) throw kotlinx.coroutines.CancellationException("Chat focus superseded")
            require(fork.agentId == agentId) { "Fork owner differs from the requested agent" }
            return fork
        }
    }
