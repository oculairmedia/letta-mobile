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

        val router = AdminRpcRegistry.buildRouter(adminBaseUrl = "http://test")

        suspend fun checkNativeFailClosed(method: String, params: kotlinx.serialization.json.JsonObject?) {
            transport.lastMethod = null
            transport.lastUrl = null
            val response = router.dispatch("req", method, params)
            assertTrue(response.contains("\"success\":false"), "Expected fail-closed for $method, got $response")
            assertTrue(response.contains("capability_unavailable") || response.contains("app_server_error"), response)
            assertEquals(null, transport.lastMethod, "$method must not dial admin proxy")
        }

        suspend fun checkNativeWithoutProxy(method: String, params: kotlinx.serialization.json.JsonObject?) =
            router.assertServedNatively(transport, method, params)

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
        checkNativeFailClosed("goal.get", buildJsonObject { put("agent_id", "ag-1") })
        checkNativeFailClosed("slash_command.list", null)

        // lgns8.9: there is no admin REST proxy left. The former admin_rest rows
        // now answer from a controller-owned constant (empty-by-contract lists and
        // the builtin tool catalog) or fail closed — and NONE of them dial HTTP.
        checkNativeWithoutProxy("archive.list", null)
        checkNativeWithoutProxy("identity.list", null)
        checkNativeWithoutProxy("mcp.list", null)
        checkNativeWithoutProxy("folder.list", buildJsonObject { put("agent_id", "ag-1") })
        checkNativeWithoutProxy("group.list", null)
        checkNativeWithoutProxy("job.list", null)
        checkNativeWithoutProxy("tool.list", null)
        checkNativeWithoutProxy("model.list.embedding", null)
        checkNativeWithoutProxy("provider.list", null)

        // Store-owned reads and native-owned schedule writes fail closed when
        // unwired — again without a proxy dial.
        checkNativeFailClosed("run.list", null)
        checkNativeFailClosed("schedule.list", null)
        checkNativeFailClosed("agent.context", buildJsonObject { put("agent_id", "ag-1") })
    }

    /** Serves successfully from controller-owned state, and never dials the proxy. */
    private suspend fun AdminRpcRouter.assertServedNatively(
        transport: FakeTransport,
        method: String,
        params: kotlinx.serialization.json.JsonObject?,
    ) {
        transport.lastMethod = null
        transport.lastUrl = null
        val response = dispatch("req", method, params)
        assertTrue(response.contains("\"success\":true"), "Expected controller-native success for $method, got $response")
        assertEquals(null, transport.lastMethod, "$method must not dial admin proxy")
    }
}
