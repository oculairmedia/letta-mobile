package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
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
 * letta-mobile-71orq: client wiring for agent reads over admin_rpc so the chat
 * screen does not hard-fail at the P4 purity choke-point in iroh:// mode.
 */
class IrohAdminRpcAgentSourceTest {
    private fun source(transport: FakeChannelTransport): IrohAdminRpcAgentSource {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket",
            ),
        )
        return IrohAdminRpcAgentSource(transport, settings)
    }

    private fun ok(result: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req",
        success = true,
        result = Json.parseToJsonElement(result),
    )

    @Test
    fun `shouldUseIroh true for iroh backend`() {
        assertTrue(source(FakeChannelTransport()).shouldUseIroh())
    }

    @Test
    fun `getAgent routes to agent_get with agent id and decodes`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""{"id":"agent-1","name":"Lester"}""") }
        }
        val agent = source(transport).getAgent(AgentId("agent-1"))

        val call = transport.adminRpcCalls.single()
        assertEquals("agent.get", call.method)
        assertEquals("/v1/agents/agent-1", call.path)
        assertTrue(call.body.orEmpty().contains("\"agent_id\":\"agent-1\""))
        assertEquals("agent-1", agent.id.value)
        assertEquals("Lester", agent.name)
    }

    @Test
    fun `getAgent coerces explicit null metadata to default`() = runTest {
        // The server serializes optional fields as explicit null; the decoder
        // must coerce "metadata": null to the empty-map default rather than
        // failing (letta-mobile-71orq — surfaced on-device after the choke-point
        // fix let agent.get results reach the decoder).
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""{"id":"agent-1","name":"Lester","metadata":null}""") }
        }
        val agent = source(transport).getAgent(AgentId("agent-1"))

        assertEquals("agent-1", agent.id.value)
        assertTrue(agent.metadata.isEmpty())
    }

    @Test
    fun `listAgents routes to agent_list and decodes`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""[{"id":"agent-1","name":"Lester"},{"id":"agent-2","name":"BMO"}]""") }
        }
        val agents = source(transport).listAgents()

        val call = transport.adminRpcCalls.single()
        assertEquals("agent.list", call.method)
        assertEquals("/v1/agents", call.path)
        assertEquals(2, agents.size)
        assertEquals("agent-2", agents[1].id.value)
    }
}
