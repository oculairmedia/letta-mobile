package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpSubagentRegistrySourceTest {
    @Test
    fun discoveryRequiresExplicitRestCapability() = runTest {
        withTransport(
            response("""{"subagent_registry_v1":{"available":true,"transport":"rest"}}"""),
        ) {
            assertNotNull(HttpSubagentRegistrySource.discover(BASE_URL))
        }
        withTransport(response("""{"mobile_transport":{}}""")) {
            assertNull(HttpSubagentRegistrySource.discover(BASE_URL))
        }
    }

    @Test
    fun listAndTodosUseConversationScopedShimEndpoints() = runTest {
        val requests = mutableListOf<String>()
        val transport = AdminProxyTransport { _, url, _ ->
            requests += url
            when {
                url.contains("/todos?") -> response(TODOS_RESPONSE)
                else -> response(LIST_RESPONSE)
            }
        }
        val source = HttpSubagentRegistrySource(AdminProxyClient(BASE_URL, transport))

        val entries = source.list("conv parent", includeTerminal = true)
        val snapshot = source.todos("conv parent", "tool/1")

        assertEquals(listOf("tool/1"), entries.map { it.toolCallId })
        assertNotNull(snapshot)
        assertTrue(snapshot.todosFound)
        assertEquals("Ship fix", snapshot.todos.single().content)
        assertEquals(
            listOf(
                "$BASE_URL/shim/v1/subagents?conversation_id=conv%20parent&all=true",
                "$BASE_URL/shim/v1/subagents/tool%2F1/todos?conversation_id=conv%20parent",
            ),
            requests,
        )
    }

    @Test
    fun missingTodosEntryMapsToNull() = runTest {
        val source = HttpSubagentRegistrySource(
            AdminProxyClient(BASE_URL, AdminProxyTransport { _, _, _ -> response("""{"found":false}""") }),
        )
        assertNull(source.todos("conv-a", "missing"))
    }

    @Test
    fun capabilityFollowsWorkingRouterSource() {
        val withoutSource = AdminRpcRegistry.buildRouter(BASE_URL)
        val withSource = AdminRpcRegistry.buildRouter(BASE_URL, subagentRegistrySource = EmptySource)

        assertFalse(HttpSubagentRegistrySource.CAPABILITY in IrohNodeConnection.advertisedCapabilities(withoutSource))
        assertTrue(HttpSubagentRegistrySource.CAPABILITY in IrohNodeConnection.advertisedCapabilities(withSource))
        assertFalse("subagent.list" in withoutSource.registeredMethods)
        assertTrue("subagent.list" in withSource.registeredMethods)
    }

    private suspend fun withTransport(response: AdminProxyTransportResponse, block: suspend () -> Unit) {
        val original = AdminProxyClient.defaultTransportFactory
        AdminProxyClient.defaultTransportFactory = { AdminProxyTransport { _, _, _ -> response } }
        try {
            block()
        } finally {
            AdminProxyClient.defaultTransportFactory = original
        }
    }

    private fun response(body: String) = AdminProxyTransportResponse(200, body)

    private object EmptySource : SubagentRegistrySource {
        override suspend fun list(conversationId: String, includeTerminal: Boolean) =
            emptyList<com.letta.mobile.data.model.SubagentEntry>()

        override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? = null
    }

    private companion object {
        const val BASE_URL = "http://127.0.0.1:8283"
        const val LIST_RESPONSE = """{"subagents":[{"toolCallId":"tool/1","status":"running","parentConversationId":"conv parent"}]}"""
        const val TODOS_RESPONSE = """{"found":true,"subagent":{"toolCallId":"tool/1","status":"running","parentConversationId":"conv parent"},"todos":[{"content":"Ship fix","status":"in_progress","activeForm":"Shipping fix"}],"todos_found":true}"""
    }
}
