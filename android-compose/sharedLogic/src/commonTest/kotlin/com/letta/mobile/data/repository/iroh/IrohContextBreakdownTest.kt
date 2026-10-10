package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.context.ContextBreakdownLoader
import com.letta.mobile.data.context.ContextBreakdownRequest
import com.letta.mobile.data.context.ContextBreakdownState
import com.letta.mobile.data.context.ContextBreakdownUnavailable
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * letta-mobile-cyh28: over iroh:// the breakdown is the allowlisted `agent.context` admin_rpc
 * carrying the client's streamed total; a host without the local store degrades to total-only.
 */
class IrohContextBreakdownTest {
    private val request = ContextBreakdownRequest(AgentId("agent-1"), ConversationId("conv-1"), reportedTotal = 40_000)

    @Test
    fun theStreamedTotalTravelsAsReportedTotalAndTheProvenanceComesBack() = runTest {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call ->
            assertEquals("agent.context", call.method)
            val body = Json.parseToJsonElement(call.body.orEmpty()).jsonObject
            assertEquals(40_000, body.getValue("reported_total").jsonPrimitive.int)
            assertEquals("conv-1", body.getValue("conversation_id").jsonPrimitive.content)
            ok(
                """{"context_window_size_current":40000,"context_window_size_max":200000,"num_tokens_system":4000,
                   "num_tokens_messages":30000,"num_tokens_functions_definitions":6000,"source":"estimate",
                   "calibrated":true,"tools_derived":true,"total_source":"client","memory_split":false}""",
            )
        }
        val directory = IrohAdminRpcAgentDirectory(transport)
        val loader = ContextBreakdownLoader { directory.getContextWindow(it.agentId, it.conversationId, it.reportedTotal) }

        val overview = assertIs<ContextBreakdownState.Loaded>(loader.load(request)).overview
        assertEquals("estimate", overview.source)
        assertEquals(true, overview.calibrated)
        assertEquals("client", overview.totalSource)
        assertEquals(false, overview.memorySplit)
    }

    @Test
    fun aWrapperWithoutTheLocalStoreIsNotSupported() = runTest {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = {
            fail("capability_unavailable: 'agent.context' has no injected 'local_backend_store' service on this controller")
        }
        val directory = IrohAdminRpcAgentDirectory(transport)
        val loader = ContextBreakdownLoader { directory.getContextWindow(it.agentId, it.conversationId, it.reportedTotal) }

        val state = assertIs<ContextBreakdownState.Unavailable>(loader.load(request))
        assertEquals(ContextBreakdownUnavailable.NotSupported, state.reason)
    }

    private fun ok(resultJson: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req-1",
        success = true,
        result = Json.parseToJsonElement(resultJson),
    )

    private fun fail(error: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req-1",
        success = false,
        error = error,
    )
}
