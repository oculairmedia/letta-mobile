package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.UnservedStandIn
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.api.IConversationRepository
import kotlinx.coroutines.flow.Flow

/**
 * The conversation repositories a host graph leaves unbound.
 *
 * a host that binds no conversation repository gets this stand-in, which
 * fails every call as the unserved [SessionRoute.CONVERSATIONS] route. The agent, admin, schedule and
 * step repositories need no equivalent here: an App Server repository with no client, or their
 * [com.letta.mobile.data.backend.RemoteRoute.Absent] form, already answers that way.
 */
class UnservedConversationRepository :
    UnservedStandIn(SessionRoute.CONVERSATIONS, "IConversationRepository"),
    IConversationRepository {
    override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = unserved("getConversations")
    override fun getCachedConversations(agentId: AgentId): List<Conversation> = unserved("getCachedConversations")
    override fun canCancelConversationRuns(): Boolean = unserved("canCancelConversationRuns")
    override fun canRecompileConversations(): Boolean = unserved("canRecompileConversations")
}
