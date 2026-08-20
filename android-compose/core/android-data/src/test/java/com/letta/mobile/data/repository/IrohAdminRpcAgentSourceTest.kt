package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.util.Telemetry
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
    fun `listAgents routes to agent_list with pagination params and decodes`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""[{"id":"agent-1","name":"Lester"},{"id":"agent-2","name":"BMO"}]""") }
        }
        val agents = source(transport).listAgents()

        // letta-mobile-z5lqt: scope to agent.list. The roster-completeness
        // probe issues a separate agent.count read purely for telemetry; the
        // paging behaviour asserted here is unchanged.
        val call = transport.adminRpcCalls.single { it.method == "agent.list" }
        assertEquals("agent.list", call.method)
        assertTrue("path should carry pagination", call.path.contains("limit=") && call.path.contains("offset="))
        assertEquals(2, agents.size)
        assertEquals("agent-2", agents[1].id.value)
    }

    @Test
    fun `listAgents pages through all agents until a short page`() = runTest {
        // The server returns only a default page when unlimited, so agents beyond
        // it never resolve a name (letta-mobile-71orq). Verify the source pages
        // via offset and stops on a short page.
        val fullPage = (1..50).joinToString(",", "[", "]") { """{"id":"agent-$it","name":"A$it"}""" }
        val secondPage = """[{"id":"agent-51","name":"Lester"}]"""
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, path, _ ->
                if (path.contains("offset=0")) ok(fullPage) else ok(secondPage)
            }
        }

        val agents = source(transport).listAgents()

        assertEquals(51, agents.size)
        assertTrue("second page agent must be included", agents.any { it.id.value == "agent-51" })
        assertEquals(2, transport.adminRpcCalls.count { it.method == "agent.list" })
    }

    // --- letta-mobile-z5lqt: roster/name telemetry ---

    @Test
    fun `sweep emits a short page stop and a matching completeness outcome`() = runTest {
        Telemetry.delegate = null
        Telemetry.clear()
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, _ ->
                if (method == "agent.count") ok("2")
                else ok("""[{"id":"agent-1","name":"Lester"},{"id":"agent-2","name":"BMO"}]""")
            }
        }

        val agents = source(transport).listAgents()
        assertEquals(2, agents.size)

        val stop = Telemetry.snapshot().first { it.name == "roster.sweepStopped" }
        assertEquals("shortPage", stop.attrs["stop"])
        assertEquals(2, stop.attrs["mergedSize"])

        val completeness = Telemetry.snapshot().first { it.name == "roster.completeness" }
        assertEquals("match", completeness.attrs["completeness"])
        assertEquals(2, completeness.attrs["sweptSize"])
        assertEquals(2, completeness.attrs["authoritativeCount"])
        Telemetry.clear()
    }

    @Test
    fun `truncated sweep against a larger authoritative count reports a mismatch`() = runTest {
        Telemetry.delegate = null
        Telemetry.clear()
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, _ ->
                if (method == "agent.count") ok("131")
                else ok("""[{"id":"agent-1","name":"Lester"}]""")
            }
        }

        val agents = source(transport).listAgents()
        assertEquals(1, agents.size)

        val completeness = Telemetry.snapshot().first { it.name == "roster.completeness" }
        assertEquals("mismatch", completeness.attrs["completeness"])
        assertEquals(131, completeness.attrs["authoritativeCount"])
        assertEquals(-130, completeness.attrs["delta"])
        Telemetry.clear()
    }

    @Test
    fun `unavailable authoritative count reports unknown rather than match or mismatch`() = runTest {
        Telemetry.delegate = null
        Telemetry.clear()
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, _ ->
                if (method == "agent.count") {
                    AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = false, error = "boom")
                } else {
                    ok("""[{"id":"agent-1","name":"Lester"}]""")
                }
            }
        }

        // Resolution is untouched: the roster is still returned in full.
        val agents = source(transport).listAgents()
        assertEquals(1, agents.size)

        val completeness = Telemetry.snapshot().first { it.name == "roster.completeness" }
        assertEquals("unknown", completeness.attrs["completeness"])
        assertEquals("unknown", completeness.attrs["authoritativeCount"])
        Telemetry.clear()
    }

    @Test
    fun `a server that ignores offset emits the distinct no fresh stop`() = runTest {
        Telemetry.delegate = null
        Telemetry.clear()
        val fullPage = (1..50).joinToString(",", "[", "]") { """{"id":"agent-$it","name":"A$it"}""" }
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, _ ->
                if (method == "agent.count") ok("131") else ok(fullPage)
            }
        }

        val agents = source(transport).listAgents()
        assertEquals(50, agents.size)

        val stop = Telemetry.snapshot().first { it.name == "roster.sweepStopped" }
        assertEquals("noFreshIgnoredOffset", stop.attrs["stop"])
        assertEquals(50, stop.attrs["pageSize"])
        assertEquals(50, stop.attrs["mergedSize"])
        Telemetry.clear()
    }
}
