package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationUpdateParams
import io.mockk.mockk
import kotlinx.coroutines.delay

import kotlin.time.Duration.Companion.milliseconds
class FakeConversationApi : ConversationApi(mockk(relaxed = true)) {
    var conversations = mutableListOf<Conversation>()
    var shouldFail = false
    var listDelayMillis: Long = 0L
    // letta-mobile-xzoy3: simulates the stale-transport ISE (IChannelTransport
    // default adminRpc) for the session-switch retry test.
    var staleTransportIaeCountdown: Int = 0
    val calls = mutableListOf<String>()
    val listLimits = mutableListOf<Int?>()
    
    // Concurrency tracking for H5 (data-efficiency-audit) test coverage
    val peakConcurrentListConversations = java.util.concurrent.atomic.AtomicInteger(0)
    private val inFlightListConversations = java.util.concurrent.atomic.AtomicInteger(0)

    override suspend fun listConversations(
        agentId: AgentId?,
        limit: Int?,
        after: String?,
        archiveStatus: String?,
        summarySearch: String?,
        order: String?,
        orderBy: String?,
    ): List<Conversation> {
        val inFlight = inFlightListConversations.incrementAndGet()
        var observedPeak = peakConcurrentListConversations.get()
        while (inFlight > observedPeak && !peakConcurrentListConversations.compareAndSet(observedPeak, inFlight)) {
            observedPeak = peakConcurrentListConversations.get()
        }
        try {
            calls.add("listConversations")
            listLimits.add(limit)
            if (listDelayMillis > 0L) delay(listDelayMillis.milliseconds)
            if (staleTransportIaeCountdown > 0) {
                staleTransportIaeCountdown--
                throw IllegalStateException("admin_rpc is not supported by this transport")
            }
            if (shouldFail) throw ApiException(500, "Server error")
        val filtered = if (agentId != null) {
            conversations.filter { it.agentId == agentId }
        } else {
            conversations.toList()
        }
        val matching = filtered.filter { conversation ->
            when (archiveStatus) {
                "archived" -> conversation.archived == true
                "unarchived" -> conversation.archived != true
                else -> true
            }
        }.filter { conversation ->
            summarySearch == null || conversation.summary?.contains(summarySearch, ignoreCase = true) == true
        }
            val afterIndex = after?.let { cursor -> matching.indexOfFirst { it.id.value == cursor } } ?: -1
            val afterPage = if (afterIndex >= 0) matching.drop(afterIndex + 1) else matching
            return limit?.let { afterPage.take(it) } ?: afterPage
        } finally {
            inFlightListConversations.decrementAndGet()
        }
    }

    override suspend fun createConversation(params: ConversationCreateParams): Conversation {
        calls.add("createConversation:${params.agentId}")
        if (shouldFail) throw ApiException(500, "Server error")
        val conv = TestData.conversation(id = "new-${conversations.size}", agentId = params.agentId.value, summary = params.summary)
        conversations.add(conv)
        return conv
    }

    override suspend fun deleteConversation(conversationId: ConversationId) {
        calls.add("deleteConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        conversations.removeAll { it.id == conversationId }
    }

    override suspend fun updateConversation(conversationId: ConversationId, params: ConversationUpdateParams): Conversation {
        calls.add("updateConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) throw ApiException(404, "Not found")
        val current = conversations[index]
        val updated = current.copy(
            summary = params.summary ?: current.summary,
            archived = params.archived ?: current.archived,
            lastMessageAt = params.lastMessageAt ?: current.lastMessageAt,
        )
        conversations[index] = updated
        return updated
    }

    override suspend fun getConversation(conversationId: ConversationId): Conversation {
        calls.add("getConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        return conversations.find { it.id == conversationId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun forkConversation(conversationId: ConversationId, agentId: AgentId?): Conversation {
        calls.add("forkConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        val forked = TestData.conversation(id = "fork-${conversations.size}", agentId = agentId?.value ?: "")
        conversations.add(forked)
        return forked
    }

    override suspend fun cancelConversation(conversationId: ConversationId, agentId: AgentId?) {
        calls.add("cancelConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
    }

    override suspend fun recompileConversation(conversationId: ConversationId, dryRun: Boolean, agentId: AgentId?): String {
        calls.add("recompileConversation:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        return "recompiled-system-prompt"
    }
}
