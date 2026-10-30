package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.util.AGENT_CONVERSATION_PAGE_SIZE
import com.letta.mobile.util.AgentConversationReader

/**
 * A backend's conversation records: list, read, delete and the undo. Part of every [ChatGateway];
 * a host that serves them apart from a timeline (an App Server session's own commands) implements
 * just this, and a gateway delegates to it.
 */
interface ChatConversationStore {
    /**
     * One page of [AgentConversationPage.agent]'s conversations, newest first, as the backend sent it
     * (a store that ignores `agent_id`, or a hidden row, is filtered by the reader, which pages on
     * from the page's last row). Lists are read per agent, never across every agent: a host's newest
     * conversations are mostly agent-free worker transcripts that would crowd a user's chats out.
     */
    suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation>

    /** Visible, active conversations across agents, newest first; unsupported hosts retain legacy agent reads. */
    suspend fun listRecentConversations(limit: Int = 30, after: ConversationId? = null): List<Conversation> {
        throw UnsupportedOperationException("Visible recents are not supported by this gateway")
    }
    suspend fun getConversation(conversationId: String): Conversation
    suspend fun deleteConversation(conversationId: String) {
        throw UnsupportedOperationException("deleteConversation is not supported by this gateway")
    }

    /** What [deleteConversation] actually does on this backend; UIs word the confirm dialog and the undo from it. */
    val deleteBehavior: ConversationDeleteBehavior get() = ConversationDeleteBehavior.Permanent

    /**
     * Undoes a [deleteConversation] that did not destroy the conversation, putting it back in the
     * state it had before: [wasArchived] true keeps it archived (it only rejoins the Archived
     * list), false makes it active again. Unsupported where delete is permanent.
     */
    suspend fun restoreDeletedConversation(conversationId: String, wasArchived: Boolean) {
        throw UnsupportedOperationException("restoreDeletedConversation is not supported by this gateway")
    }
}

/** A backend the chat runs over: its timeline and its conversation records. */
interface ChatGateway : TimelineTransport, ChatConversationStore {
    /** Other clients' conversation writes; empty on backends without push support. */
    val conversationChanges: kotlinx.coroutines.flow.Flow<Pair<ConversationId, AgentId?>>
        get() = kotlinx.coroutines.flow.emptyFlow()
}

/** Which page of one agent's conversations to read: [limit] rows after the [after] cursor (none: the newest). */
data class AgentConversationPage(
    val agent: AgentId,
    val limit: Int = AGENT_CONVERSATION_PAGE_SIZE,
    val after: ConversationId? = null,
    /** Every conversation, active and archived: the lists filter archived rows themselves. */
    val archiveStatus: String = "all",
)

/** This store's own conversations as the shared conversation lists read them, a page per agent. */
fun ChatConversationStore.agentConversationReader(): AgentConversationReader =
    AgentConversationReader { agent, after, limit -> listAgentConversations(AgentConversationPage(agent, limit, after)) }

/**
 * Management operations beyond the core [ChatGateway] contract (agent/
 * conversation creation, model catalog, per-conversation overrides).
 * Desktop reaches these through an interface check on its gateway, so any
 * transport (HTTP, Iroh admin_rpc) can opt in without the controller
 * depending on a concrete gateway class.
 */
interface ChatGatewayExtras {
    suspend fun createConversation(agentId: String, summary: String? = null): Conversation
    suspend fun createAgent(params: AgentCreateParams): Agent
    suspend fun listLlmModels(): List<LlmModel>
    suspend fun setConversationModel(conversationId: String, model: String): Conversation
    suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation
}

/**
 * Gateways that can report their underlying transport's
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
