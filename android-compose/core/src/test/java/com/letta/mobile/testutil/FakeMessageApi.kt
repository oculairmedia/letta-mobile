package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.BatchMessage
import com.letta.mobile.data.model.BatchMessagesResponse
import com.letta.mobile.data.model.CreateBatchMessagesRequest
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.UsageStatistics
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk

class FakeMessageApi : MessageApi(mockk(relaxed = true)) {
    var messages = mutableListOf<LettaMessage>()
    var batches = mutableListOf<Job>()
    var batchMessagesByBatchId = mutableMapOf<String, List<BatchMessage>>()
    var searchResults = mutableListOf<MessageSearchResult>()
    var shouldFail = false
    val calls = mutableListOf<String>()
    var lastFetchConversationId: String? = null
    var lastFetchMessageLimit: Int? = null
    var lastFetchBeforeMessageId: String? = null
    var lastListAgentId: String? = null
    var lastListLimit: Int? = null
    var lastListBefore: String? = null
    var lastListAfter: String? = null
    var lastListOrder: String? = null
    var lastListConversationId: String? = null
    var lastCancelAgentId: String? = null
    var lastCancelRunIds: List<String>? = null
    var lastNoStreamConversationId: String? = null
    var lastNoStreamRequest: MessageCreateRequest? = null
    var lastStreamConversationId: String? = null
    var lastStreamRequest: MessageCreateRequest? = null
    var lastSendAgentId: String? = null
    var lastSendRequest: MessageCreateRequest? = null
    var lastSearchRequest: MessageSearchRequest? = null
    var lastCreateBatchRequest: CreateBatchMessagesRequest? = null
    var lastResetAgentId: String? = null

    override suspend fun fetchRecentMessages(
        conversationId: String,
        messageLimit: Int,
        beforeMessageId: String?,
    ): List<LettaMessage> {
        calls.add("fetchRecentMessages:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        lastFetchConversationId = conversationId
        lastFetchMessageLimit = messageLimit
        lastFetchBeforeMessageId = beforeMessageId
        return messages.take(messageLimit)
    }

    override suspend fun listMessages(
        agentId: String,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        calls.add("listMessages:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        lastListAgentId = agentId
        lastListLimit = limit
        lastListBefore = before
        lastListAfter = after
        lastListOrder = order
        lastListConversationId = conversationId
        return messages
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        calls.add("listConversationMessages:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        return messages
    }

    override suspend fun retrieveBatch(batchId: String): Job {
        calls.add("retrieveBatch:$batchId")
        if (shouldFail) throw ApiException(500, "Server error")
        return batches.firstOrNull { it.id == batchId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): ByteReadChannel {
        calls.add("sendConversationMessage:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        lastStreamConversationId = conversationId
        lastStreamRequest = request
        return ByteReadChannel("data: [DONE]\n\n")
    }

    override suspend fun sendConversationMessageNoStream(
        conversationId: String,
        request: MessageCreateRequest,
    ): LettaResponse {
        calls.add("sendConversationMessageNoStream:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        lastNoStreamConversationId = conversationId
        lastNoStreamRequest = request
        return LettaResponse(
            messages = emptyList(),
            stopReason = StopReason(reason = "end_turn"),
            usage = UsageStatistics(totalTokens = 0),
        )
    }

    override suspend fun sendMessage(agentId: String, request: MessageCreateRequest): LettaResponse {
        calls.add("sendMessage:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        lastSendAgentId = agentId
        lastSendRequest = request
        return LettaResponse(
            messages = emptyList(),
            stopReason = StopReason(reason = "end_turn"),
            usage = UsageStatistics(totalTokens = 0),
        )
    }

    override suspend fun cancelMessage(agentId: String, runIds: List<String>?): Map<String, String> {
        calls.add("cancelMessage:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        lastCancelAgentId = agentId
        lastCancelRunIds = runIds
        return mapOf("status" to "cancelled")
    }

    override suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult> {
        calls.add("searchMessages")
        if (shouldFail) throw ApiException(500, "Server error")
        lastSearchRequest = request
        return searchResults.toList()
    }

    override suspend fun createBatch(request: CreateBatchMessagesRequest): Job {
        calls.add("createBatch")
        if (shouldFail) throw ApiException(500, "Server error")
        lastCreateBatchRequest = request
        return batches.firstOrNull() ?: Job(id = "batch-job", status = "created")
    }

    override suspend fun listBatches(limit: Int?, before: String?, after: String?, order: String?): List<Job> {
        calls.add("listBatches")
        if (shouldFail) throw ApiException(500, "Server error")
        return batches.toList()
    }

    override suspend fun listBatchMessages(
        batchId: String,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
        agentId: String?,
    ): BatchMessagesResponse {
        calls.add("listBatchMessages:$batchId:${agentId.orEmpty()}")
        if (shouldFail) throw ApiException(500, "Server error")
        val messages = batchMessagesByBatchId[batchId].orEmpty().filter { message ->
            agentId == null || message.agentId == agentId
        }
        return BatchMessagesResponse(messages = messages)
    }

    override suspend fun cancelBatch(batchId: String) {
        calls.add("cancelBatch:$batchId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = batches.indexOfFirst { it.id == batchId }
        if (index < 0) throw ApiException(404, "Not found")
        batches[index] = batches[index].copy(status = "cancelled")
    }

    override suspend fun resetMessages(agentId: String) {
        calls.add("resetMessages:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        lastResetAgentId = agentId
    }
}
