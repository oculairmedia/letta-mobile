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

    override suspend fun listConversations(
        agentId: String?,
        limit: Int?,
        after: String?,
        archiveStatus: String?,
        summarySearch: String?,
        order: String?,
        orderBy: String?,
    ): List<Conversation> {
        calls.add("listConversations")
        if (shouldFail) throw ApiException(500, "Server error")
        val filtered = if (agentId != null) {
            conversations.filter { it.agentId == agentId }
        } else {
            conversations.toList()
        }
        return filtered.filter { conversation ->
            when (archiveStatus) {
                "archived" -> conversation.archived == true
                "unarchived" -> conversation.archived != true
                else -> true
            }
        }.filter { conversation ->
            summarySearch == null || conversation.summary?.contains(summarySearch, ignoreCase = true) == true
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

    override suspend fun cancelConversation(conversationId: String, agentId: String?) {
        calls.add("cancelConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
    }

    override suspend fun recompileConversation(conversationId: String, dryRun: Boolean, agentId: String?): String {
        calls.add("recompileConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        return "recompiled-system-prompt"
    }
}
