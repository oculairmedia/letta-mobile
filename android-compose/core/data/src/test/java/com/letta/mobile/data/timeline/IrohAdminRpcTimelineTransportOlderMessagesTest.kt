package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
