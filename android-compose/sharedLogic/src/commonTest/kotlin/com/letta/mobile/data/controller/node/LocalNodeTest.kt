package com.letta.mobile.data.controller.node

import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalNodeTest {
    @Test
    fun testLocalNodeHasBothServerAndClient() = runTest {
        val identity = NodeIdentity.local(id = "local-node")
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)
        val client = FakeNodeClient(controller)

        val localNode = LocalNode(server = server, client = client)

        assertNotNull(localNode.server)
        assertNotNull(localNode.client)
    }

    @Test
    fun testLocalNodeAdvertise() = runTest {
        val identity = NodeIdentity.local(id = "local-node", displayName = "Local Node")
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)
        val client = FakeNodeClient(controller)

        val localNode = LocalNode(server = server, client = client)

        val advertised = localNode.advertise()

        assertEquals(identity, advertised)
    }

    @Test
    fun testLocalNodeHostedRuntimes() = runTest {
        val identity = NodeIdentity.local()
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)
        val client = FakeNodeClient(controller)

        val runtime = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "agent-1",
                conversationId = "conv-1",
                actingUserId = "user-1",
            ),
        )

        server.addHostedRuntime(runtime)

        val localNode = LocalNode(server = server, client = client)
        val runtimes = localNode.hostedRuntimes()

        assertEquals(1, runtimes.size)
        assertEquals(runtime, runtimes.first())
    }

    @Test
    fun testLocalNodeConnectToIdentity() = runTest {
        val identity = NodeIdentity.local(id = "local-node")
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)
        val client = FakeNodeClient(controller)

        val localNode = LocalNode(server = server, client = client)

        val remoteIdentity = NodeIdentity.local(id = "remote-node", port = 4501)
        val handle = localNode.connectTo(remoteIdentity)

        assertNotNull(handle)
        assertEquals(controller, handle.controller)
    }

    @Test
    fun testLocalNodeConnectToEndpoint() = runTest {
        val identity = NodeIdentity.local(id = "local-node")
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)
        val client = FakeNodeClient(controller)

        val localNode = LocalNode(server = server, client = client)

        val endpoint = AppServerEndpoint(scheme = "ws", address = "127.0.0.1:4501")
        val handle = localNode.connectTo(endpoint)

        assertNotNull(handle)
        assertEquals(controller, handle.controller)
    }

    @Test
    fun testLocalNodeAcceptConnections() = runTest {
        val identity = NodeIdentity.local()
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)
        val client = FakeNodeClient(controller)

        val localNode = LocalNode(server = server, client = client)

        // Should not throw
        localNode.acceptConnections(4500)
    }

    @Test
    fun testAgentMobilityScenario() = runTest {
        // Scenario: Runtime hosted on node A, driven by node B's client

        // Node A: hosts a runtime
        val identityA = NodeIdentity.local(id = "node-a", port = 4500)
        val controllerA = FakeAppServerController()
        val serverA = DefaultNodeServer(identityA, controllerA)
        val clientA = FakeNodeClient(controllerA)

        val runtimeOnA = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "agent-mobile",
                conversationId = "conv-mobile",
                actingUserId = "user-1",
            ),
        )
        serverA.addHostedRuntime(runtimeOnA)

        val nodeA = LocalNode(server = serverA, client = clientA)

        // Node B: connects to node A and drives the runtime
        val identityB = NodeIdentity.local(id = "node-b", port = 4501)
        val controllerB = FakeAppServerController()
        val serverB = DefaultNodeServer(identityB, controllerB)
        val clientB = FakeNodeClient(controllerA) // Client connects to node A's controller

        val nodeB = LocalNode(server = serverB, client = clientB)

        // Node B connects to node A
        val nodeAIdentity = nodeA.advertise()
        val handle = nodeB.connectTo(nodeAIdentity)

        // Node B starts a runtime on node A (via the handle)
        val startedRuntime = handle.startRuntime(
            agentId = AgentId("agent-mobile"),
            conversationId = ConversationId("conv-mobile"),
        )

        assertEquals("agent-mobile", startedRuntime.scope.agentId)
        assertEquals("conv-mobile", startedRuntime.scope.conversationId)

        // Verify node A still hosts the runtime
        val hostedRuntimes = nodeA.hostedRuntimes()
        assertTrue(hostedRuntimes.contains(runtimeOnA))
    }

    @Test
    fun testLocalNodeCreateFactoryMethod() = runTest {
        val identity = NodeIdentity.local(id = "factory-node")
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)
        val client = FakeNodeClient(controller)

        val localNode = LocalNode.create(server = server, client = client)

        assertEquals(server, localNode.server)
        assertEquals(client, localNode.client)
    }
}
