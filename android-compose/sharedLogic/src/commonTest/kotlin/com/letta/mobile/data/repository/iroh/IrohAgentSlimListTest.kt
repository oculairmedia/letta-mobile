package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.iroh.WireContractFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The roster decodes the host's slim `agent.list` rows (agent.list/slim-list-v1)
 * with every list field intact, and the detail a screen opens comes from `agent.get`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohAgentSlimListTest {
    private val contract = WireContractFixture(agentListSlimContractText)
    private val hostRows = contract.cases.single { it.getValue("name").jsonPrimitive.content == "list-fields" }
        .getValue("host_state").jsonObject.getValue("agents").jsonArray.map { it.jsonObject }

    @Test
    fun theRosterKeepsListFieldsAndOpeningAnAgentReadsItsDetail() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport().apply { rpcResponder = ::answer }
        val repository = IrohAgentRepository(directoryProvider = { IrohAdminRpcAgentDirectory(transport) })

        repository.refreshAgents()
        val listed = repository.agents.value.single { it.id == ADA }
        assertEquals(listOf("agent-ada", "agent-bob"), repository.agents.value.map { it.id.value })
        assertEquals("anthropic/claude-sonnet-5-5", listed.model)
        assertEquals(200_000, listed.llmConfig?.contextWindow)
        assertEquals("2026-11-01T03:16:48.945Z", listed.lastRunCompletion)
        assertNull(listed.system, "a list row never carries the system prompt")
        assertEquals(listOf("agent.list"), transport.rpcCalls.map { it.method }, "the list needs no per-agent read")

        val detail = repository.getFreshAgent(ADA)
        assertNotNull(detail.system, "the detail comes from agent.get")
        assertEquals(listOf("agent.list", "agent.get"), transport.rpcCalls.map { it.method })
        assertFailsWith<NoSuchElementException> { repository.getFreshAgent(AgentId("agent-404")) }
    }

    @Test
    fun aSlimRosterRefreshKeepsDetailAnEarlierReadCached() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport().apply { rpcResponder = ::answer }
        val repository = IrohAgentRepository(directoryProvider = { IrohAdminRpcAgentDirectory(transport) })
        val detail = repository.getFreshAgent(ADA)

        repository.refreshAgents()

        val cached = repository.agents.value.single { it.id == ADA }
        assertEquals(detail.system, cached.system, "a list row never erases cached detail")
        assertEquals(detail.model, cached.model)
    }

    /** `agent.list` answers with the contract's envelope; `agent.get` answers like the host: the whole row, or a refusal. */
    private fun answer(call: AdminRpcCall): AppServerInboundFrame.AdminRpcResponse = when (call.method) {
        "agent.list" -> listPage(call)
        "agent.get" -> detailOf(call)
        else -> error("Unexpected admin_rpc ${call.method}")
    }

    private fun listPage(call: AdminRpcCall): AppServerInboundFrame.AdminRpcResponse {
        val offset = Json.parseToJsonElement(call.body.orEmpty()).jsonObject.getValue("offset").jsonPrimitive.content
        val envelope = contract.response("list-fields")
        val rows = if (offset == "0") envelope.getValue("result") else JsonArray(emptyList())
        return AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = true, result = rows)
    }

    private fun detailOf(call: AdminRpcCall): AppServerInboundFrame.AdminRpcResponse {
        val id = Json.parseToJsonElement(call.body.orEmpty()).jsonObject.getValue("agent_id").jsonPrimitive.content
        val row: JsonObject? = hostRows.firstOrNull { it.getValue("id").jsonPrimitive.content == id }
        return row?.let { AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = true, result = it) }
            ?: AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = false,
                error = "app_server_error: agent.get native command unsuccessful",
            )
    }

    private companion object {
        val ADA = AgentId("agent-ada")
    }
}
