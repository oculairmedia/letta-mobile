package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-kzqkr.8: [IrohAdminRpcRunSource.listRuns] must encode every
 * [RunListParams] field the server's `run.list` admin_rpc handler
 * (`RunAdminHandlers`) understands rather than dropping them, and must reject
 * combinations the server cannot express (non-singleton agent ids, conflicting
 * agent/order overrides, invalid order values, any `orderBy` other than the server's fixed
 * `created_at`) loudly instead of silently ignoring them.
 */
class IrohAdminRpcRunSourceTest {
    private fun source(transport: FakeChannelTransport): IrohAdminRpcRunSource {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket",
            ),
        )
        return IrohAdminRpcRunSource(transport, settings)
    }

    private fun ok(result: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req",
        success = true,
        result = Json.parseToJsonElement(result),
    )

    @Test
    fun `listRuns encodes every supported filter and omits nulls`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        source(transport).listRuns(
            RunListParams(
                agentId = "agent-1",
                conversationId = "conv-1",
                active = true,
                background = false,
                statuses = listOf("running", "completed"),
                stopReason = "end_turn",
                before = "cursor-before",
                after = "cursor-after",
                limit = 25,
                order = "asc",
                orderBy = "created_at",
            )
        )

        val call = transport.adminRpcCalls.single()
        assertEquals("run.list", call.method)
        assertEquals("/v1/runs", call.path)
        assertEquals(
            "{\"agent_id\":\"agent-1\",\"conversation_id\":\"conv-1\",\"active\":true," +
                "\"background\":false,\"statuses\":[\"running\",\"completed\"]," +
                "\"stop_reason\":\"end_turn\",\"before\":\"cursor-before\",\"after\":\"cursor-after\"," +
                "\"limit\":25,\"order\":\"asc\",\"order_by\":\"created_at\"}",
            call.body,
        )
    }

    @Test
    fun `listRuns with no params sends an empty object`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        source(transport).listRuns(RunListParams())

        val call = transport.adminRpcCalls.single()
        assertEquals("run.list", call.method)
        assertEquals("/v1/runs", call.path)
        assertEquals("{}", call.body)
    }

    @Test
    fun `listRuns maps a singleton agentIds onto agent_id`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        source(transport).listRuns(RunListParams(agentIds = listOf("agent-only")))

        val call = transport.adminRpcCalls.single()
        assertEquals("{\"agent_id\":\"agent-only\"}", call.body)
    }

    @Test
    fun `listRuns rejects empty agentIds`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                source(transport).listRuns(RunListParams(agentIds = emptyList()))
            }
        }
        assertTrue(ex.message.orEmpty().contains("single agent_id"))
        assertTrue("must not broaden an empty agent selection", transport.adminRpcCalls.isEmpty())
    }

    @Test
    fun `listRuns rejects multiple agentIds`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                source(transport).listRuns(RunListParams(agentIds = listOf("a1", "a2")))
            }
        }
        assertTrue(ex.message.orEmpty().contains("single agent_id"))
        assertTrue("must not silently drop the second agent id", transport.adminRpcCalls.isEmpty())
    }

    @Test
    fun `listRuns rejects conflicting agentId and agentIds`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                source(transport).listRuns(RunListParams(agentId = "a1", agentIds = listOf("a2")))
            }
        }
        assertTrue(ex.message.orEmpty().contains("conflicting"))
        assertTrue(transport.adminRpcCalls.isEmpty())
    }

    @Test
    fun `listRuns rejects invalid explicit order`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                source(transport).listRuns(RunListParams(order = "sideways"))
            }
        }
        assertTrue(ex.message.orEmpty().contains("'asc' or 'desc'"))
        assertTrue("must not let the server reinterpret invalid order", transport.adminRpcCalls.isEmpty())
    }

    @Test
    fun `listRuns normalizes explicit order case`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        source(transport).listRuns(RunListParams(order = "ASC"))

        assertEquals("{\"order\":\"asc\"}", transport.adminRpcCalls.single().body)
    }

    @Test
    fun `listRuns rejects conflicting order and ascending`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                source(transport).listRuns(RunListParams(order = "asc", ascending = false))
            }
        }
        assertTrue(ex.message.orEmpty().contains("conflicting"))
        assertTrue(transport.adminRpcCalls.isEmpty())
    }

    @Test
    fun `listRuns maps ascending onto order when non-conflicting`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        source(transport).listRuns(RunListParams(ascending = true))

        val call = transport.adminRpcCalls.single()
        assertEquals("{\"order\":\"asc\"}", call.body)
    }

    @Test
    fun `listRuns rejects orderBy other than created_at`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[]") }
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                source(transport).listRuns(RunListParams(orderBy = "status"))
            }
        }
        assertTrue(ex.message.orEmpty().contains("order_by=created_at"))
        assertTrue(transport.adminRpcCalls.isEmpty())
    }

    @Test
    fun `listRuns failure envelope surfaces as error`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse("req", success = false, error = "boom")
            }
        }
        val thrown = runCatching { source(transport).listRuns(RunListParams()) }.exceptionOrNull()
        assertTrue(thrown!!.message.orEmpty().contains("boom"))
    }
}
