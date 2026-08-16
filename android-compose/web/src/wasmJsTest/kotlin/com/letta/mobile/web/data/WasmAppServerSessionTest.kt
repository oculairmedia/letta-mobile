package com.letta.mobile.web.data

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class WasmAppServerSessionTest {
    @Test
    fun `transport disconnect closes router subscribers`() = runTest {
        val transport = FakeTransport()
        val client = DefaultAppServerClient(transport)
        val router = AppServerRuntimeEventRouter()
        val session = WasmAppServerSession(
            client = client,
            engine = AppServerTurnEngine(client = client, eventRouter = router),
            router = router,
            transport = transport,
            label = "test",
            closeTransport = {},
        )
        val (_, events) = router.subscribe(AgentId("agent-1"), ConversationId("conv-1"))
        val completion = async { events.toList() }

        session.onTransportDisconnected()

        withTimeout(100) { runCatching { completion.await() } }
    }

    private class FakeTransport : AppServerTransport {
        override val controlFrames: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override val streamFrames: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override val isConnected: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun sendControl(command: AppServerCommand) = Unit
    }
}
