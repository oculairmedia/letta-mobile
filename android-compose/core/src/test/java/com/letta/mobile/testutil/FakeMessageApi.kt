package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.BatchMessage
import com.letta.mobile.data.model.BatchMessagesResponse
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import io.mockk.mockk

class FakeMessageApi : MessageApi(mockk(relaxed = true)) {
    var messages = mutableListOf<LettaMessage>()
    var batches = mutableListOf<Job>()
    var batchMessagesByBatchId = mutableMapOf<String, List<BatchMessage>>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listMessages(
        agentId: String,
        limit: Int?,
        after: String?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        calls.add("listMessages:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
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
}
