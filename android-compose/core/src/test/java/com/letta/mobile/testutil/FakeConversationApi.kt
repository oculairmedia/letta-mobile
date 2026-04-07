package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationUpdateParams

class FakeConversationApi : ConversationApi(null!!) {
    var conversations = mutableListOf<Conversation>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listConversations(agentId: String?, limit: Int?, after: String?): List<Conversation> {
        calls.add("listConversations")
        if (shouldFail) throw ApiException(500, "Server error")
        return if (agentId != null) {
            conversations.filter { it.agentId == agentId }
        } else {
            conversations.toList()
        }
    }

    override suspend fun createConversation(params: ConversationCreateParams): Conversation {
        calls.add("createConversation:${params.agentId}")
        if (shouldFail) throw ApiException(500, "Server error")
        val conv = TestData.conversation(id = "new-${conversations.size}", agentId = params.agentId, summary = params.summary)
        conversations.add(conv)
        return conv
    }

    override suspend fun deleteConversation(conversationId: String) {
        calls.add("deleteConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        conversations.removeAll { it.id == conversationId }
    }

    override suspend fun updateConversation(conversationId: String, params: ConversationUpdateParams): Conversation {
        calls.add("updateConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) throw ApiException(404, "Not found")
        val updated = conversations[index].copy(summary = params.summary)
        conversations[index] = updated
        return updated
    }

    override suspend fun forkConversation(conversationId: String, agentId: String): Conversation {
        calls.add("forkConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        val forked = TestData.conversation(id = "fork-${conversations.size}", agentId = agentId)
        conversations.add(forked)
        return forked
    }
}
