package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.runtime.SharedChatSessionResolver
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Android-side adapter over the platform-neutral [SharedChatSessionResolver].
 *
 * The conversation-lifecycle resolution itself (agent-name lookup, most-recent
 * conversation selection, stale-cache refresh) now lives in sharedLogic so
 * desktop no longer needs its own copy. This class only owns the Android wiring
 * (the [CoroutineScope] for background refresh comes from the ViewModel) and
 * delegates every decision to the shared resolver — there is intentionally no
 * Android-specific behavior here.
 */
internal class ChatSessionResolver(
    agentRepository: IAgentRepository,
    conversationRepository: IConversationRepository,
    backgroundRefreshScope: CoroutineScope? = null,
) {
    private val delegate = SharedChatSessionResolver(
        agentRepository = agentRepository,
        conversationRepository = conversationRepository,
        backgroundRefreshScope = backgroundRefreshScope,
    )

    fun cachedAgentName(agentId: String): String? = delegate.cachedAgentName(agentId)

    fun observeCachedAgentName(agentId: String): Flow<String> =
        delegate.observeCachedAgentName(agentId)

    suspend fun resolveMostRecentConversation(
        agentId: String,
        maxAgeMs: Long,
    ): String? = delegate.resolveMostRecentConversation(agentId, maxAgeMs)
}
