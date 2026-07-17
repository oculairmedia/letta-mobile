package com.letta.mobile.data.transport.iroh

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * letta-mobile-bn008.2: pure-unit coverage for the send path that needs no live
 * QUIC endpoint (the real-loopback probe is IrohAgentMessageSendE2ETest, opt-in).
 * Covers the envelope wire round-trip and the unaddressable short-circuit — a send
 * to an unregistered agent must NEVER dial and must return a typed result.
 */
class IrohAgentMessageSenderTest {

    private val tmp = File.createTempFile("bn008-2-unit", ".kv").apply { deleteOnExit() }
    @AfterTest fun cleanup() { tmp.delete() }

    @Test
    fun envelopeRoundTripsThroughWire() {
        val msg = IrohAgentMessage("a", "b", body = "hi\n\"quoted\"", msgId = "m-1", ts = 42L)
        val back = IrohAgentMessage.decode(msg.encode())
        assertEquals(msg, back)
    }

    @Test
    fun ackRoundTripsThroughWire() {
        val ack = IrohAgentMessageAck("m-1", accepted = true)
        assertEquals(ack, IrohAgentMessageAck.decode(ack.encode()))
    }

    @Test
    fun sendToUnregisteredAgentReturnsUnaddressableWithoutDialing() = runBlocking {
        // Empty store → resolver returns Unavailable. The sender must short-circuit
        // to Unaddressable and never touch the (here-unused) endpoint.
        val resolver = IrohAgentAddressResolver(FileIrohAgentAddressStore(tmp))
        // The endpoint PROVIDER must never be invoked because resolution fails
        // first — a throwing provider proves no dial happens.
        val sender = IrohAgentMessageSender(
            endpointProvider = { throw AssertionError("must not dial when the target is unaddressable") },
            resolver = resolver,
        )
        val result = sender.send(IrohAgentMessage("from", "nope", "body", "m-1", 1L))
        val unaddressable = assertIs<AgentSendResult.Unaddressable>(result)
        assertEquals("nope", unaddressable.toAgentId)
        assertEquals("not_registered", unaddressable.reason)
    }
}
