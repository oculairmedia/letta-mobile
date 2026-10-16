package com.letta.mobile.web.data

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

    private class FakeTransport : AppServerTransport {
        override val controlFrames: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override val streamFrames: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override val isConnected: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun sendControl(command: AppServerCommand) = Unit
    }
}
