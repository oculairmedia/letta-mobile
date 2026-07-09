package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals

class AdminHandlerRefactorCharacterizationTest {

    private val adminBaseUrl = "http://test.local"

    private class CapturingTransport : AdminProxyTransport {
        val requests = mutableListOf<Triple<String, String, String?>>()

        override fun execute(method: String, url: String, body: String?): AdminProxyTransportResponse {
            requests.add(Triple(method, url, body))
            // Return a dummy empty JSON to avoid throwing AdminProxyHttpException
            return AdminProxyTransportResponse(200, "{}")
        }
    }

    @Test
    fun characterizeAllHandlers() = runTest {
        val transport = CapturingTransport()
        AdminProxyClient.defaultTransportFactory = { transport }

        val router = AdminRpcRegistry.buildRouter(adminBaseUrl)

        // AgentAdminHandlers
        router.dispatch("1", "agent.list", buildJsonObject { put("limit", 10) })
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/agents?limit=10")

        // ArchiveAdminHandlers
        router.dispatch("2", "archive.list", null)
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/archives")

        // ConversationAdminHandlers
        router.dispatch("3", "conversation.list", null)
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/conversations")

        // GoalAdminHandlers
        router.dispatch("4", "goal.get", buildJsonObject { put("agent_id", "agent_123") })
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/agents/agent_123/goal")

        // IdentityAdminHandlers
        router.dispatch("5", "identity.list", null)
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/identities")

        // McpAdminHandlers
        router.dispatch("6", "mcp.list", null)
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/mcp/servers")

        // ModelAdminHandlers
        router.dispatch("7", "model.list", null)
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/models")

        // RunAdminHandlers
        router.dispatch("8", "run.list", null)
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/runs")

        // ScheduleAdminHandlers
        router.dispatch("9", "schedule.list", null)
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/schedules")

        // ToolAdminHandlers
        router.dispatch("10", "tool.list", null)
        assertLastRequest(transport, "GET", "$adminBaseUrl/v1/tools")

        // HealthAdminHandlers - uses distinct HttpProxyTransport instantiated directly in HealthAdminHandlers
        // We can't capture this using defaultTransportFactory without changes to HealthAdminHandlers.

        // ApprovalAdminHandlers - complex payload, skip for now.
    }

    private fun assertLastRequest(transport: CapturingTransport, expectedMethod: String, expectedUrl: String) {
        val last = transport.requests.last()
        assertEquals(expectedMethod, last.first)
        assertEquals(expectedUrl, last.second)
    }
}
