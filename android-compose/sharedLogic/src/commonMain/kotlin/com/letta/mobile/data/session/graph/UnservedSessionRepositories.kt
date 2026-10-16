package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.UnservedStandIn
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.VibesyncEvent
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

// letta-mobile-ckmub.17.1: the session repositories a host graph leaves unbound and that have no
// shared absent-route implementation (the agent, admin, schedule, step and project-work repositories
// do: an App Server repository with no client, or their RemoteRoute.Absent form), as
// [com.letta.mobile.data.backend.UnservedRoute] stand-ins.

class UnservedConversationRepository :
    UnservedStandIn(SessionRoute.CONVERSATIONS, "IConversationRepository"),
    IConversationRepository {
    override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = unserved("getConversations")
    override fun getCachedConversations(agentId: AgentId): List<Conversation> = unserved("getCachedConversations")
    override fun canCancelConversationRuns(): Boolean = unserved("canCancelConversationRuns")
    override fun canRecompileConversations(): Boolean = unserved("canRecompileConversations")
}

class UnservedVibesyncEventStreamRepository :
    UnservedStandIn(SessionRoute.VIBESYNC, "IVibesyncEventStreamRepository"),
    IVibesyncEventStreamRepository {
    override val events: SharedFlow<VibesyncEvent> get() = unserved("events")
    override fun start(): Unit = unserved("start")
    override fun stop(): Unit = unserved("stop")
}
