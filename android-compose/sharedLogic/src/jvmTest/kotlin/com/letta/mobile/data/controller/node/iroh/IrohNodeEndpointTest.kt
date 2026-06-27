package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
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

class IrohNodeEndpointTest {

    private val alpn = "/letta/appserver/0".toByteArray()

    @Test
    fun nodeIdIsExposedAsAppServerEndpoint() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val endpoint = IrohNodeEndpoint(alpn, scope)
            endpoint.create()

            // Verify the node ID is available as a hex string
            val nodeIdHex = endpoint.nodeIdHex()
            assertNotNull(nodeIdHex)
            assertTrue(nodeIdHex.isNotEmpty())
            assertTrue(nodeIdHex.matches(Regex("^[0-9a-f]{64}$")))

            // Verify the endpoint is exposed as an AppServerEndpoint with an iroh ticket
            val appServerEndpoint = endpoint.asAppServerEndpoint()
            assertEquals("iroh", appServerEndpoint.scheme)
            assertNotNull(appServerEndpoint.address)
            assertTrue(appServerEndpoint.address.isNotEmpty())
            
            // The address should be a ticket (not a bare hex ID), which can be parsed
            val ticket = computer.iroh.EndpointTicket.Companion.fromString(appServerEndpoint.address)
            val endpointAddr = ticket.endpointAddr()
            
            // Verify the ticket contains the correct node ID
            val ticketNodeIdHex = endpointAddr.id().toBytes().joinToString("") { "%02x".format(it) }
            assertEquals(nodeIdHex, ticketNodeIdHex)

            endpoint.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clientCanConnectToNodeEndpoint() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val mockController = MockAppServerController()
            val serverEndpoint = IrohNodeEndpoint(alpn, scope)
            serverEndpoint.create()
            serverEndpoint.start(mockController)

            // For loopback connections, use the full EndpointAddr which includes
            // direct addresses. This is what the working IrohLoopbackRoundTripTest does.
            val serverAddr = serverEndpoint.addr()
            val clientEndpoint = Endpoint.bind(EndpointOptions())
            try {
                withTimeout(10_000L) {
                    val connection = clientEndpoint.connect(serverAddr, alpn)
                    assertNotNull(connection)
                    val bi = connection.openBi()
                    assertNotNull(bi)
                    val send = bi.send()
                    send.writeAll("test".toByteArray())
                    send.finish()
                    connection.close()
                }
            } finally {
                clientEndpoint.shutdown()
                clientEndpoint.close()
            }

            serverEndpoint.shutdown()
        } finally {
            scope.cancel()
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
