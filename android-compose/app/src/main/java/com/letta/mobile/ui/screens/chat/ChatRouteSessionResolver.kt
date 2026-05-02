package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.repository.ConversationManager
import javax.inject.Inject

data class ChatRouteState(
    val explicitConversationId: String?,
    val isFreshRoute: Boolean,
)

data class ChatConversationResolveRequest(
    val agentId: String,
    val routeState: ChatRouteState,
    val clientModeEnabled: Boolean,
    val activeConversationId: String?,
    val savedClientModeConversationId: String?,
    val maxConversationAgeMs: Long,
)

sealed interface ChatConversationResolution {
    data class Ready(val conversationId: String) : ChatConversationResolution

    data object FreshConversation : ChatConversationResolution

    data object NoConversation : ChatConversationResolution
}

class ChatRouteSessionResolver @Inject constructor(
    private val conversationManager: ConversationManager,
) {
    fun routeState(
        requestedConversationArg: String?,
        freshRouteKey: Long?,
    ): ChatRouteState = ChatRouteState(
        explicitConversationId = requestedConversationArg?.takeIf { it.isNotBlank() },
        isFreshRoute = freshRouteKey != null || requestedConversationArg?.isBlank() == true,
    )

    suspend fun resolve(request: ChatConversationResolveRequest): ChatConversationResolution {
        val explicitConversationId = request.routeState.explicitConversationId
        if (request.clientModeEnabled) {
            explicitConversationId?.let { return ChatConversationResolution.Ready(it) }
            request.savedClientModeConversationId?.let {
                return ChatConversationResolution.Ready(it)
            }
            if (request.routeState.isFreshRoute) return ChatConversationResolution.FreshConversation
            val resolvedConversationId = conversationManager.resolveAndSetActiveConversation(
                agentId = request.agentId,
                maxAgeMs = request.maxConversationAgeMs,
            )
            return resolvedConversationId
                ?.let { ChatConversationResolution.Ready(it) }
                ?: ChatConversationResolution.NoConversation
        }

        explicitConversationId?.let {
            conversationManager.setActiveConversation(request.agentId, it)
            return ChatConversationResolution.Ready(it)
        }
        request.activeConversationId?.let {
            conversationManager.setActiveConversation(request.agentId, it)
            return ChatConversationResolution.Ready(it)
        }
        val resolvedConversationId = conversationManager.resolveAndSetActiveConversation(
            agentId = request.agentId,
            maxAgeMs = request.maxConversationAgeMs,
        )
        return resolvedConversationId
            ?.let { ChatConversationResolution.Ready(it) }
            ?: ChatConversationResolution.NoConversation
    }
}
