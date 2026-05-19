package com.letta.mobile.testutil

import com.letta.mobile.data.model.Conversation
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
    val conversationsByAgentState: MutableStateFlow<Map<String, List<Conversation>>> =
        MutableStateFlow(initialConversations.groupBy { it.agentId })

    val calls: MutableList<String> = mutableListOf()

    override fun getConversations(agentId: String): Flow<List<Conversation>> {
        calls += "getConversations:$agentId"
        return conversationsByAgentState.map { conversationsByAgent -> conversationsByAgent[agentId].orEmpty() }
    }

    override suspend fun refreshConversations(agentId: String) {
        calls += "refreshConversations:$agentId"
    }

    override suspend fun getConversation(id: String): Conversation {
        calls += "getConversation:$id"
        return requireConversation(id)
    }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation {
        calls += "createConversation:$agentId"
        val conversation = TestData.conversation(
            id = "conv-${conversationCount() + 1}",
            agentId = agentId,
            summary = summary,
        )
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (agentId to (conversationsByAgent[agentId].orEmpty() + conversation))
        }
        return conversation
    }

    override suspend fun deleteConversation(id: String, agentId: String) {
        calls += "deleteConversation:$id:$agentId"
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (agentId to conversationsByAgent[agentId].orEmpty().filterNot { it.id == id })
        }
    }

    override suspend fun updateConversation(id: String, agentId: String, summary: String) {
        calls += "updateConversation:$id:$agentId"
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (
                agentId to conversationsByAgent[agentId].orEmpty().map { conversation ->
                    if (conversation.id == id) conversation.copy(summary = summary) else conversation
                }
            )
        }
    }

    override suspend fun setConversationArchived(id: String, agentId: String, archived: Boolean) {
        calls += "setConversationArchived:$id:$agentId:$archived"
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (
                agentId to conversationsByAgent[agentId].orEmpty().map { conversation ->
                    if (conversation.id == id) conversation.copy(archived = archived) else conversation
                }
            )
        }
    }

    override suspend fun cancelConversation(id: String, agentId: String?) {
        calls += "cancelConversation:$id:${agentId.orEmpty()}"
    }

    override suspend fun recompileConversation(id: String, dryRun: Boolean, agentId: String?): String {
        calls += "recompileConversation:$id:$dryRun:${agentId.orEmpty()}"
        return ""
    }

    override suspend fun forkConversation(id: String, agentId: String): Conversation {
        calls += "forkConversation:$id:$agentId"
        val source = requireConversation(id)
        val fork = source.copy(id = "${source.id}-fork")
        conversationsByAgentState.update { conversationsByAgent ->
            conversationsByAgent + (agentId to (conversationsByAgent[agentId].orEmpty() + fork))
        }
        return fork
    }

    private fun requireConversation(id: String): Conversation =
        conversationsByAgentState.value.values.flatten().firstOrNull { it.id == id }
            ?: error("No fake conversation queued for $id")

    private fun conversationCount(): Int = conversationsByAgentState.value.values.sumOf { it.size }
}
