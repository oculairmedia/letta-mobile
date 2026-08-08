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

    /**
     * letta-mobile-u6hwa: the send path must reach its target whichever namespace
     * each side spells the agent in. This is the exact CLI invocation that failed
     * live (`--to letta_agent-<uuid>` → `unaddressable / not_registered` while the
     * wrapper had published the bare `agent-<uuid>`).
     *
     * Asserting "not Unaddressable" is the whole point: resolution is the step under
     * test, and dialing needs a live QUIC endpoint we deliberately do not bring up
     * here. The provider throws a distinctive marker, so reaching the dial proves
     * resolution SUCCEEDED — the sender's runCatching turns that throw into Failed.
     * Unaddressable would mean we never got past the address book.
     */
    private fun assertResolvesAcrossNamespaces(publishedAs: String, addressedAs: String) = runBlocking {
        val store = FileIrohAgentAddressStore(tmp)
        store.register(IrohAgentAddress(publishedAs, nodeIdHex = "aabb", directAddrs = listOf("10.0.0.1:60008")))

        val sender = IrohAgentMessageSender(
            endpointProvider = { throw IllegalStateException("dial-attempted") },
            resolver = IrohAgentAddressResolver(store),
        )
        val result = sender.send(IrohAgentMessage("from", addressedAs, "body", "m-2", 1L))

        val failed = assertIs<AgentSendResult.Failed>(
            result,
            "target published as '$publishedAs' must resolve when addressed as '$addressedAs' and proceed to dial",
        )
        assertEquals("dial-attempted", failed.reason)
    }

    @Test
    fun sendResolvesTargetPublishedUnderTheBareKeyWhenAddressedWithPrefix() =
        assertResolvesAcrossNamespaces(publishedAs = "agent-X", addressedAs = "letta_agent-X")

    @Test
    fun sendResolvesTargetPublishedUnderThePrefixedKeyWhenAddressedBare() =
        assertResolvesAcrossNamespaces(publishedAs = "letta_agent-X", addressedAs = "agent-X")

    /**
     * Canonicalization must widen SPELLINGS, never membership. An id that names no
     * agent stays unaddressable in both forms — otherwise the CLI would start
     * dialing garbage ids instead of reporting them.
     */
    @Test
    fun unknownTargetStaysUnaddressableInBothNamespaces(): Unit = runBlocking {
        val store = FileIrohAgentAddressStore(tmp)
        store.register(IrohAgentAddress("agent-X", nodeIdHex = "aabb"))
        val sender = IrohAgentMessageSender(
            endpointProvider = { throw AssertionError("must not dial an unknown target") },
            resolver = IrohAgentAddressResolver(store),
        )

        assertIs<AgentSendResult.Unaddressable>(
            sender.send(IrohAgentMessage("from", "agent-nope", "body", "m-4", 1L)),
        )
        assertIs<AgentSendResult.Unaddressable>(
            sender.send(IrohAgentMessage("from", "letta_agent-nope", "body", "m-5", 1L)),
        )
    }
}
