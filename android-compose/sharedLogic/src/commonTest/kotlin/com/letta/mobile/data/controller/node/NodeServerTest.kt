package com.letta.mobile.data.controller.node

import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeServerTest {
    @Test
    fun testDefaultNodeServerAdvertisesIdentity() = runTest {
        val identity = NodeIdentity.local(id = "server-node", displayName = "Server Node")
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)

        val advertised = server.advertise()

        assertEquals(identity, advertised)
    }

    @Test
    fun testDefaultNodeServerHostedRuntimesInitiallyEmpty() = runTest {
        val identity = NodeIdentity.local()
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)

        val runtimes = server.hostedRuntimes()

        assertTrue(runtimes.isEmpty())
    }

    @Test
    fun testDefaultNodeServerCanAddHostedRuntimes() = runTest {
        val identity = NodeIdentity.local()
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)

        val runtime = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "agent-1",
                conversationId = "conv-1",
                actingUserId = "user-1",
            ),
        )

        server.addHostedRuntime(runtime)

        val runtimes = server.hostedRuntimes()
        assertEquals(1, runtimes.size)
        assertEquals(runtime, runtimes.first())
    }

    @Test
    fun testDefaultNodeServerAcceptConnectionsIsNoOp() = runTest {
        val identity = NodeIdentity.local()
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)

        // Should not throw
        server.acceptConnections(4500)
    }

    @Test
    fun testMultipleHostedRuntimes() = runTest {
        val identity = NodeIdentity.local()
        val controller = FakeAppServerController()
        val server = DefaultNodeServer(identity, controller)

        val runtime1 = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "agent-1",
                conversationId = "conv-1",
                actingUserId = "user-1",
            ),
        )

        val runtime2 = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "agent-2",
                conversationId = "conv-2",
                actingUserId = "user-1",
            ),
        )

        server.addHostedRuntime(runtime1)
        server.addHostedRuntime(runtime2)

        val runtimes = server.hostedRuntimes()
        assertEquals(2, runtimes.size)
        assertTrue(runtimes.contains(runtime1))
        assertTrue(runtimes.contains(runtime2))
    }
}
