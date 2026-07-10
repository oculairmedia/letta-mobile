package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [IrohAdminRoute] repository-boundary decorators (audit-Q5).
 */
class IrohAdminRouteTest {

    @Serializable
    private data class Widget(val id: String, val name: String)

    private fun createClient(shouldUseIroh: Boolean, transport: FakeChannelTransport): IrohAdminRpcClient {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "t",
                mode = if (shouldUseIroh) LettaConfig.Mode.SELF_HOSTED else LettaConfig.Mode.CLOUD,
                serverUrl = if (shouldUseIroh) "iroh://test" else "https://api.letta.com",
                accessToken = "t",
            ),
        )
        return IrohAdminRpcClient(transport, settings)
    }

    private fun transportReturning(
        success: Boolean,
        result: kotlinx.serialization.json.JsonElement? = null,
        error: String? = null,
    ) = FakeChannelTransport().apply {
        adminRpcHandler = { _, _, _ ->
            AppServerInboundFrame.AdminRpcResponse("req", success = success, result = result, error = error)
        }
    }

    @Test
    fun `irohAdminRoute routes to client when shouldUseIroh is true`() = runTest {
        val json = Json.encodeToJsonElement(Widget.serializer(), Widget("w1", "iroh"))
        val transport = transportReturning(success = true, result = json)
        val client = createClient(shouldUseIroh = true, transport = transport)

        var fallbackCalled = false
        val result: Widget = irohAdminRoute(client, "widget.get", "/v1/widgets/w1") {
            fallbackCalled = true
            Widget("w1", "http")
        }

        assertEquals(Widget("w1", "iroh"), result)
        assertEquals(1, transport.adminRpcCalls.size)
        assertEquals("widget.get", transport.adminRpcCalls.first().method)
        assertEquals(false, fallbackCalled)
    }

    @Test
    fun `irohAdminRoute routes to fallback when shouldUseIroh is false`() = runTest {
        val transport = transportReturning(success = true, result = JsonNull)
        val client = createClient(shouldUseIroh = false, transport = transport)

        var fallbackCalled = false
        val result: Widget = irohAdminRoute(client, "widget.get", "/v1/widgets/w1") {
            fallbackCalled = true
            Widget("w1", "http")
        }

        assertEquals(Widget("w1", "http"), result)
        assertEquals(0, transport.adminRpcCalls.size)
        assertTrue(fallbackCalled)
    }

    @Test
    fun `irohAdminRoute routes to fallback when client is null`() = runTest {
        var fallbackCalled = false
        val result: Widget = irohAdminRoute(null, "widget.get", "/v1/widgets/w1") {
            fallbackCalled = true
            Widget("w1", "http")
        }

        assertEquals(Widget("w1", "http"), result)
        assertTrue(fallbackCalled)
    }

    @Test
    fun `irohAdminRouteList routes to client when shouldUseIroh is true`() = runTest {
        val list = listOf(Widget("a", "A"), Widget("b", "B"))
        val json = Json.encodeToJsonElement(ListSerializer(Widget.serializer()), list)
        val transport = transportReturning(success = true, result = json)
        val client = createClient(shouldUseIroh = true, transport = transport)

        var fallbackCalled = false
        val result: List<Widget> = irohAdminRouteList(client, "widget.list", "/v1/widgets") {
            fallbackCalled = true
            emptyList()
        }

        assertEquals(list, result)
        assertEquals(1, transport.adminRpcCalls.size)
        assertEquals("widget.list", transport.adminRpcCalls.first().method)
        assertEquals(false, fallbackCalled)
    }

    @Test
    fun `irohAdminRouteUnit routes to client when shouldUseIroh is true`() = runTest {
        val transport = transportReturning(success = true, result = JsonNull)
        val client = createClient(shouldUseIroh = true, transport = transport)

        var fallbackCalled = false
        irohAdminRouteUnit(client, "widget.delete", "/v1/widgets/w1") {
            fallbackCalled = true
        }

        assertEquals(1, transport.adminRpcCalls.size)
        assertEquals("widget.delete", transport.adminRpcCalls.first().method)
        assertEquals(false, fallbackCalled)
    }
}
