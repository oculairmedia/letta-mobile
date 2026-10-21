package com.letta.mobile.web.data

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId as DataConversationId
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.runtime.IrohApprovalControls
import com.letta.mobile.data.runtime.PermissionModeRpc
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChange
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeout

class WasmAppServerSessionTest {
    @Test
    fun `transport disconnect unblocks router subscribers`() = runTest {
        val transport = FakeTransport()
        val client = DefaultAppServerClient(transport)
        val router = AppServerRuntimeEventRouter()
        val session = WasmAppServerSession(
            link = WasmAppServerLink(client, transport.isConnected, failureReason = { null }, label = "test", close = {}),
            services = WasmAppServerServices(
                engine = AppServerTurnEngine(client = client, eventRouter = router),
                router = router,
                appServerAdmin = AppServerLocalAdminGateway(client) { operation -> "test-$operation" },
            ),
            scope = this,
        )
        val (_, events) = router.subscribe(AgentId("agent-1"), ConversationId("conv-1"))
        val started = CompletableDeferred<Unit>()
        val completion = async {
            try {
                events.onStart { started.complete(Unit) }.toList()
                null
            } catch (cancelled: CancellationException) {
                cancelled
            }
        }
        started.await()

        session.onTransportDisconnected()

        assertIs<CancellationException>(withTimeout(100) { completion.await() })
        assertTrue(completion.isCompleted)
    }

    @Test
    fun `http endpoints resolve to the one socket app server path`() {
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("http://localhost:4500/"))
        assertEquals("wss://api.example.com/ws", resolveWebSocketUrl("https://api.example.com"))
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("ws://localhost:4500/ws"))
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("ws://localhost:4500"))
    }

    @Test
    fun `iroh endpoint never falls back to websocket`() {
        assertFailsWith<IllegalStateException> { resolveWebSocketUrl("iroh://0123456789abcdef") }
    }

    @Test
    fun `iroh auth asks the server to split replies larger than one frame`() {
        assertEquals(
            listOf("frame_part"),
            authCapabilitiesFor("iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f"),
        )
        assertNull(authCapabilitiesFor("http://localhost:4500"))
        assertNull(authCapabilitiesFor("wss://api.example.com/ws"))
    }

    @Test
    fun `request ids do not collide after a page reload`() {
        assertNotEquals(webRequestId("page-a", "message", 9), webRequestId("page-b", "message", 9))
        assertEquals("web-page-b-message-9", webRequestId("page-b", "message", 9))
    }

    @Test
    fun `over iroh the chat lists and switches models through the shared gateway's admin rpc`() = runTest {
        val host = RecordingHost()
        val source = webModelSource(IrohAdminRpcChatGateway(host), nativeAdmin(), null, ModelCatalogRepository(unexpectedRpc))

        source.backend.listModels()
        source.backend.switchModel(
            ConversationModelChange(DataConversationId("conv-1"), ModelHandle("openai/gpt-sol"), ReasoningEffortChoice.Named("high")),
        )

        assertTrue(source.backend.supportsReasoningEffort, "the effort chip shows over an Iroh host")
        assertTrue("model.list" in host.methods && "model.update" in host.methods)
        assertEquals("openai/gpt-sol", source.conversation(DataConversationId("conv-1")).model)
    }

    @Test
    fun `a websocket chat keeps the app server's own model commands`() = runTest {
        val source = webModelSource(null, nativeAdmin(), null, ModelCatalogRepository(unexpectedRpc))

        assertFalse(source.backend.supportsReasoningEffort)
    }

    @Test
    fun `over iroh the mode chip is the host's, and a closed session lets the gateway go`() = runTest {
        val transport = FakeTransport()
        val client = DefaultAppServerClient(transport)
        val router = AppServerRuntimeEventRouter()
        val session = WasmAppServerSession(
            link = WasmAppServerLink(client, transport.isConnected, failureReason = { null }, label = "test", close = {}),
            services = WasmAppServerServices(
                engine = AppServerTurnEngine(client = client, eventRouter = router),
                router = router,
                appServerAdmin = AppServerLocalAdminGateway(client) { operation -> "test-$operation" },
            ),
            scope = this,
        )
        assertNull(session.permissionModes, "no mode chip until the host's gateway is bound")

        val host = RecordingHost(setOf(PermissionModeRpc.CAPABILITY))
        val gateway = IrohAdminRpcChatGateway(
            host,
            approvalControls = IrohApprovalControls(backgroundScope, host, PermissionModeSettings(InMemorySecureSettingsStore()).modes),
        )
        session.bindIrohChat(gateway)

        assertSame(gateway, session.permissionModes)
        assertNotNull(gateway.permissionModes, "the host advertises the mode relay")
        session.close()
        assertNull(session.irohChat)
    }

    private fun nativeAdmin() = AppServerLocalAdminGateway(DefaultAppServerClient(FakeTransport())) { operation -> "test-$operation" }

    private val unexpectedRpc = AdminRpcInvoker { method, _ -> error("unexpected $method") }

    /** An Iroh host's admin_rpc: it answers the model and conversation methods and records each call. */
    private class RecordingHost(override val hostCapabilities: Set<String> = emptySet()) : NoOpChannelTransport() {
        val methods = mutableListOf<String>()

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            methods += method
            val result = when (method) {
                "model.list" -> """[{"id":"openai/gpt-sol","handle":"openai/gpt-sol","label":"Sol"}]"""
                "conversation.get" -> """{"id":"conv-1","agent_id":"agent-1","model":"openai/gpt-sol"}"""
                else -> "{}"
            }
            return AppServerInboundFrame.AdminRpcResponse(requestId = method, success = true, result = Json.parseToJsonElement(result))
        }
    }

    private class FakeTransport : AppServerTransport {
        override val controlFrames: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override val streamFrames: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override val isConnected: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun sendControl(command: AppServerCommand) = Unit
    }
}
