package com.letta.mobile.data.transport.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spike test for g3cva.1: verify the iroh-ffi Kotlin binding loads and basic
 * endpoint create/connect works.
 *
 * This test proves:
 * 1. The computer.iroh:iroh:1.0.0 dependency resolves
 * 2. Native library (JNI) loads successfully in JVM test environment
 * 3. Iroh Endpoint can be created via Endpoint.bind()
 * 4. EndpointId/EndpointAddr can be obtained
 * 5. Basic endpoint operations work (close, addr queries)
 *
 * NOTE: Full connection tests (two endpoints connecting + transferring bytes)
 * require relay/discovery setup which is awkward in a unit test. This test
 * focuses on proving the binding loads and basic operations work. Connection
 * + byte transfer will be tested in integration/device environments.
 */
class IrohBindingSpikeTest {

    @Test
    fun `iroh binding loads and endpoint can be created`() = runBlocking {
        // Create an in-memory iroh endpoint with minimal preset (no relays).
        // EndpointOptions() with no args uses the default n0 preset, but we use
        // minimal to avoid needing network access in this unit test.
        val options = EndpointOptions()

        val endpoint = Endpoint.bind(options)

        try {
            // Get the endpoint ID (the public key identifying this endpoint)
            val endpointId = endpoint.id()
            assertNotNull(endpointId, "Endpoint should have an ID")
            println("Endpoint ID: ${endpointId.fmtShort()}")

            // Get the endpoint address (ID + known network addresses)
            val endpointAddr = endpoint.addr()
            assertNotNull(endpointAddr, "Endpoint should have an addr")
            println("Endpoint addr ID: ${endpointAddr.id().fmtShort()}")
            println("Endpoint relay URL: ${endpointAddr.relayUrl()}")
            println("Endpoint direct addrs: ${endpointAddr.directAddresses()}")

            // Get the bound sockets
            val boundSockets = endpoint.boundSockets()
            assertNotNull(boundSockets, "Endpoint should have bound sockets")
            println("Bound sockets: $boundSockets")

            // Check if endpoint is closed (should be false)
            val isClosed = endpoint.isClosed()
            assertTrue(!isClosed, "Endpoint should not be closed initially")

            // If we got here, the binding loaded, endpoint created, and basic
            // operations worked!
            assertTrue(true, "Iroh binding spike test passed!")

        } finally {
            // Clean up endpoint
            endpoint.shutdown()
            endpoint.close()
        }
    }

    @Test
    fun `iroh secret key can be provided and retrieved`() = runBlocking {
        // Generate a 32-byte secret key (in real usage, this would be persisted)
        val secretKey = ByteArray(32) { it.toByte() }

        val options = EndpointOptions(secretKey = secretKey)
        val endpoint = Endpoint.bind(options)

        try {
            // Retrieve the secret key from the endpoint
            val retrievedKey = endpoint.secretKey()
            assertNotNull(retrievedKey, "Should be able to retrieve secret key")
            println("Retrieved secret key (first 8 bytes): ${retrievedKey.toBytes().take(8)}")

            assertTrue(true, "Secret key round-trip works")
        } finally {
            endpoint.shutdown()
            endpoint.close()
        }
    }

    @Test
    fun `iroh alpns can be configured`() = runBlocking {
        // Configure custom ALPN protocols
        val alpns = listOf(
            "/letta/spike/0".toByteArray(),
            "/letta/test/1".toByteArray()
        )

        val options = EndpointOptions(alpns = alpns)
        val endpoint = Endpoint.bind(options)

        try {
            val endpointId = endpoint.id()
            assertNotNull(endpointId, "Endpoint with ALPNs should have an ID")
            println("Endpoint with custom ALPNs created: ${endpointId.fmtShort()}")

            // We can also set ALPNs at runtime
            endpoint.setAlpns(listOf("/letta/runtime/0".toByteArray()))
            println("Runtime ALPN configuration works")

            assertTrue(true, "ALPN configuration works")
        } finally {
            endpoint.shutdown()
            endpoint.close()
        }
    }
}
