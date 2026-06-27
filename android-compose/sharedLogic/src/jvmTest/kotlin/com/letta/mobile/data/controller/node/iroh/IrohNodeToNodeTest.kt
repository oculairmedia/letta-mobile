package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.controller.node.DefaultNodeClient
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IrohNodeToNodeTest {

    private val alpn = "/letta/appserver/0".toByteArray()

    @Test
    fun nodeBCanDialNodeAByIrohNodeId() = runBlocking {
        val scopeA = CoroutineScope(Dispatchers.Default)
        val scopeB = CoroutineScope(Dispatchers.Default)

        try {
            val mockControllerA = MockAppServerController()
            val serverA = IrohNodeServer.create(
                id = "node-a",
                displayName = "Node A",
                controller = mockControllerA,
                scope = scopeA,
                alpn = alpn,
            )

            serverA.acceptConnections()
            val identityA = serverA.advertise()
            assertNotNull(identityA)
            assertTrue(identityA.endpoints.isNotEmpty())

            val irohEndpoint = identityA.endpoints.firstOrNull { it.scheme == "iroh" }
            assertNotNull(irohEndpoint)
            assertTrue(irohEndpoint.address.isNotEmpty())

            val clientEndpointB = Endpoint.bind(EndpointOptions())
            val adapterB = IrohAppServerTransportAdapter(
                endpoint = clientEndpointB,
                alpn = alpn,
            )
            com.letta.mobile.data.transport.appserver.AppServerTransportRegistry.register(adapterB)

            val clientB = DefaultNodeClient(scopeB)

            withTimeout(15_000L) {
                val handle = clientB.connectTo(irohEndpoint)
                assertNotNull(handle)
                assertNotNull(handle.controller)
            }

            clientEndpointB.shutdown()
            clientEndpointB.close()
            serverA.shutdown()
        } finally {
            scopeA.cancel()
            scopeB.cancel()
        }
    }

    private class MockAppServerController : AppServerController {
        override val state: Flow<AppServerControllerState> =
            flowOf(AppServerControllerState.Connected)

        override suspend fun startRuntime(
            agentId: AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): CanonicalRuntime {
            throw NotImplementedError("Mock controller")
        }

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> {
            throw NotImplementedError("Mock controller")
        }

        override suspend fun sync(
            runtime: AppServerRuntimeScope,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): AppServerInboundFrame.SyncResponse {
            throw NotImplementedError("Mock controller")
        }

        override suspend fun abort(
            runtime: AppServerRuntimeScope,
            runId: String?,
        ): AppServerInboundFrame.AbortMessageResponse {
            throw NotImplementedError("Mock controller")
        }
    }
}
