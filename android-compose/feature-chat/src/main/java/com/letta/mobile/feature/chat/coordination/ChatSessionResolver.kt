package com.letta.mobile.feature.chat.coordination
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.chat.runtime.SharedChatSessionResolver
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.TimelineConversationAttributionCapture
import com.letta.mobile.data.timeline.TimelineConversationSelectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
/** Android lifecycle adapter to the shared session resolver and focus owner. */
internal class ChatSessionResolver(
    agentRepository: IAgentRepository,
    private val conversationRepository: IConversationRepository,
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
    /** Bind Android loading to the shared owner's resume policy, never the resolver's fallback. */
    suspend fun resolveFocus(
        owner: com.letta.mobile.data.shell.ShellWorkspaceState,
        request: com.letta.mobile.data.shell.CurrentFocusRequest,
        maxAge: kotlin.time.Duration,
        repository: IConversationRepository? = null,
    ): com.letta.mobile.data.model.ConversationId? {
        val conversations = repository ?: conversationRepository
        if (!owner.accepts(request)) return null
        val agent = request.requestedAgent ?: return null
        return when (request.mode) {
            com.letta.mobile.data.shell.CurrentFocusChatMode.Exact -> request.exactConversation
            com.letta.mobile.data.shell.CurrentFocusChatMode.New -> null
            com.letta.mobile.data.shell.CurrentFocusChatMode.Resume -> {
                conversations.refreshConversationsIfStale(agent, maxAge.inWholeMilliseconds)
                if (!owner.accepts(request)) return null
                owner.resolveAgentChat(agent, conversations.getCachedConversations(agent).filter { it.agentId == agent }.map {
                    ShellConversationRef(it.id, it.agentId, it.lastMessageAt ?: it.updatedAt ?: it.createdAt.orEmpty(), tags = it.tags)
                })
            }
        }
    }
    suspend fun resolveMostRecentConversationWithProvenance(
        agentId: String,
        maxAgeMs: Long,
        parentAgentId: String? = null,
    ): SharedChatSessionResolver.ConversationSelection =
        delegate.resolveMostRecentConversationWithProvenance(agentId, maxAgeMs, parentAgentId)
    fun captureAttribution(
        request: SharedChatSessionResolver.AttributionRequest,
    ): TimelineConversationAttributionCapture = delegate.captureAttribution(request)
    fun captureAttribution(
        requestedAgentId: String,
        selectedConversationId: String?,
        selectionMode: TimelineConversationSelectionMode,
        parentAgentId: String? = null,
    ): TimelineConversationAttributionCapture = delegate.captureAttribution(
        requestedAgentId = requestedAgentId,
        selectedConversationId = selectedConversationId,
        selectionMode = selectionMode,
        parentAgentId = parentAgentId,
    )
}
