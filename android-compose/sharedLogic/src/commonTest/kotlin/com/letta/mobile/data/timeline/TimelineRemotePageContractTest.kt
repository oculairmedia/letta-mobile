package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class TimelineRemotePageContractTest {
    private val request = TimelineRemotePageRequest(
        scope = TimelineScope("backend-secret", "conversation-secret", "agent-secret"),
        requestId = TimelineRequestId("request-secret"),
        selectionGeneration = TimelineSelectionGeneration(7),
        order = TimelineRemoteOrder.NewestFirst,
        continuation = TimelineContinuation.Before(TimelineMessageId("before-2")),
        budget = TimelinePageBudget(2, 8_192),
    )

    @Test
    fun `unknown order and invalid budgets fail closed`() {
        assertFailsWith<IllegalArgumentException> { TimelinePageBudget(0, 1) }
        assertFailsWith<IllegalArgumentException> { TimelinePageBudget(1, 0) }
        assertFailsWith<IllegalArgumentException> { request.copy(order = TimelineRemoteOrder.Unknown) }
    }

    @Test
    fun `canonical page echoes owner and advances before continuation`() {
        val page = TimelineRemotePageAdapter.fromMessages(
            request,
            listOf(message("new", "2026-02-02"), message("old", "2026-01-01")),
            hasMore = true,
        )
        assertEquals(request.requestId, page.requestId)
        assertEquals(request.selectionGeneration, page.selectionGeneration)
        assertEquals(listOf("old", "new"), page.records.map { it.identity.value })
        assertEquals(TimelineContinuation.Before(TimelineMessageId("old")), page.nextContinuation)
    }

    @Test
    fun `duplicate-only advancing page succeeds and repeated cursor is no progress`() {
        Telemetry.clear()
        val advancing = TimelineRemotePageAdapter.fromMessages(
            request,
            listOf(message("old", "2026-01-01")),
            hasMore = true,
        )
        assertIs<TimelineRemotePageResult.Page>(
            TimelineRemotePageProgressClassifier.classify(request, advancing, TimelinePageProgress(request.continuation, 1, 0)),
        )
        val repeated = advancing.copy(nextContinuation = request.continuation)
        assertIs<TimelineRemotePageResult.NoProgress>(
            TimelineRemotePageProgressClassifier.classify(request, repeated, TimelinePageProgress(request.continuation, 1, 0)),
        )
        val attrs = Telemetry.snapshot().first { it.name == "pageResult" }.attrs
        assertNotEquals("request-secret", attrs["requestIdHash"])
        assertNotEquals("backend-secret", attrs["scopeHash"])
        assertEquals(7L, attrs["selectionGeneration"])
        assertEquals(false, attrs.values.any { it.toString().contains("secret") })
    }

    @Test
    fun `row body and malformed continuation guards fail closed`() {
        assertFailsWith<TimelineRemotePageException.BudgetExceeded> {
            TimelineRemotePageAdapter.fromMessages(request.copy(budget = TimelinePageBudget(1, 8_192)), listOf(message("a"), message("b")), false)
        }
        assertFailsWith<TimelineRemotePageException.BudgetExceeded> {
            TimelineRemotePageAdapter.fromMessages(request.copy(budget = TimelinePageBudget(2, 1)), listOf(message("a")), false)
        }
        assertFailsWith<TimelineRemotePageException.MalformedPage> {
            TimelineRemotePageAdapter.fromMessages(request, emptyList(), true)
        }
        assertFailsWith<TimelineRemotePageException.MalformedPage> {
            TimelineRemotePageAdapter.fromMessages(request, listOf(message("a")), false, request.continuation)
        }
    }

    @Test
    fun `default before rejection performs no legacy request`() = runTest {
        var calls = 0
        val transport = object : TimelineTransport {
            override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> = emptyFlow()
            override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()
            override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?): List<LettaMessage> {
                calls++
                return emptyList()
            }
            override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?): List<LettaMessage> = emptyList()
        }

        assertFailsWith<TimelineRemotePageException.InvalidRequest> { transport.listConversationMessagePage(request) }
        assertEquals(0, calls)
    }

    @Test
    fun `before continuation routes through the older page read and never the tail read`() = runTest {
        var tailCalls = 0
        var olderRequest: List<Any?>? = null
        val transport = object : TimelineTransport {
            override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> = emptyFlow()
            override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()
            override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?): List<LettaMessage> {
                tailCalls++
                return emptyList()
            }
            override suspend fun listConversationMessagesBefore(conversationId: String, limit: Int, before: String, order: String): List<LettaMessage> {
                olderRequest = listOf(conversationId, limit, before, order)
                return listOf(message("old-1", "2026-01-01T00:00:01Z"), message("old-0", "2026-01-01T00:00:00Z"))
            }
            override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?): List<LettaMessage> = emptyList()
        }

        val page = assertIs<TimelineRemotePageResult.Page>(transport.listConversationMessagePage(request))

        assertEquals(0, tailCalls)
        assertEquals(listOf("conversation-secret", 2, "before-2", "desc"), olderRequest)
        assertEquals(listOf("old-0", "old-1"), page.records.map { it.identity.value })
        // A full page keeps walking backwards from its oldest row.
        assertEquals(TimelineContinuation.Before(TimelineMessageId("old-0")), page.nextContinuation)
    }

    @Test
    fun `cancellation is never mapped to transport failure`() = runTest {
        assertFailsWith<CancellationException> {
            TimelineRemotePageAdapter.load(request) { _, _, _, _ -> throw CancellationException("stop") }
        }
    }

    private fun message(id: String, date: String = "2026-01-01") = AssistantMessage(
        id = id,
        contentRaw = JsonPrimitive("body-$id"),
        date = date,
    )
}
