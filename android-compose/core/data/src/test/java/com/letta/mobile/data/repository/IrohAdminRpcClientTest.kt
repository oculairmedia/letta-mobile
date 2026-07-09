package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for the generic [IrohAdminRpcClient] (audit-Q4). Uses the shared
 * [FakeChannelTransport] / [FakeSettingsRepository] test utils rather than a
 * hand-rolled fake.
 */
class IrohAdminRpcClientTest {

    @Serializable
    private data class Widget(val id: String, val name: String)

    private fun client(transport: FakeChannelTransport): IrohAdminRpcClient {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "t",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test",
                accessToken = "t",
            ),
        )
        return IrohAdminRpcClient(transport, settings)
    }

    @Test
    fun `call decodes result on success`() = runTest {
        val transport = FakeChannelTransport()
        val json = Json.encodeToJsonElement(Widget.serializer(), Widget("w1", "hello"))
        transport.adminRpcHandler = { _, _, _ ->
            AppServerInboundFrame.AdminRpcResponse("req", success = true, result = json, error = null)
        }
        val result: Widget = client(transport).call("widget.get", "/v1/widgets/w1")
        assertEquals(Widget("w1", "hello"), result)
        assertEquals(1, transport.adminRpcCalls.size)
        assertEquals("widget.get", transport.adminRpcCalls.first().method)
    }

    @Test
    fun `call throws on failure with server error message`() = runTest {
        val transport = FakeChannelTransport()
        transport.adminRpcHandler = { _, _, _ ->
            AppServerInboundFrame.AdminRpcResponse("req", success = false, result = null, error = "boom")
        }
        val c = client(transport)
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { c.call<Widget>("widget.get", "/v1/widgets/w1") }
        }
        assertEquals("boom", ex.message)
    }

    @Test
    fun `call throws when success but no result`() = runTest {
        val transport = FakeChannelTransport()
        transport.adminRpcHandler = { _, _, _ ->
            AppServerInboundFrame.AdminRpcResponse("req", success = true, result = null, error = null)
        }
        val c = client(transport)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { c.call<Widget>("widget.get", "/v1/widgets/w1") }
        }
    }

    @Test
    fun `callList decodes a list`() = runTest {
        val transport = FakeChannelTransport()
        val list = listOf(Widget("a", "A"), Widget("b", "B"))
        val json = Json.encodeToJsonElement(ListSerializer(Widget.serializer()), list)
        transport.adminRpcHandler = { _, _, _ ->
            AppServerInboundFrame.AdminRpcResponse("req", success = true, result = json, error = null)
        }
        val result: List<Widget> = client(transport).callList("widget.list", "/v1/widgets")
        assertEquals(list, result)
    }

    @Test
    fun `callList returns empty when result is null`() = runTest {
        val transport = FakeChannelTransport()
        transport.adminRpcHandler = { _, _, _ ->
            AppServerInboundFrame.AdminRpcResponse("req", success = true, result = null, error = null)
        }
        val result: List<Widget> = client(transport).callList("widget.list", "/v1/widgets")
        assertEquals(emptyList<Widget>(), result)
    }

    @Test
    fun `callUnit succeeds and ignores result`() = runTest {
        val transport = FakeChannelTransport()
        transport.adminRpcHandler = { _, _, _ ->
            AppServerInboundFrame.AdminRpcResponse("req", success = true, result = JsonNull, error = null)
        }
        client(transport).callUnit("widget.delete", "/v1/widgets/w1")
        assertEquals("widget.delete", transport.adminRpcCalls.first().method)
    }

    @Test
    fun `callUnit throws on failure`() = runTest {
        val transport = FakeChannelTransport()
        transport.adminRpcHandler = { _, _, _ ->
            AppServerInboundFrame.AdminRpcResponse("req", success = false, result = null, error = "nope")
        }
        val c = client(transport)
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { c.callUnit("widget.delete", "/v1/widgets/w1") }
        }
        assertEquals("nope", ex.message)
    }
}
