package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * letta-mobile-71orq: older-message pagination (scroll up for history) over
 * message.list admin_rpc so it works in iroh:// mode instead of hard-failing at
 * the LettaApiClient purity choke-point.
 */
class IrohAdminRpcTimelineTransportOlderMessagesTest {
    private fun transport(fake: FakeChannelTransport): IrohAdminRpcTimelineTransport {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket",
            ),
        )
        return IrohAdminRpcTimelineTransport(fake, settings)
    }

    private fun ok(result: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req",
        success = true,
        result = Json.parseToJsonElement(result),
    )

    @Test
    fun `shouldUseIroh true for iroh backend`() {
        assertTrue(transport(FakeChannelTransport()).shouldUseIroh())
    }

    @Test
    fun `listOlderConversationMessages cursors on before and decodes`() = runTest {
        val fake = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                ok("""[{"id":"letta-msg-10","message_type":"assistant_message","content":"older"}]""")
            }
        }

        val messages = transport(fake).listOlderConversationMessages(
            conversationId = "conv-1",
            beforeMessageId = "letta-msg-20",
            limit = 20,
        )

        val call = fake.adminRpcCalls.single()
        assertEquals("message.list", call.method)
        assertTrue("path carries before cursor", call.path.contains("before=letta-msg-20"))
        assertTrue("path carries limit", call.path.contains("limit=20"))
        assertTrue("path scopes to the conversation", call.path.contains("/v1/conversations/conv-1/messages"))
        assertEquals(1, messages.size)
        assertEquals("letta-msg-10", messages.single().id)
    }

    @Test
    fun `listOlderConversationMessages tolerates explicit-null optional fields`() = runTest {
        val fake = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                ok("""[{"id":"letta-msg-10","message_type":"assistant_message","content":"older","tool_return":null}]""")
            }
        }

        val messages = transport(fake).listOlderConversationMessages("conv-1", "letta-msg-20", 20)

        assertEquals(1, messages.size)
    }

    /**
     * letta-mobile-w9k3f: MessageListPageGuard wraps a page as
     * { messages, has_more, next_before } whenever it trims an oversized window — i.e. on
     * exactly the long conversations users care about. Both message.list paths in this
     * transport decoded that wrapper straight into a ListSerializer and threw
     * "Expected JsonArray, but had JsonObject", so hydration failed and the conversation
     * rendered empty.
     */
    @Test
    fun `listConversationMessages decodes the trimmed wrapper shape`() = runTest {
        val fake = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                ok(
                    """{"messages":[{"id":"letta-msg-30","message_type":"assistant_message","content":"newest"}],""" +
                        """"has_more":true,"next_before":"letta-msg-30"}""",
                )
            }
        }

        val messages = transport(fake).listConversationMessages("conv-1", limit = 50)

        assertEquals(1, messages.size)
        assertEquals("letta-msg-30", messages.single().id)
    }

    @Test
    fun `typed page preserves trimmed next-before and ownership`() = runTest {
        val fake = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                ok(
                    """{"messages":[{"id":"letta-msg-31","message_type":"assistant_message","content":"older"}],""" +
                        """"has_more":true,"next_before":"letta-msg-31"}""",
                )
            }
        }
        val request = TimelineRemotePageRequest(
            scope = TimelineScope("backend", "conv-1"),
            requestId = TimelineRequestId("request-1"),
            selectionGeneration = TimelineSelectionGeneration(4),
            order = TimelineRemoteOrder.NewestFirst,
            continuation = TimelineContinuation.Before(TimelineMessageId("letta-msg-40")),
            budget = TimelinePageBudget(20, 1024),
        )

        val page = transport(fake).listConversationMessagePage(request) as TimelineRemotePageResult.Page

        assertEquals(request.requestId, page.requestId)
        assertEquals(request.selectionGeneration, page.selectionGeneration)
        assertEquals(TimelineContinuation.Before(TimelineMessageId("letta-msg-31")), page.nextContinuation)
        assertEquals(true, page.hasMore)
        assertTrue(fake.adminRpcCalls.single().path.contains("before=letta-msg-40"))
    }

    @Test
    fun `typed page URL encodes reserved continuation characters`() = runTest {
        val fake = FakeChannelTransport().apply { adminRpcHandler = { _, _, _ -> ok("[]") } }
        val request = typedRequest(TimelineContinuation.Before(TimelineMessageId("id/a?b&c=d")))

        transport(fake).listConversationMessagePage(request)

        assertTrue(fake.adminRpcCalls.single().path.contains("before=id%2Fa%3Fb%26c%3Dd"))
    }

    @Test
    fun `trimmed oldest-first initial derives after continuation`() = runTest {
        val fake = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""{"messages":[{"id":"first","message_type":"assistant_message","content":"a"},{"id":"last","message_type":"assistant_message","content":"b"}],"has_more":true,"next_before":"wrong-direction"}""") }
        }
        val request = typedRequest(TimelineContinuation.Initial, TimelineRemoteOrder.OldestFirst)

        val page = transport(fake).listConversationMessagePage(request) as TimelineRemotePageResult.Page

        assertEquals(TimelineContinuation.After(TimelineMessageId("last")), page.nextContinuation)
    }

    @Test
    fun `trimmed after request derives after continuation and reaches no-progress classifier`() = runTest {
        val previous = TimelineContinuation.After(TimelineMessageId("last"))
        val fake = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""{"messages":[{"id":"last","message_type":"assistant_message","content":"duplicate"}],"has_more":true,"next_before":"wrong-direction"}""") }
        }
        val request = typedRequest(previous, TimelineRemoteOrder.OldestFirst)

        val result = transport(fake).listConversationMessagePage(request, TimelinePageProgress(previous, 1, 0))

        assertTrue(result is TimelineRemotePageResult.NoProgress)
    }

    @Test
    fun `blank next-before is malformed for before request`() = runTest {
        val fake = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""{"messages":[{"id":"old","message_type":"assistant_message","content":"a"}],"has_more":true,"next_before":" "}""") }
        }

        assertThrows(TimelineRemotePageException.MalformedPage::class.java) {
            kotlinx.coroutines.runBlocking { transport(fake).listConversationMessagePage(typedRequest(TimelineContinuation.Before(TimelineMessageId("cursor")))) }
        }
    }

    private fun typedRequest(
        continuation: TimelineContinuation,
        order: TimelineRemoteOrder = TimelineRemoteOrder.NewestFirst,
    ) = TimelineRemotePageRequest(
        scope = TimelineScope("backend", "conv-1"),
        requestId = TimelineRequestId("request-extra"),
        selectionGeneration = TimelineSelectionGeneration(5),
        order = order,
        continuation = continuation,
        budget = TimelinePageBudget(20, 4096),
    )

    @Test
    fun `listOlderConversationMessages decodes the trimmed wrapper shape`() = runTest {
        val fake = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                ok(
                    """{"messages":[{"id":"letta-msg-31","message_type":"assistant_message","content":"older"}],""" +
                        """"has_more":true,"next_before":"letta-msg-31"}""",
                )
            }
        }

        val messages = transport(fake).listOlderConversationMessages("conv-1", "letta-msg-40", 20)

        assertEquals(1, messages.size)
        assertEquals("letta-msg-31", messages.single().id)
    }
}
