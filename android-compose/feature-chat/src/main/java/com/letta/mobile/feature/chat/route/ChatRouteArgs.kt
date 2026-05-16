package com.letta.mobile.feature.chat.route

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.feature.chat.ProjectChatContext
import javax.inject.Inject

internal class ChatRouteArgs @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) {
    val agentId: String = requireNotNull(savedStateHandle.get<String>(AGENT_ID_KEY)) {
        "Missing agentId in AdminChatViewModel navigation arguments"
    }

    val initialAgentName: String? = savedStateHandle.get<String>(AGENT_NAME_KEY)
        ?.takeIf { it.isNotBlank() }

    val initialMessage: String? = savedStateHandle.get<String>(INITIAL_MESSAGE_KEY)

    val requestedConversationArg: String? = savedStateHandle.get<String>(CONVERSATION_ID_KEY)

    val explicitConversationId: String?
        get() = requestedConversationArg?.takeIf { it.isNotBlank() }

    private val freshRouteKey: Long? = savedStateHandle.get<Long>(FRESH_ROUTE_KEY)

    val scrollToMessageId: String? = savedStateHandle.get<String>(SCROLL_TO_MESSAGE_ID_KEY)

    val isFreshRoute: Boolean
        get() = freshRouteKey != null || requestedConversationArg?.isBlank() == true

    val explicitNewChat: Boolean
        get() = freshRouteKey != null

    val projectContext: ProjectChatContext? =
        savedStateHandle.get<String>(PROJECT_IDENTIFIER_KEY)?.let { identifier ->
            ProjectChatContext(
                identifier = identifier,
                name = savedStateHandle.get<String>(PROJECT_NAME_KEY) ?: identifier,
                lettaFolderId = savedStateHandle.get<String>(PROJECT_LETTA_FOLDER_ID_KEY),
                filesystemPath = savedStateHandle.get<String>(PROJECT_FILESYSTEM_PATH_KEY),
                gitUrl = savedStateHandle.get<String>(PROJECT_GIT_URL_KEY),
                lastSyncAt = savedStateHandle.get<String>(PROJECT_LAST_SYNC_AT_KEY),
                activeCodingAgents = savedStateHandle.get<String>(PROJECT_ACTIVE_CODING_AGENTS_KEY),
            )
        }

    fun currentClientModeConversationId(): String? =
        savedStateHandle.get<String>(CLIENT_MODE_CONVERSATION_ID_KEY)?.takeIf { it.isNotBlank() }

    fun setClientModeConversationId(conversationId: String?) {
        savedStateHandle[CLIENT_MODE_CONVERSATION_ID_KEY] = conversationId?.takeIf { it.isNotBlank() }
    }

    fun setRouteConversationId(conversationId: String) {
        savedStateHandle[CONVERSATION_ID_KEY] = conversationId
    }

    internal fun savedStateHandle(): SavedStateHandle = savedStateHandle

    private companion object {
        const val AGENT_ID_KEY = "agentId"
        const val AGENT_NAME_KEY = "agentName"
        const val INITIAL_MESSAGE_KEY = "initialMessage"
        const val CONVERSATION_ID_KEY = "conversationId"
        const val FRESH_ROUTE_KEY = "freshRouteKey"
        const val SCROLL_TO_MESSAGE_ID_KEY = "scrollToMessageId"
        const val PROJECT_IDENTIFIER_KEY = "projectIdentifier"
        const val PROJECT_NAME_KEY = "projectName"
        const val PROJECT_LETTA_FOLDER_ID_KEY = "projectLettaFolderId"
        const val PROJECT_FILESYSTEM_PATH_KEY = "projectFilesystemPath"
        const val PROJECT_GIT_URL_KEY = "projectGitUrl"
        const val PROJECT_LAST_SYNC_AT_KEY = "projectLastSyncAt"
        const val PROJECT_ACTIVE_CODING_AGENTS_KEY = "projectActiveCodingAgents"
        const val CLIENT_MODE_CONVERSATION_ID_KEY = "clientModeConversationId"
    }
}
