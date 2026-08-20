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

    /** Transport pre-wired to return a fixed admin_rpc response for any call. */
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
    fun `call decodes result on success`() = runTest {
        val json = Json.encodeToJsonElement(Widget.serializer(), Widget("w1", "hello"))
        val transport = transportReturning(success = true, result = json)
        val result: Widget = client(transport).call("widget.get", "/v1/widgets/w1")
        assertEquals(Widget("w1", "hello"), result)
        assertEquals(1, transport.adminRpcCalls.size)
        assertEquals("widget.get", transport.adminRpcCalls.first().method)
    }

    @Test
    fun `call throws on failure with server error message`() = runTest {
        val c = client(transportReturning(success = false, error = "boom"))
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { c.call<Widget>("widget.get", "/v1/widgets/w1") }
        }
        assertEquals("boom", ex.message)
    }

    @Test
    fun `call throws when success but no result`() = runTest {
        val c = client(transportReturning(success = true))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { c.call<Widget>("widget.get", "/v1/widgets/w1") }
        }
    }

    @Test
    fun `callList decodes a list`() = runTest {
        val list = listOf(Widget("a", "A"), Widget("b", "B"))
        val json = Json.encodeToJsonElement(ListSerializer(Widget.serializer()), list)
        val result: List<Widget> = client(transportReturning(success = true, result = json)).callList("widget.list", "/v1/widgets")
        assertEquals(list, result)
    }

    @Test
    fun `callList returns empty when result is null`() = runTest {
        val result: List<Widget> = client(transportReturning(success = true)).callList("widget.list", "/v1/widgets")
        assertEquals(emptyList<Widget>(), result)
    }

    @Test
    fun `callUnit succeeds and ignores result`() = runTest {
        val transport = transportReturning(success = true, result = JsonNull)
        client(transport).callUnit("widget.delete", "/v1/widgets/w1")
        assertEquals("widget.delete", transport.adminRpcCalls.first().method)
    }

    @Test
    fun `callUnit throws on failure`() = runTest {
        val c = client(transportReturning(success = false, error = "nope"))
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { c.callUnit("widget.delete", "/v1/widgets/w1") }
        }
        assertEquals("nope", ex.message)
    }
}
