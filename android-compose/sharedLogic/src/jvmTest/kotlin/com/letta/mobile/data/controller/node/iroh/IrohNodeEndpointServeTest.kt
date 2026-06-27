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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * g3cva.6 verification: prove that IrohNodeEndpoint can accept Iroh connections
 * and route App Server frames to a controller.
 * 
 * This test:
 * 1. Creates an IrohNodeEndpoint (server) with a stub controller
 * 2. Starts the endpoint listening for connections
 * 3. Gets the dialable ticket/NodeID
 * 4. Creates a client endpoint and dials the server over REAL Iroh
 * 5. Sends a runtime_start frame and verifies the server processes it and responds
 * 
 * The server uses IrohNodeEndpoint + IrohNodeConnection which implement the accept
 * loop and frame routing. This proves the CLI serve-iroh command will work end-to-end.
 */
class IrohNodeEndpointServeTest {
    private val alpn = IrohNodeEndpoint.DEFAULT_ALPN

    @Test
    fun irohNodeEndpointAcceptsDialAndRoundTripsRuntimeStartFrame() = runBlocking {
        val serverEndpoint = IrohNodeEndpoint(scope = this)
        serverEndpoint.create()
        val clientEndpoint = Endpoint.bind(EndpointOptions())
        
        try {
            // Create a stub controller that returns a successful runtime_start response
            val stubController = StubAppServerController()
            
            // Start the server
            serverEndpoint.start(stubController)
            
            // Get dialable info (use the addr directly)
            val serverAddr = serverEndpoint.addr()
            val nodeId = serverEndpoint.nodeIdHex()
            val ticket = serverEndpoint.ticketString()
            
            println("[test] Server NodeID: $nodeId")
            println("[test] Server Ticket: $ticket")
            
            // Client dials server using the addr
            val connection = withTimeout(15_000) {
                clientEndpoint.connect(serverAddr, alpn)
            }
            
            try {
                // The server expects TWO bi-streams: control and stream
                // Open control bi-stream
                val controlBi = connection.openBi()
                
                // Open stream bi-stream (the server waits for this too)
                val streamBi = connection.openBi()
                
                // Send a newline on the stream channel to signal we're ready
                streamBi.send().write("\n".toByteArray())
                streamBi.send().finish()
                
                // Now send runtime_start on control channel
                val runtimeStartJson = """{"type":"runtime_start","request_id":"test-req-1","agent_id":"agent-test","conversation_id":"conv-test","client_info":{"name":"test","title":"Test","version":"1.0"}}"""
                controlBi.send().write((runtimeStartJson + "\n").toByteArray())
                
                // Read response line-by-line
                val responseBytes = mutableListOf<Byte>()
                val chunk = withTimeout(10_000) {
                    controlBi.recv().read(8192u)
                }
                
                for (byte in chunk) {
                    if (byte == '\n'.code.toByte()) break
                    responseBytes.add(byte)
                }
                
                val responseJson = responseBytes.toByteArray().decodeToString()
                println("[test] Response: $responseJson")
                
                // Verify response
                assertTrue(responseJson.contains("\"request_id\":\"test-req-1\""), 
                    "Response should contain matching request_id")
                assertTrue(responseJson.contains("\"type\":\"runtime_start_response\""), 
                    "Response should be a runtime_start_response")
                
                // The stub controller returns success: true
                assertTrue(responseJson.contains("\"success\":true"), 
                    "Stub controller should return success")
                assertTrue(responseJson.contains("\"agent_id\":\"agent-test\""), 
                    "Response should echo agent_id")
                assertTrue(responseJson.contains("\"conversation_id\":\"conv-test\""), 
                    "Response should echo conversation_id")
                
                println("[test] ✓ Iroh transport correctly routed frame to controller and back")
                
            } finally {
                runCatching { connection.close() }
            }
        } finally {
            runCatching { clientEndpoint.shutdown() }
            runCatching { clientEndpoint.close() }
            runCatching { serverEndpoint.shutdown() }
        }
    }

    @Test
    fun irohNodeEndpointTicketFormatIsParseable() = runBlocking {
        val serverEndpoint = IrohNodeEndpoint(scope = this)
        serverEndpoint.create()
        
        try {
            serverEndpoint.start(StubAppServerController())
            
            val ticket = serverEndpoint.ticketString()
            val nodeId = serverEndpoint.nodeIdHex()
            
            // Verify NodeID is 64 hex chars (32 bytes)
            assertEquals(64, nodeId.length, "NodeID should be 64 hex characters")
            assertTrue(nodeId.matches(Regex("[0-9a-f]+")), "NodeID should be hex")
            
            // Verify ticket is non-empty
            assertTrue(ticket.isNotEmpty(), "Ticket should be non-empty")
            
            println("[test] NodeID: $nodeId")
            println("[test] Ticket: $ticket")
            println("[test] ✓ Ticket format is valid")
            
        } finally {
            runCatching { serverEndpoint.shutdown() }
        }
    }

    /**
     * Stub controller that successfully starts runtimes but doesn't implement full behavior.
     * This is sufficient to test the Iroh transport layer.
     */
    private class StubAppServerController : AppServerController {
        override val state = MutableStateFlow<AppServerControllerState>(
            AppServerControllerState.Connected
        )

        override suspend fun startRuntime(
            agentId: AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean
        ): CanonicalRuntime {
            // Return a minimal canonical runtime
            return CanonicalRuntime(
                scope = AppServerRuntimeScope(
                    agentId = agentId.value,
                    conversationId = conversationId.value,
                    actingUserId = null,
                ),
                agent = null,
                conversation = null,
                created = null,
            )
        }

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> {
            throw UnsupportedOperationException("Stub does not run turns")
        }

        override suspend fun sync(
            runtime: AppServerRuntimeScope,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean
        ): AppServerInboundFrame.SyncResponse {
            throw UnsupportedOperationException("Stub does not sync")
        }

        override suspend fun abort(
            runtime: AppServerRuntimeScope,
            runId: String?
        ): AppServerInboundFrame.AbortMessageResponse {
            throw UnsupportedOperationException("Stub does not abort")
        }
    }
}
