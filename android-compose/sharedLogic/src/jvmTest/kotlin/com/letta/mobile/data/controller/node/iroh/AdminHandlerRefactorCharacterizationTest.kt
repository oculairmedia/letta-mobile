package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization of remaining admin_rest_service proxy routes after Phase 2
 * removed native→shim fallbacks for runtime-owned operations.
 */
class AdminHandlerRefactorCharacterizationTest {
    class FakeTransport : AdminProxyTransport {
        var lastMethod: String? = null
        var lastUrl: String? = null
        var lastBody: String? = null

        override fun execute(method: String, url: String, body: String?): AdminProxyTransportResponse {
            lastMethod = method
            lastUrl = url
            lastBody = body
            return AdminProxyTransportResponse(200, "{\"result\": {}}")
        }
    }

    private var savedFactory: (() -> AdminProxyTransport)? = null

    @BeforeTest
    fun install() {
        NativeAdmin.resetCircuitForTest()
        savedFactory = AdminProxyClient.defaultTransportFactory
    }

    @AfterTest
    fun restore() {
        savedFactory?.let { AdminProxyClient.defaultTransportFactory = it }
    }

    @Test
    fun characterizationTest() = runTest {
        val transport = FakeTransport()
        AdminProxyClient.defaultTransportFactory = { transport }

        val router = AdminRpcRegistry.buildRouter("http://test")

        suspend fun check(method: String, params: kotlinx.serialization.json.JsonObject?, expectedMethod: String, expectedUrl: String) {
            transport.lastMethod = null
            transport.lastUrl = null
            val response = router.dispatch("req", method, params)
            assertTrue(response.contains("\"success\":true"), "Expected success for $method, got $response")
            assertEquals(expectedMethod, transport.lastMethod, "Method mismatch for $method")
            assertEquals(expectedUrl, transport.lastUrl, "Url mismatch for $method")
        }

        suspend fun checkNativeFailClosed(method: String, params: kotlinx.serialization.json.JsonObject?) {
            transport.lastMethod = null
            transport.lastUrl = null
            val response = router.dispatch("req", method, params)
            assertTrue(response.contains("\"success\":false"), "Expected fail-closed for $method, got $response")
            assertTrue(response.contains("capability_unavailable") || response.contains("app_server_error"), response)
            assertEquals(null, transport.lastMethod, "$method must not dial admin proxy")
        }

        checkNativeFailClosed("agent.get", buildJsonObject { put("agent_id", "ag-1") })
        checkNativeFailClosed("approval.submit", buildJsonObject {
            put("agent_id", "ag-1")
            put("payload", buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("approval_request_id", "ar-1")
                        put("approve", true)
                        put("approvals", buildJsonArray {
                            add(buildJsonObject { put("tool_call_id", "tc-123") })
                        })
                    })
                })
            })
        })
        checkNativeFailClosed("conversation.create", buildJsonObject { put("agent_id", "ag-1") })
        checkNativeFailClosed("model.list", null)

        check("archive.list", null, "GET", "http://test/v1/archives")
        check("goal.get", buildJsonObject { put("agent_id", "ag-1") }, "GET", "http://test/v1/agents/ag-1/goal")
        check("identity.list", null, "GET", "http://test/v1/identities")
        check("mcp.list", null, "GET", "http://test/v1/mcp/servers")
        check("run.list", null, "GET", "http://test/v1/runs")
        check("schedule.list", null, "GET", "http://test/v1/schedules")
        check("tool.list", null, "GET", "http://test/v1/tools")
    }
}
