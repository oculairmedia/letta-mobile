package com.letta.mobile.data.transport.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * letta-mobile-8lxw0: the bytes passed via [EndpointOptions.secretKey] MUST determine
 * the bound endpoint's NodeId. If this test fails, the iroh-ffi binding is silently
 * ignoring the secretKey parameter — a regression that ships a fresh NodeId on
 * every Endpoint.bind() call even when the caller passed a persistent key.
 *
 * The earlier [IrohAgentIdentityTest.loadOrCreateIsIdempotentForSameAgent] test
 * computed the NodeId locally via SecretKey.fromBytes(bytes).public() and never
 * bound a live endpoint, so it never exercised the production binding path. This
 * test does. It is hermetic (no network, no accept loop) so it can run in the
 * default :sharedLogic:allTests gate.
 *
 * If this test fails on the host JVM, the fix is in the iroh-ffi binding (likely
 * the EndpointOptions.secretKey parameter is being dropped or coerced). If it
 * passes on the host JVM but the device behavior is wrong, the bug is downstream
 * — see the bead for the second-bind-path hypothesis.
 */
class IrohEndpointSecretKeyRoundtripTest {

    @Test
    fun bindingWithSameSecretKeyBytesProducesSameNodeId() = runBlocking {
        val bytes = ByteArray(32) { (it + 1).toByte() }
        val first = Endpoint.bind(
            EndpointOptions(relayMode = RelayMode.disabled(), secretKey = bytes)
        )
        try {
            val firstId = first.addr().id().toBytes()
            val firstExpected = computer.iroh.SecretKey.fromBytes(bytes).public().toBytes()
            assertContentEquals(
                firstExpected, firstId,
                "Endpoint.bind(secretKey=bytes) MUST produce an endpoint whose NodeId " +
                    "is the public key derived from those bytes — otherwise the Android client " +
                    "mints a fresh NodeId per bind and the persistent key file is ignored.",
            )

            // Now bind a second endpoint with the same bytes and assert it lands on the
            // same NodeId. This is the actual production scenario: two Endpoint.bind()
            // calls happen on every redial in the supervisor loop, and a fresh NodeId
            // per redial is the symptom we're guarding against.
            val second = Endpoint.bind(
                EndpointOptions(relayMode = RelayMode.disabled(), secretKey = bytes)
            )
            try {
                val secondId = second.addr().id().toBytes()
                assertContentEquals(
                    firstId, secondId,
                    "same secretKey bytes MUST produce the same NodeId across two Endpoint.bind() calls",
                )
            } finally {
                second.shutdown()
            }
        } finally {
            first.shutdown()
        }
    }
}
