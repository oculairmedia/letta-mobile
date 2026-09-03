package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
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
