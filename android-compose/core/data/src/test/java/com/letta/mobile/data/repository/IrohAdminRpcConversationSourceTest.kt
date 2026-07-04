package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
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
 * letta-mobile-qfa81 (P4 rows 3-6): client wiring for conversation B-tier
 * reads/writes over admin_rpc. Verifies method/path shape and result decoding.
 */
class IrohAdminRpcConversationSourceTest {
    private fun source(transport: FakeChannelTransport): IrohAdminRpcConversationListSource {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket",
            ),
        )
        return IrohAdminRpcConversationListSource(transport, settings)
    }

    private fun ok(result: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req",
        success = true,
        result = Json.parseToJsonElement(result),
    )

    @Test
    fun `getConversation routes to conversation_get and decodes`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""{"id":"conv-1","agent_id":"agent-1","summary":"hi"}""") }
        }
        val conv = source(transport).getConversation(ConversationId("conv-1"))

        val call = transport.adminRpcCalls.single()
        assertEquals("conversation.get", call.method)
        assertEquals("/v1/conversations/conv-1", call.path)
        assertEquals("conv-1", conv.id.value)
        assertEquals("hi", conv.summary)
    }

    @Test
    fun `createConversation routes to conversation_create with agent id in body`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""{"id":"conv-2","agent_id":"agent-1"}""") }
        }
        val conv = source(transport).createConversation(AgentId("agent-1"), summary = "new")

        val call = transport.adminRpcCalls.single()
        assertEquals("conversation.create", call.method)
        assertEquals("/v1/agents/agent-1/conversations", call.path)
        assertTrue(call.body.orEmpty().contains("\"agent_id\":\"agent-1\""))
        assertTrue(call.body.orEmpty().contains("\"summary\":\"new\""))
        assertEquals("conv-2", conv.id.value)
    }

    @Test
    fun `deleteConversation routes to conversation_delete`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> AppServerInboundFrame.AdminRpcResponse("req", success = true) }
        }
        source(transport).deleteConversation(ConversationId("conv-1"))

        val call = transport.adminRpcCalls.single()
        assertEquals("conversation.delete", call.method)
        assertEquals("/v1/conversations/conv-1", call.path)
    }

    @Test
    fun `setConversationArchived routes archive and restore to distinct methods`() = runTest {
        val archived = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""{"id":"c","agent_id":"a","archived":true}""") }
        }
        source(archived).setConversationArchived(ConversationId("c"), archived = true)
        assertEquals("conversation.archive", archived.adminRpcCalls.single().method)
        assertEquals("/v1/conversations/c", archived.adminRpcCalls.single().path)

        val restored = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""{"id":"c","agent_id":"a","archived":false}""") }
        }
        source(restored).setConversationArchived(ConversationId("c"), archived = false)
        assertEquals("conversation.restore", restored.adminRpcCalls.single().method)
        assertEquals("/v1/conversations/c", restored.adminRpcCalls.single().path)
    }

    @Test
    fun `failure envelope surfaces as error`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse("req", success = false, error = "boom")
            }
        }
        val thrown = runCatching { source(transport).getConversation(ConversationId("conv-1")) }.exceptionOrNull()
        assertTrue(thrown!!.message.orEmpty().contains("boom"))
    }
}
