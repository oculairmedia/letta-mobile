package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-qfa81 (P4 row 13): client-side wiring for approval submission
 * over admin_rpc. Verifies the source routes to `approval.submit` with the
 * correct payload while iroh:// is active, and stays off admin_rpc for HTTP
 * backends. The server handler round-trip is covered by
 * ApprovalAdminHandlersTest in :sharedLogic.
 */
class IrohAdminRpcApprovalSourceTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val approvalRequest = MessageCreateRequest(
        messages = listOf(
            buildJsonObject {
                put("type", "approval")
                put("approval_request_id", "approval-1")
                put("approve", true)
            },
        ),
        streaming = false,
    )

    @Test
    fun `submitApproval issues approval_submit admin_rpc with agent id and payload`() = runTest {
        var capturedBody: String? = null
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, body ->
                capturedBody = body
                AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req",
                    success = true,
                    result = null,
                )
            }
        }
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket",
            ),
        )
        val source = IrohAdminRpcApprovalSource(transport, settings)

        assertTrue(source.shouldUseIroh())
        source.submitApproval(AgentId("agent-1"), approvalRequest)

        val call = transport.adminRpcCalls.single()
        assertEquals("approval.submit", call.method)
        assertEquals("/v1/agents/agent-1/messages", call.path)

        // Body carries the agent id and the full MessageCreateRequest payload
        // (the approval message) the server forwards verbatim.
        val body = json.parseToJsonElement(capturedBody!!).jsonObject
        assertEquals("agent-1", body.getValue("agent_id").jsonPrimitive.content)
        val payload = body.getValue("payload").jsonObject
        assertEquals(false, payload.getValue("streaming").jsonPrimitive.content.toBoolean())
        val approval = payload.getValue("messages").let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject }
        assertEquals("approval-1", approval.getValue("approval_request_id").jsonPrimitive.content)
    }

    @Test
    fun `submitApproval surfaces failure envelope as error`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req",
                    success = false,
                    error = "backend rejected approval",
                )
            }
        }
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket",
            ),
        )
        val source = IrohAdminRpcApprovalSource(transport, settings)

        val thrown = runCatching { source.submitApproval(AgentId("agent-1"), approvalRequest) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown!!.message.orEmpty().contains("backend rejected approval"))
    }

    @Test
    fun `shouldUseIroh is false for http backend so approval stays on http path`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "http",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "https://admin.example/",
            ),
        )
        val transport = FakeChannelTransport()
        val source = IrohAdminRpcApprovalSource(transport, settings)

        assertFalse(source.shouldUseIroh())
        assertTrue(transport.adminRpcCalls.isEmpty())
    }
}
