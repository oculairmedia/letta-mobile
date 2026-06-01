package com.letta.mobile.testutil

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Hand-written test double for [IConversationRepository]. Tests use this
 * instead of mocking the stateful concrete repository so repository state does
 * not leak through MockK bytecode instrumentation in reused Gradle daemons.
 */
class FakeConversationRepository(
    initialConversations: List<Conversation> = emptyList(),
) : IConversationRepository {
    val conversationsByAgentState: MutableStateFlow<Map<AgentId, List<Conversation>>> =
        MutableStateFlow(initialConversations.groupBy { it.agentId })

    val calls: MutableList<String> = mutableListOf()

    override fun getConversations(agentId: AgentId): Flow<List<Conversation>> {
        calls += "getConversations:${agentId.value}"
        return conversationsByAgentState.map { conversationsByAgent -> conversationsByAgent[agentId].orEmpty() }
    }

    override fun getCachedConversations(agentId: AgentId): List<Conversation> {
        calls += "getCachedConversations:${agentId.value}"
        return conversationsByAgentState.value[agentId].orEmpty()
    }

    override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean {
        calls += "hasFreshConversations:${agentId.value}:$maxAgeMs"
        return conversationsByAgentState.value.containsKey(agentId)
    }

    override suspend fun refreshConversations(agentId: AgentId) {
        calls += "refreshConversations:${agentId.value}"
    }

    override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean {
        calls += "refreshConversationsIfStale:${agentId.value}:$maxAgeMs"
        return false
    }

    override suspend fun getConversation(id: ConversationId): Conversation {
        calls += "getConversation:${id.value}"
        return requireConversation(id)
    }

    override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation {
        calls += "createConversation:${agentId.value}"
        val conversation = TestData.conversation(
            id = "conv-${conversationCount() + 1}",
            agentId = agentId.value,
            summary = summary,
        )
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (agentId to (conversationsByAgent[agentId].orEmpty() + conversation))
        }
        return conversation
    }

    override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) {
        calls += "deleteConversation:${id.value}:${agentId.value}"
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (agentId to conversationsByAgent[agentId].orEmpty().filterNot { it.id == id })
        }
    }

    override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) {
        calls += "updateConversation:${id.value}:${agentId.value}"
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (
                agentId to conversationsByAgent[agentId].orEmpty().map { conversation ->
                    if (conversation.id == id) conversation.copy(summary = summary) else conversation
                }
            )
        }
    }

    override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) {
        calls += "setConversationArchived:${id.value}:${agentId.value}:$archived"
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (
                agentId to conversationsByAgent[agentId].orEmpty().map { conversation ->
                    if (conversation.id == id) conversation.copy(archived = archived) else conversation
                }
            )
        }
    }

    override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) {
        calls += "cancelConversation:${id.value}:${agentId?.value.orEmpty()}"
    }

    override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String {
        calls += "recompileConversation:${id.value}:$dryRun:${agentId?.value.orEmpty()}"
        return ""
    }

    override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation {
        calls += "forkConversation:${id.value}:${agentId.value}"
        val source = requireConversation(id)
        val fork = source.copy(id = ConversationId("${source.id.value}-fork"))
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (agentId to (conversationsByAgent[agentId].orEmpty() + fork))
        }
        return fork
    }

    private fun requireConversation(id: ConversationId): Conversation =
        conversationsByAgentState.value.values.flatten().firstOrNull { it.id == id }
            ?: error("No fake conversation queued for ${id.value}")

    private fun conversationCount(): Int = conversationsByAgentState.value.values.sumOf { it.size }
}
