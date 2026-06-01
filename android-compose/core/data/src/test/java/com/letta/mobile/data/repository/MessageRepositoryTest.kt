package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.BatchMessageRequest
import com.letta.mobile.data.model.CreateBatchMessagesRequest
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.testutil.FakeMessageApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class MessageRepositoryTest {

    private lateinit var fakeApi: FakeMessageApi
    private lateinit var repository: MessageRepository

    @Before
    fun setup() {
        fakeApi = FakeMessageApi()
        repository = MessageRepository(fakeApi)
    }

    @Test
    fun `fetchMessages without target fetches default recent conversation page`() = runTest {
        fakeApi.messages.addAll(
            listOf(
                UserMessage(id = "user-1", contentRaw = JsonPrimitive("hello")),
                AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("hi")),
            )
        )

        val result = repository.fetchMessages(agentId = "agent-1", conversationId = "conv-1")

        assertEquals(listOf("user-1", "assistant-1"), result.map { it.id })
        assertEquals("conv-1", fakeApi.lastFetchConversationId)
        assertEquals(MessageRepository.DEFAULT_FETCH_LIMIT, fakeApi.lastFetchMessageLimit)
        assertEquals(null, fakeApi.lastFetchBeforeMessageId)
    }

    @Test
    fun `fetchMessages returns empty list when recent fetch fails`() = runTest {
        fakeApi.shouldFail = true

        val result = repository.fetchMessages(agentId = "agent-1", conversationId = "conv-1")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchMessages with target scans chronological pages using conversation filter`() = runTest {
        fakeApi.messages.add(UserMessage(id = "target-1", contentRaw = JsonPrimitive("found")))

        val result = repository.fetchMessages(
            agentId = "agent-1",
            conversationId = "conv-1",
            targetMessageId = "target-1",
        )

        assertEquals(listOf("target-1"), result.map { it.id })
        assertEquals("agent-1", fakeApi.lastListAgentId)
        assertEquals(MessageRepository.TARGETED_FETCH_LIMIT, fakeApi.lastListLimit)
        assertEquals("asc", fakeApi.lastListOrder)
        assertEquals("conv-1", fakeApi.lastListConversationId)
    }

    @Test
    fun `fetchOlderMessages ignores blank cursor and otherwise delegates page request`() = runTest {
        assertTrue(repository.fetchOlderMessages("agent-1", "conv-1", " ").isEmpty())

        fakeApi.messages.add(UserMessage(id = "older-1", contentRaw = JsonPrimitive("older")))

        val result = repository.fetchOlderMessages("agent-1", "conv-1", "before-1")

        assertEquals(listOf("older-1"), result.map { it.id })
        assertEquals("conv-1", fakeApi.lastFetchConversationId)
        assertEquals(MessageRepository.OLDER_MESSAGES_PAGE_SIZE, fakeApi.lastFetchMessageLimit)
        assertEquals("before-1", fakeApi.lastFetchBeforeMessageId)
    }

    @Test
    fun `submitApproval sends agent-scoped approval JSON without response-only fields`() = runTest {
        repository.submitApproval(
            agentId = "agent-1",
            approvalRequestId = "approval-1",
            toolCallIds = listOf("tool-a", "tool-b"),
            approve = false,
            reason = " ",
        )

        assertEquals("agent-1", fakeApi.lastSendAgentId)
        assertEquals(null, fakeApi.lastStreamConversationId)
        assertEquals(false, fakeApi.lastSendRequest?.streaming)
        val approval = fakeApi.lastSendRequest!!.messages!!.single().jsonObject
        assertEquals("approval", approval["type"]?.jsonPrimitive?.content)
        assertEquals(false, approval["approve"]?.jsonPrimitive?.boolean)
        assertEquals("approval-1", approval["approval_request_id"]?.jsonPrimitive?.content)
        assertEquals(null, approval["reason"])

        val raw = approval.toString()
        assertTrue(raw.contains("\"type\":\"approval\""))
        assertFalse(raw.contains("\"status\""))
        assertFalse(raw.contains("\"tool_return\""))
        assertFalse(raw.contains("\"stdout\""))
        assertFalse(raw.contains("\"stderr\""))

        val approvals = approval["approvals"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool-a", "tool-b"), approvals.map { it["tool_call_id"]?.jsonPrimitive?.content })
        assertTrue(approvals.all { it["approve"]?.jsonPrimitive?.boolean == false })
        assertTrue(approvals.all { it["reason"] == null })
    }

    @Test
    fun `search and batch operations delegate exact request objects`() = runTest {
        val searchRequest = MessageSearchRequest(query = "needle", roles = listOf("assistant"), limit = 3)
        val searchResult = MessageSearchResult(
            embeddedText = "needle result",
            message = JsonObject(mapOf("id" to JsonPrimitive("msg-1"))),
        )
        fakeApi.searchResults.add(searchResult)
        fakeApi.batches.add(Job(id = "job-1", status = "pending"))
        val batchRequest = CreateBatchMessagesRequest(
            requests = listOf(BatchMessageRequest(agentId = "agent-1", messages = emptyList())),
        )

        val search = repository.searchMessages(searchRequest)
        val batch = repository.createBatch(batchRequest)

        assertEquals(listOf(searchResult), search)
        assertSame(searchRequest, fakeApi.lastSearchRequest)
        assertEquals("job-1", batch.id)
        assertSame(batchRequest, fakeApi.lastCreateBatchRequest)
    }

    @Test
    fun `batch listing and cancellation preserve batch id and optional agent filter`() = runTest {
        fakeApi.batches.add(Job(id = "job-1", status = "running"))
        fakeApi.batchMessagesByBatchId["job-1"] = listOf(
            com.letta.mobile.data.model.BatchMessage(id = "m1", agentId = "agent-1"),
            com.letta.mobile.data.model.BatchMessage(id = "m2", agentId = "agent-2"),
        )

        val batches = repository.listBatches()
        val messages = repository.listBatchMessages("job-1", agentId = "agent-1")
        repository.cancelBatch("job-1")

        assertEquals(listOf("job-1"), batches.map { it.id })
        assertEquals(listOf("m1"), messages.messages.map { it.id })
        assertEquals("cancelled", fakeApi.batches.single().status)
        assertTrue(fakeApi.calls.contains("listBatches"))
        assertTrue(fakeApi.calls.contains("listBatchMessages:job-1:agent-1"))
        assertTrue(fakeApi.calls.contains("cancelBatch:job-1"))
    }

    @Test
    fun `cancel and reset message operations delegate identifiers`() = runTest {
        val cancelResult = repository.cancelMessage("agent-1", runIds = listOf("run-1"))
        repository.resetMessages("agent-1")

        assertEquals(mapOf("status" to "cancelled"), cancelResult)
        assertEquals("agent-1", fakeApi.lastCancelAgentId)
        assertEquals(listOf("run-1"), fakeApi.lastCancelRunIds)
        assertEquals("agent-1", fakeApi.lastResetAgentId)
        assertEquals(1, fakeApi.calls.count { it == "resetMessages:agent-1" })
    }

    @Test(expected = ApiException::class)
    fun `fetchOlderMessages propagates api failures for explicit pagination`() = runTest {
        fakeApi.shouldFail = true

        repository.fetchOlderMessages("agent-1", "conv-1", "before-1")
    }

    @Test
    fun `fetchConversationInspectorMessages maps message metadata and details`() = runTest {
        fakeApi.messages.addAll(
            listOf(
                UserMessage(
                    id = "user-1",
                    contentRaw = JsonPrimitive("hello"),
                    date = "2026-05-08T00:00:00Z",
                    runId = "run-1",
                    stepId = "step-1",
                    otid = "otid-1",
                    senderId = "sender-1",
                ),
                AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("")),
            )
        )

        val result = repository.fetchConversationInspectorMessages(com.letta.mobile.data.model.ConversationId("conv-1"))

        assertEquals(listOf("user-1", "assistant-1"), result.map { it.id })
        assertEquals("user_message", result.first().messageType)
        assertEquals("hello", result.first().summary)
        assertTrue(result.first().detailLines.contains("Run ID" to "run-1"))
        assertTrue(result.first().detailLines.contains("Sender ID" to "sender-1"))
        assertEquals("Assistant message", result[1].summary)
    }
}
