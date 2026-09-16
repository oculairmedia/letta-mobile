package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeMessageApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class TimelineRemotePageTransportParityTest {
    @Test
    fun `HTTP and Iroh newest pages have identical canonical result`() = runTest {
        val messages = listOf(message("old", "2026-01-01"), message("new", "2026-01-02"))
        assertPageParity(
            messages = messages,
            request = request("newest", TimelineContinuation.Initial, TimelineRemoteOrder.NewestFirst, 2),
            irohResult = bare(messages.reversed()),
        )
    }

    @Test
    fun `HTTP and Iroh older pages have identical canonical result`() = runTest {
        val messages = listOf(message("old", "2026-01-01"), message("middle", "2026-01-02"))
        assertPageParity(
            messages = messages,
            request = request("older", TimelineContinuation.Before(TimelineMessageId("new")), TimelineRemoteOrder.NewestFirst, 2),
            irohResult = bare(messages.reversed()),
        )
    }

    @Test
    fun `short Iroh trimmed page preserves explicit continuation without inferring terminal`() = runTest {
        val request = request("trimmed", TimelineContinuation.Before(TimelineMessageId("new")), TimelineRemoteOrder.NewestFirst, 4)
        val messages = listOf(message("old", "2026-01-01"), message("middle", "2026-01-02"))
        val result = iroh(wrapped(messages.reversed(), hasMore = true, nextBefore = "server-cursor"))
            .listConversationMessagePage(request) as TimelineRemotePageResult.Page

        assertEquals(listOf("old", "middle"), result.records.map { it.identity.value })
        assertEquals(TimelineContinuation.Before(TimelineMessageId("server-cursor")), result.nextContinuation)
        assertTrue(result.hasMore)
    }

    @Test
    fun `duplicate-only pages advance or stop identically across transports`() = runTest {
        val messages = listOf(message("duplicate", "2026-01-01"))
        val advancingRequest = request("advance", TimelineContinuation.Before(TimelineMessageId("previous")), TimelineRemoteOrder.NewestFirst, 1)
        val advancingProgress = TimelinePageProgress(advancingRequest.continuation, 1, 0)
        val httpAdvance = http(messages).listConversationMessagePage(advancingRequest, advancingProgress)
        val irohAdvance = iroh(wrapped(messages, true, "duplicate")).listConversationMessagePage(advancingRequest, advancingProgress)
        assertEquals(httpAdvance, irohAdvance)
        assertTrue(httpAdvance is TimelineRemotePageResult.Page)

        val repeatedRequest = request("repeat", TimelineContinuation.Before(TimelineMessageId("duplicate")), TimelineRemoteOrder.NewestFirst, 1)
        val repeatedProgress = TimelinePageProgress(repeatedRequest.continuation, 1, 0)
        val httpStop = http(messages).listConversationMessagePage(repeatedRequest, repeatedProgress)
        val irohStop = iroh(wrapped(messages, true, "duplicate")).listConversationMessagePage(repeatedRequest, repeatedProgress)
        assertEquals(httpStop, irohStop)
        assertTrue(httpStop is TimelineRemotePageResult.NoProgress)
    }

    @Test
    fun `row and decoded-byte guards fail through both production adapters`() = runTest {
        val rows = listOf(message("one"), message("two"))
        val rowRequest = request("rows", TimelineContinuation.Initial, TimelineRemoteOrder.NewestFirst, 1)
        assertThrows(TimelineRemotePageException.BudgetExceeded::class.java) {
            kotlinx.coroutines.runBlocking { iroh(bare(rows)).listConversationMessagePage(rowRequest) }
        }
        val byteRequest = request("bytes", TimelineContinuation.Initial, TimelineRemoteOrder.NewestFirst, 2, maxBytes = 1)
        assertThrows(TimelineRemotePageException.BudgetExceeded::class.java) {
            kotlinx.coroutines.runBlocking { http(rows).listConversationMessagePage(byteRequest) }
        }
        assertThrows(TimelineRemotePageException.BudgetExceeded::class.java) {
            kotlinx.coroutines.runBlocking { iroh(bare(rows)).listConversationMessagePage(byteRequest) }
        }
    }

    @Test
    fun `timeout and cancellation cannot contaminate sibling success or telemetry owner`() = runTest {
        Telemetry.clear()
        val channel = FakeChannelTransport().apply {
            adminRpcHandler = { _, path, _ ->
                when {
                    "conv-timeout" in path -> { delay(30); throw SocketTimeoutException("bounded timeout") }
                    "conv-cancel" in path -> { delay(10); throw CancellationException("cancel only this request") }
                    else -> { delay(20); ok(bare(listOf(message("success")))) }
                }
            }
        }
        val transport = iroh(channel)
        val successRequest = request("success-owner", TimelineContinuation.Initial, TimelineRemoteOrder.OldestFirst, 2, conversationId = "conv-success")
        val timeoutRequest = request("timeout-owner", TimelineContinuation.Initial, TimelineRemoteOrder.OldestFirst, 2, conversationId = "conv-timeout")
        val cancelRequest = request("cancel-owner", TimelineContinuation.Initial, TimelineRemoteOrder.OldestFirst, 2, conversationId = "conv-cancel")

        val success = async { transport.listConversationMessagePage(successRequest, TimelinePageProgress(successRequest.continuation, 1, 1)) }
        val timeout = async { runCatching { transport.listConversationMessagePage(timeoutRequest) }.exceptionOrNull() }
        val cancelled = async { runCatching { transport.listConversationMessagePage(cancelRequest) }.exceptionOrNull() }

        val page = success.await() as TimelineRemotePageResult.Page
        assertEquals(successRequest.requestId, page.requestId)
        assertEquals(successRequest.selectionGeneration, page.selectionGeneration)
        assertTrue(timeout.await() is SocketTimeoutException)
        assertTrue(cancelled.await() is CancellationException)
        val pageEvents = Telemetry.snapshot().filter { it.name == "pageResult" }
        assertEquals(1, pageEvents.size)
        assertEquals(successRequest.selectionGeneration.value, pageEvents.single().attrs["selectionGeneration"])
        assertFalse(pageEvents.single().attrs.values.any { it.toString().contains("owner") || it.toString().contains("success") })
    }

    private suspend fun assertPageParity(messages: List<AssistantMessage>, request: TimelineRemotePageRequest, irohResult: String) {
        val http = http(messages).listConversationMessagePage(request)
        val iroh = iroh(irohResult).listConversationMessagePage(request)
        assertEquals(http, iroh)
        assertEquals(listOf("old", if (messages.size == 2 && messages[1].id == "new") "new" else "middle"), (http as TimelineRemotePageResult.Page).records.map { it.identity.value })
    }

    private fun http(messages: List<AssistantMessage>) = MessageApiTimelineTransport(FakeMessageApi().apply { this.messages.addAll(messages) })

    private fun iroh(result: String): IrohAdminRpcTimelineTransport = iroh(FakeChannelTransport().apply { adminRpcHandler = { _, _, _ -> ok(result) } })

    private fun iroh(channel: FakeChannelTransport): IrohAdminRpcTimelineTransport = IrohAdminRpcTimelineTransport(
        channel,
        FakeSettingsRepository(LettaConfig("iroh", LettaConfig.Mode.SELF_HOSTED, "iroh://EndpointTicket")),
    )

    private fun request(
        id: String,
        continuation: TimelineContinuation,
        order: TimelineRemoteOrder,
        maxRows: Int,
        maxBytes: Long = 16_384,
        conversationId: String = "conv-parity",
    ) = TimelineRemotePageRequest(
        TimelineScope("backend", conversationId),
        TimelineRequestId("request-$id"),
        TimelineSelectionGeneration(id.length.toLong()),
        order,
        continuation,
        TimelinePageBudget(maxRows, maxBytes),
    )

    private fun message(id: String, date: String = "2026-01-01") = AssistantMessage(id, JsonPrimitive("body-$id"), date)

    private fun bare(messages: List<AssistantMessage>): String = messages.joinToString(prefix = "[", postfix = "]") { message ->
        """{"id":"${message.id}","message_type":"assistant_message","content":"body-${message.id}","date":"${message.date}"}"""
    }

    private fun wrapped(messages: List<AssistantMessage>, hasMore: Boolean, nextBefore: String): String =
        "{\"messages\":${bare(messages)},\"has_more\":$hasMore,\"next_before\":\"$nextBefore\"}"

    private fun ok(result: String) = AppServerInboundFrame.AdminRpcResponse("req", true, Json.parseToJsonElement(result))

}
