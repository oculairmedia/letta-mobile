package com.letta.mobile.data.chat.routing

import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InterAgentConversationPickerTest {
    private class Repo(private val rows: List<Conversation>) : IConversationRepository {
        override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = flowOf(rows)
        override fun getCachedConversations(agentId: AgentId): List<Conversation> = rows
        override suspend fun listConversationsForAgent(agentId: AgentId, limit: Int): List<Conversation> = rows
    }

    @Test
    fun pickerAgreesWithRouterOnInboxDespiteNewerHome() = runTest {
        val agent = AgentId("target")
        val rows = listOf(
            Conversation(ConversationId("home"), agent, lastMessageAt = "2026-11-02", tags = listOf("lane:home")),
            Conversation(ConversationId("inbox"), agent, lastMessageAt = "2020-01-01", tags = listOf("lane:inbox")),
        )
        val states = rows.map { IrohAgentMessageRouter.ConversationState(it, false) }
        val decision = IrohAgentMessageRouter("target").route("sender", "m", states)
        assertEquals(
            (decision as IrohAgentMessageRouter.RoutingDecision.Deliver).conversationId,
            pickOtherAgentConversation(Repo(rows), agent),
        )
        assertNull(pickOtherAgentConversation(Repo(rows.take(1)), agent))
        val duplicated = rows + Conversation(ConversationId("other-inbox"), agent, tags = listOf("lane:inbox"))
        assertNull(pickOtherAgentConversation(Repo(duplicated), agent))
    }
}
