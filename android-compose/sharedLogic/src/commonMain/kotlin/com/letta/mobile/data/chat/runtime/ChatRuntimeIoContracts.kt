package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.timeline.TimelineTransport

interface ChatGateway : TimelineTransport {
    suspend fun listConversations(limit: Int = DEFAULT_CONVERSATION_LIMIT, archiveStatus: String? = null): List<Conversation>
    // letta-mobile-i9h61.3.2: agent-scoped list (the OTHER agent's
    // conversations, for tap-to-navigate). Reads via the wrapper's
    // conversation.list_agent admin_rpc when the Iroh transport is
    // available; HTTP / appserver gateways without that surface return
    // emptyList() so the picker falls back cleanly. No pagination —
    // the recipient's router reads all active conversations at receive
    // time, and a single 500-cap is well above any practical agent's
    // count.
    suspend fun listConversationsForAgent(
        agentId: String,
        limit: Int = DEFAULT_CONVERSATION_LIMIT,
    ): List<Conversation> = emptyList()
    suspend fun getConversation(conversationId: String): Conversation
    suspend fun deleteConversation(conversationId: String) {
        throw UnsupportedOperationException("deleteConversation is not supported by this gateway")
    }

    companion object {
        const val DEFAULT_CONVERSATION_LIMIT = 40
    }
}

/**
 * Management operations beyond the core [ChatGateway] contract (agent/
 * conversation creation, model catalog, per-conversation overrides).
 * Desktop reaches these through an interface check on its gateway, so any
 * transport (HTTP, Iroh admin_rpc) can opt in without the controller
 * depending on a concrete gateway class (letta-mobile-yh92w).
 */
interface ChatGatewayExtras {
    suspend fun createConversation(agentId: String, summary: String? = null): Conversation
    suspend fun createAgent(params: AgentCreateParams): Agent
    suspend fun listLlmModels(): List<LlmModel>
    suspend fun setConversationModel(conversationId: String, model: String): Conversation
    suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation
}

/**
 * letta-mobile-wxy4s: gateways that can report their underlying transport's
 * connection state, so a UI controller can surface a drop and auto-recover after
 * the redial instead of silently rendering cached data.
 *
 * Probed the same way as [ChatGatewayExtras] / ApprovalSubmittingGateway: the
 * controller casts its gateway to this interface and, when present, collects
 * [connectionState]. Gateways without a live transport simply don't implement it.
 */
interface ConnectionStatusGateway {
    val connectionState: kotlinx.coroutines.flow.StateFlow<com.letta.mobile.data.transport.ChannelTransportState>
}

interface ChatSessionGraph<out Repositories : SessionRepositoryGraph> {
    val repositories: Repositories
    val gateway: ChatGateway

    fun close()
}
