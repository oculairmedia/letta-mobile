package com.letta.mobile.data.backend

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId

/** Session-local listing operations consumed by the cached all-conversations repository. */
interface ConversationListing {
    /** Whether an already listed agent has another page. */
    val hasMore: Boolean

    /** Refreshes visible recents and requested agent pages, publishing recents before agent reads. */
    suspend fun refresh(roster: List<Agent>, pins: Set<AgentId>, publish: (List<Conversation>) -> Unit): List<Conversation>

    /** Reconciles changed conversation identities against the visible roster. */
    suspend fun changed(dirty: Map<ConversationId, AgentId?>, roster: List<Agent>): List<Conversation>

    /** Records rows already read individually without listing their agents again. */
    suspend fun record(rows: List<Conversation>, roster: List<Agent>): List<Conversation>

    /** Reads another page for an agent, or all agents already listed when null. */
    suspend fun loadMore(agent: AgentId? = null): List<Conversation>
}

/** Creates a fresh listing over the repository's existing session read paths. */
fun interface ConversationListingFactory {
    /** Adapts agent pages, visible recents and individual conversation reads without choosing a transport. */
    fun create(
        listAgent: suspend (AgentId, ConversationId?, Int) -> List<Conversation>,
        listRecents: suspend (Int, ConversationId?) -> List<Conversation>,
        getConversation: suspend (ConversationId) -> Conversation,
    ): ConversationListing
}
