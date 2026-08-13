package com.letta.mobile.data.repository

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BatchMessage
import com.letta.mobile.data.model.BatchMessagesResponse
import com.letta.mobile.data.model.Job
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Phase 2.2 (data-efficiency-audit Q3): focused pagination tests for
 * [MessageRepository.listBatches] and [MessageRepository.listBatchMessages].
 * Both previously used `limit = 1000` and now route through
 * [exhaustCursorPages] with a bounded `maxPages = 20`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class MessagePaginationTest {

    @Test
    fun `listBatches fetches both pages when API returns exactly two`() = runTest {
        val jobs = (1..60).map { Job(id = "job-$it", status = "running") }
        val api = PaginatingMessageApi(jobs = jobs)
        val repo = MessageRepository(api)

        val result = repo.listBatches()

        assertEquals(60, result.size)
        assertEquals(listOf(null, "job-50"), api.observedAfters)
    }

    @Test
    fun `listBatchMessages fetches both pages when API returns exactly two`() = runTest {
        val messages = (1..60).map { BatchMessage(id = "m-$it", agentId = "agent-1") }
        val api = PaginatingMessageApi(batchMessages = messages)
        val repo = MessageRepository(api)

        // Pass an explicit AgentId to disambiguate the two listBatchMessages overloads.
        val result = repo.listBatchMessages(batchId = "job-1", agentId = AgentId("agent-1"))

        assertEquals(60, result.messages.size)
        assertEquals(listOf(null, "m-50"), api.observedAftersForBatchMessages)
    }

    private class PaginatingMessageApi(
        private val jobs: List<Job> = listOf(Job(id = "job-1", status = "running")),
        private val batchMessages: List<BatchMessage> = listOf(BatchMessage(id = "m-1", agentId = "agent-1")),
    ) : MessageApi(mockk(relaxed = true)) {
        val observedAfters = mutableListOf<String?>()
        val observedAftersForBatchMessages = mutableListOf<String?>()

        override suspend fun listBatches(
            limit: Int?,
            before: String?,
            after: String?,
            order: String?,
        ): List<Job> {
            observedAfters += after
            val pageSize = limit ?: 50
            val start = after?.let { id ->
                jobs.indexOfFirst { it.id == id }.let { if (it < 0) jobs.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(jobs.size)
            return jobs.subList(start, end)
        }

        override suspend fun listBatchMessages(
            batchId: String,
            limit: Int?,
            before: String?,
            after: String?,
            order: String?,
            agentId: String?,
        ): BatchMessagesResponse {
            observedAftersForBatchMessages += after
            val pageSize = limit ?: 50
            val start = after?.let { id ->
                batchMessages.indexOfFirst { it.id == id }.let { if (it < 0) batchMessages.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(batchMessages.size)
            return BatchMessagesResponse(messages = batchMessages.subList(start, end))
        }
    }
}