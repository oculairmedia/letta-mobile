package com.letta.mobile.data.controller.node

import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for agent mobility across nodes.
 *
 * These tests demonstrate the core use case: a runtime hosted on node A
 * can be discovered and driven by node B's client, proving that agents
 * can move between nodes in a distributed system.
 */
class AgentMobilityIntegrationTest {
    @Test
    fun testNodeAdvertisesIdentityAndCapabilities() = runTest {
        // Node advertises its identity with endpoints and capabilities
        val capabilities = RemoteCapabilities(
            imageHydration = true,
            subagentChips = true,
        )

        val identity = NodeIdentity(
            id = "advertiser-node",
            displayName = "Advertiser Node",
            endpoints = setOf(
                AppServerEndpoint(scheme = "ws", address = "192.168.1.100:4500"),
                AppServerEndpoint(scheme = "wss", address = "example.com:443", bearerToken = "secret"),
            ),
            capabilities = capabilities,
        )

        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)
        val client = FakeNodeClient(controller)
        val node = LocalNode(server = server, client = client)

        val advertised = node.advertise()

        assertEquals("advertiser-node", advertised.id)
        assertEquals("Advertiser Node", advertised.displayName)
        assertEquals(2, advertised.endpoints.size)
        assertTrue(advertised.capabilities.imageHydration)
        assertTrue(advertised.capabilities.subagentChips)
    }

    @Test
    fun testClientConnectsToRemoteNodeAndDrivesRuntime() = runTest {
        // Scenario: Client discovers a remote node, connects to it, and drives a runtime

        // Remote node (server side)
        val remoteIdentity = NodeIdentity.local(id = "remote-node", port = 4500)
        val remoteController = FakeAppServerController()
        val remoteServer = DefaultNodeServer(remoteIdentity, remoteController)

        // Add a hosted runtime to the remote node
        val hostedRuntime = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "remote-agent",
                conversationId = "remote-conv",
                actingUserId = "user-1",
            ),
        )
        remoteServer.addHostedRuntime(hostedRuntime)

        // Client node
        val clientNode = LocalNode(
            server = DefaultNodeServer(NodeIdentity.local(id = "client-node", port = 4501), FakeAppServerController()),
            client = FakeNodeClient(remoteController), // Client connects to remote controller
        )

        // Client discovers the remote node's identity
        val discoveredIdentity = remoteServer.advertise()

        // Client connects to the remote node
        val handle = clientNode.connectTo(discoveredIdentity)

        // Client starts the runtime on the remote node
        val startedRuntime = handle.startRuntime(
            agentId = AgentId("remote-agent"),
            conversationId = ConversationId("remote-conv"),
        )

        assertEquals("remote-agent", startedRuntime.scope.agentId)
        assertEquals("remote-conv", startedRuntime.scope.conversationId)
    }

    @Test
    fun testLocalNodeIsSimultaneouslyServerAndClient() = runTest {
        // A single node can both host runtimes (server) and dial other nodes (client)

        val identity = NodeIdentity.local(id = "dual-role-node", port = 4500)
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)
        val client = FakeNodeClient(controller)

        val node = LocalNode(server = server, client = client)

        // As a server: host a runtime
        val localRuntime = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "local-agent",
                conversationId = "local-conv",
                actingUserId = "user-1",
            ),
        )
        server.addHostedRuntime(localRuntime)

        val hostedRuntimes = node.hostedRuntimes()
        assertEquals(1, hostedRuntimes.size)
        assertTrue(hostedRuntimes.contains(localRuntime))

        // As a client: connect to a remote node
        val remoteIdentity = NodeIdentity.local(id = "remote-node", port = 4501)
        val handle = node.connectTo(remoteIdentity)

        assertNotNull(handle)
    }

    @Test
    fun testRuntimeMovesBetweenNodes() = runTest {
        // Scenario: A runtime starts on node A, then node B connects and drives it

        // Node A: hosts the runtime initially
        val identityA = NodeIdentity.local(id = "node-a", port = 4500)
        val controllerA = FakeAppServerController()
        val serverA = DefaultNodeServer(identityA, controllerA)

        val runtimeOnA = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "mobile-agent",
                conversationId = "mobile-conv",
                actingUserId = "user-1",
            ),
        )
        serverA.addHostedRuntime(runtimeOnA)

        val nodeA = LocalNode(
            server = serverA,
            client = FakeNodeClient(controllerA),
        )

        // Node B: discovers node A and connects to it
        val identityB = NodeIdentity.local(id = "node-b", port = 4501)
        val controllerB = FakeAppServerController()
        val nodeB = LocalNode(
            server = DefaultNodeServer(identityB, controllerB),
            client = FakeNodeClient(controllerA), // Client connects to node A's controller
        )

        // Node B discovers node A
        val nodeAIdentity = nodeA.advertise()
        assertEquals("node-a", nodeAIdentity.id)

        // Node B connects to node A
        val handle = nodeB.connectTo(nodeAIdentity)

        // Node B drives the runtime on node A
        val runtime = handle.startRuntime(
            agentId = AgentId("mobile-agent"),
            conversationId = ConversationId("mobile-conv"),
        )

        assertEquals("mobile-agent", runtime.scope.agentId)
        assertEquals("mobile-conv", runtime.scope.conversationId)

        // Verify node A still hosts the runtime
        val hostedRuntimes = nodeA.hostedRuntimes()
        assertTrue(hostedRuntimes.contains(runtimeOnA))
    }

    @Test
    fun testMultipleNodesCanConnectToSameRemoteNode() = runTest {
        // Scenario: Multiple client nodes connect to the same server node

        // Server node
        val serverIdentity = NodeIdentity.local(id = "server-node", port = 4500)
        val serverController = FakeAppServerController()
        val serverNode = LocalNode(
            server = DefaultNodeServer(serverIdentity, serverController),
            client = FakeNodeClient(serverController),
        )

        // Client 1
        val client1 = LocalNode(
            server = DefaultNodeServer(NodeIdentity.local(id = "client-1", port = 4501), FakeAppServerController()),
            client = FakeNodeClient(serverController),
        )

        // Client 2
        val client2 = LocalNode(
            server = DefaultNodeServer(NodeIdentity.local(id = "client-2", port = 4502), FakeAppServerController()),
            client = FakeNodeClient(serverController),
        )

        // Both clients connect to the server
        val handle1 = client1.connectTo(serverNode.advertise())
        val handle2 = client2.connectTo(serverNode.advertise())

        assertNotNull(handle1)
        assertNotNull(handle2)

        // Both clients can drive runtimes on the server
        val runtime1 = handle1.startRuntime(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        val runtime2 = handle2.startRuntime(
            agentId = AgentId("agent-2"),
            conversationId = ConversationId("conv-2"),
        )

        assertEquals("agent-1", runtime1.scope.agentId)
        assertEquals("agent-2", runtime2.scope.agentId)
    }

    @Test
    fun testNodeCanSyncAndAbortRemoteRuntime() = runTest {
        // Scenario: Client connects to a remote node and performs sync/abort operations

        val remoteController = FakeAppServerController()
        val remoteIdentity = NodeIdentity.local(id = "remote-node", port = 4500)
        val remoteNode = LocalNode(
            server = DefaultNodeServer(remoteIdentity, remoteController),
            client = FakeNodeClient(remoteController),
        )

        val clientNode = LocalNode(
            server = DefaultNodeServer(NodeIdentity.local(id = "client-node", port = 4501), FakeAppServerController()),
            client = FakeNodeClient(remoteController),
        )

        // Client connects to remote node
        val handle = clientNode.connectTo(remoteNode.advertise())

        // Client starts a runtime on the remote node
        val runtime = handle.startRuntime(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        // Client syncs the runtime
        val syncResponse = handle.sync(runtime.scope)
        assertTrue(syncResponse.success)
        assertEquals(runtime.scope, syncResponse.runtime)

        // Client aborts the runtime
        val abortResponse = handle.abort(runtime.scope)
        assertTrue(abortResponse.success)
        assertTrue(abortResponse.aborted)
        assertEquals(runtime.scope, abortResponse.runtime)
    }
}
