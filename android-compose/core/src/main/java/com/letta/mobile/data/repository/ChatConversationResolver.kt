package com.letta.mobile.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves conversation selection for chat routes based on route arguments,
 * saved state, and active conversation tracking.
 *
 * Encapsulates the conversation resolution precedence logic previously embedded
 * in AdminChatViewModel initialization.
 */
@Singleton
class ChatConversationResolver @Inject constructor(
    private val conversationManager: ConversationManager,
) {
    /**
     * Conversation resolution result states.
     */
    sealed interface ResolutionState {
        /** Conversation resolved and ready to use */
        data class Ready(val conversationId: String) : ResolutionState

        /** Fresh conversation path - no existing conversation selected */
        data object NoConversation : ResolutionState

        /** Resolution failed with an error */
        data class Error(val message: String) : ResolutionState
    }

    /**
     * Resolves which conversation to use based on route arguments and current state.
     *
     * Resolution precedence for client mode:
     * 1. Explicit route conversationId (if non-blank)
     * 2. Saved client mode conversationId (from SavedStateHandle)
     * 3. Fresh route intent (freshRouteKey != null or conversationId.isBlank())
     * 4. Resolve most recent conversation (with 30s cache tolerance)
     *
     * Resolution precedence for non-client mode:
     * 1. Explicit route conversationId (also sets as active)
     * 2. Active conversation for agent
     * 3. Resolve most recent conversation (with 30s cache tolerance)
     *
     * @param agentId The agent ID to resolve conversation for
     * @param routeConversationId The conversationId from route arguments (nullable)
     * @param freshRouteKey The freshRouteKey from route arguments (nullable)
     * @param isClientMode Whether client mode is active for this route
     * @param savedClientModeConversationId Saved conversationId from SavedStateHandle (client mode only)
     * @param activeConversationId Current active conversationId for agent (non-client mode only)
     * @param maxAgeMs Maximum cache age for conversation list refresh (default 30s)
     * @return The resolved conversation state
     */
    suspend fun resolve(
        agentId: String,
        routeConversationId: String?,
        freshRouteKey: Long?,
        isClientMode: Boolean,
        savedClientModeConversationId: String? = null,
        activeConversationId: String? = null,
        maxAgeMs: Long = 30_000L,
    ): ResolutionState {
        val explicitConversationId = routeConversationId?.takeIf { it.isNotBlank() }
        val isFreshRoute = freshRouteKey != null || routeConversationId?.isBlank() == true

        return if (isClientMode) {
            resolveClientMode(
                agentId = agentId,
                explicitConversationId = explicitConversationId,
                isFreshRoute = isFreshRoute,
                savedClientModeConversationId = savedClientModeConversationId,
                maxAgeMs = maxAgeMs,
            )
        } else {
            resolveNonClientMode(
                agentId = agentId,
                explicitConversationId = explicitConversationId,
                activeConversationId = activeConversationId,
                maxAgeMs = maxAgeMs,
            )
        }
    }

    /**
     * Client mode resolution path.
     *
     * Precedence:
     * 1. Explicit route arg
     * 2. Saved conversation ID (persists across config changes)
     * 3. Fresh route → NoConversation (in-memory path)
     * 4. Resolve most recent
     */
    private suspend fun resolveClientMode(
        agentId: String,
        explicitConversationId: String?,
        isFreshRoute: Boolean,
        savedClientModeConversationId: String?,
        maxAgeMs: Long,
    ): ResolutionState {
        return when {
            // 1. Explicit route arg
            explicitConversationId != null -> {
                ResolutionState.Ready(explicitConversationId)
            }

            // 2. Saved conversation ID
            savedClientModeConversationId != null -> {
                ResolutionState.Ready(savedClientModeConversationId)
            }

            // 3. Fresh route
            isFreshRoute -> {
                ResolutionState.NoConversation
            }

            // 4. Resolve most recent
            else -> {
                val resolved = conversationManager.resolveAndSetActiveConversation(
                    agentId = agentId,
                    maxAgeMs = maxAgeMs,
                )
                if (resolved != null) {
                    ResolutionState.Ready(resolved)
                } else {
                    ResolutionState.NoConversation
                }
            }
        }
    }

    /**
     * Non-client mode resolution path.
     *
     * Precedence:
     * 1. Explicit route arg (also sets as active)
     * 2. Active conversation
     * 3. Resolve most recent
     */
    private suspend fun resolveNonClientMode(
        agentId: String,
        explicitConversationId: String?,
        activeConversationId: String?,
        maxAgeMs: Long,
    ): ResolutionState {
        return when {
            // 1. Explicit route arg (also sets as active)
            explicitConversationId != null -> {
                conversationManager.setActiveConversation(agentId, explicitConversationId)
                ResolutionState.Ready(explicitConversationId)
            }

            // 2. Active conversation
            activeConversationId != null -> {
                ResolutionState.Ready(activeConversationId)
            }

            // 3. Resolve most recent
            else -> {
                val resolved = conversationManager.resolveAndSetActiveConversation(
                    agentId = agentId,
                    maxAgeMs = maxAgeMs,
                )
                if (resolved != null) {
                    ResolutionState.Ready(resolved)
                } else {
                    ResolutionState.NoConversation
                }
            }
        }
    }
}
