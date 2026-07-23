package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * lgns8.9: the bounded admin_rest_service adapters are injected. With no
 * admin-rest service configured (adminRestBaseUrl = null) every one of the 40
 * bounded methods returns capability-unavailable and NEVER dials lettashim — a
 * shim-less deployment degrades gracefully without failing chat.
 */
class AdminRestServiceInjectionTest {
    private var savedFactory: (() -> AdminProxyTransport)? = null
    private val dialed = mutableListOf<String>()

    @BeforeTest
    fun recordDials() {
        savedFactory = AdminProxyClient.defaultTransportFactory
        AdminProxyClient.defaultTransportFactory = {
            AdminProxyTransport { _, url, _ -> dialed += url; error("no dial expected") }
        }
    }

    @AfterTest
    fun restore() {
        savedFactory?.let { AdminProxyClient.defaultTransportFactory = it }
        dialed.clear()
    }

    private object EmptySubagentSource : SubagentRegistrySource {
        override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> = emptyList()

        override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? = null
    }

    private val adminRestMethods = RunAdminHandlers.METHODS + ArchiveAdminHandlers.METHODS +
        IdentityAdminHandlers.METHODS + ModelAdminHandlers.METHODS + ScheduleAdminHandlers.METHODS +
        ToolAdminHandlers.METHODS + McpAdminHandlers.METHODS + GoalAdminHandlers.METHODS +
        SlashCommandAdminHandlers.METHODS

    @Test
    fun theBoundedAdminRestMethodsAreEnumerated() {
        assertTrue(adminRestMethods.size == 40, "expected 40 admin_rest methods, got ${adminRestMethods.size}")
    }

    @Test
    fun withNoAdminRestServiceEveryMethodIsCapabilityUnavailableAndNeverDialsShim() = runTest {
        val router = AdminRpcRegistry.buildRouter(
            adminBaseUrl = "http://127.0.0.1:8291",
            controller = null,
            subagentRegistrySource = EmptySubagentSource,
            pairingService = IrohPairingService(InMemoryPairedPeerStore()),
            nativeClient = null,
            adminRestBaseUrl = null, // shim-less: no admin-rest service
            vibesyncBaseUrl = null,
        )
        adminRestMethods.forEach { method ->
            val response = router.dispatch(
                AdminRpcInvocation(
                    requestId = "t",
                    method = method,
                    params = buildJsonObject { put("run_id", "r"); put("agent_id", "a"); put("id", "i"); put("name", "n") },
                    context = AdminRpcRequestContext.Authenticated,
                ),
            )
            assertTrue(response.contains("\"success\":false"), "$method should fail closed")
            assertTrue(response.contains("capability_unavailable"), "$method should be capability-unavailable: $response")
        }
        assertTrue(dialed.isEmpty(), "capability-unavailable must never dial lettashim: $dialed")
    }
}
