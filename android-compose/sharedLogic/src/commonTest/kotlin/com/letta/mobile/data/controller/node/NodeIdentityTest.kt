package com.letta.mobile.data.controller.node

import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeIdentityTest {
    @Test
    fun testCreateNodeIdentity() {
        val endpoint = AppServerEndpoint(scheme = "ws", address = "127.0.0.1:4500")
        val capabilities = RemoteCapabilities(imageHydration = true)

        val identity = NodeIdentity(
            id = "node-1",
            displayName = "Test Node",
            endpoints = setOf(endpoint),
            capabilities = capabilities,
        )

        assertEquals("node-1", identity.id)
        assertEquals("Test Node", identity.displayName)
        assertEquals(1, identity.endpoints.size)
        assertTrue(identity.endpoints.contains(endpoint))
        assertTrue(identity.capabilities.imageHydration)
    }

    @Test
    fun testNodeIdentityRequiresNonBlankId() {
        val endpoint = AppServerEndpoint(scheme = "ws", address = "127.0.0.1:4500")

        assertFailsWith<IllegalArgumentException> {
            NodeIdentity(
                id = "",
                displayName = "Test Node",
                endpoints = setOf(endpoint),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            NodeIdentity(
                id = "   ",
                displayName = "Test Node",
                endpoints = setOf(endpoint),
            )
        }
    }

    @Test
    fun testNodeIdentityRequiresNonBlankDisplayName() {
        val endpoint = AppServerEndpoint(scheme = "ws", address = "127.0.0.1:4500")

        assertFailsWith<IllegalArgumentException> {
            NodeIdentity(
                id = "node-1",
                displayName = "",
                endpoints = setOf(endpoint),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            NodeIdentity(
                id = "node-1",
                displayName = "   ",
                endpoints = setOf(endpoint),
            )
        }
    }

    @Test
    fun testNodeIdentityRequiresAtLeastOneEndpoint() {
        assertFailsWith<IllegalArgumentException> {
            NodeIdentity(
                id = "node-1",
                displayName = "Test Node",
                endpoints = emptySet(),
            )
        }
    }

    @Test
    fun testLocalNodeIdentityFactory() {
        val identity = NodeIdentity.local()

        assertEquals("local", identity.id)
        assertEquals("Local Node", identity.displayName)
        assertEquals(1, identity.endpoints.size)

        val endpoint = identity.endpoints.first()
        assertEquals("ws", endpoint.scheme)
        assertEquals("127.0.0.1:4500", endpoint.address)
        assertEquals(null, endpoint.bearerToken)
        assertEquals(RemoteCapabilities.FACTORY_DEFAULT, identity.capabilities)
    }

    @Test
    fun testLocalNodeIdentityWithCustomPort() {
        val identity = NodeIdentity.local(port = 8080)

        val endpoint = identity.endpoints.first()
        assertEquals("127.0.0.1:8080", endpoint.address)
    }

    @Test
    fun testLocalNodeIdentityWithCustomCapabilities() {
        val capabilities = RemoteCapabilities(goals = true, slashCommands = true)
        val identity = NodeIdentity.local(capabilities = capabilities)

        assertTrue(identity.capabilities.goals)
        assertTrue(identity.capabilities.slashCommands)
    }

    @Test
    fun testMultipleEndpoints() {
        val wsEndpoint = AppServerEndpoint(scheme = "ws", address = "127.0.0.1:4500")
        val wssEndpoint = AppServerEndpoint(scheme = "wss", address = "example.com:443", bearerToken = "token")

        val identity = NodeIdentity(
            id = "multi-endpoint-node",
            displayName = "Multi-Endpoint Node",
            endpoints = setOf(wsEndpoint, wssEndpoint),
        )

        assertEquals(2, identity.endpoints.size)
        assertTrue(identity.endpoints.contains(wsEndpoint))
        assertTrue(identity.endpoints.contains(wssEndpoint))
    }
}
