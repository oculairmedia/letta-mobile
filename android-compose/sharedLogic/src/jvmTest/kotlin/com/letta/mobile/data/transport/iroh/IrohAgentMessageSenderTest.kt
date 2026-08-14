package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * letta-mobile-bn008.2 + letta-mobile-xmpqm: pure-unit coverage for the send
 * path that needs no live QUIC endpoint (the real-loopback probe is
 * IrohAgentMessageSendE2ETest, opt-in).
 *
 * Covers the envelope wire round-trip and the unaddressable short-circuit — a
 * send to an unregistered agent must NEVER dial and must return a typed
 * result. letta-mobile-xmpqm: the store is the host-level
 * [HostEndpointAddressStore] backed by an in-memory fake
 * [LocalBackendAdminStore] so membership is deterministic per test.
 */
class IrohAgentMessageSenderTest {

    private val tmp = File.createTempFile("bn008-2-unit", ".kv").apply { deleteOnExit() }
    @AfterTest fun cleanup() { tmp.delete() }

    private class FakeBackendAdminStore(
        private val present: Set<String>,
    ) : LocalBackendAdminStore(File.createTempFile("bn008-2-fake-backend", "")) {
        override fun agentExists(agentId: String): Boolean = agentId in present
    }

    @Test
    fun envelopeRoundTripsThroughWire() {
        val msg = IrohAgentMessage("a", "b", body = "hi\n\"quoted\"", msgId = "m-1", ts = 42L)
        val back = IrohAgentMessage.decode(msg.encode())
        assertEquals(msg, back)
    }

    @Test
    fun ackRoundTripsThroughWire() {
        val ack = IrohAgentMessageAck("m-1", accepted = true, applicationDelivered = true)
        assertEquals(ack, IrohAgentMessageAck.decode(ack.encode()))
    }

    @Test
    fun sendToUnregisteredAgentReturnsUnaddressableWithoutDialing() = runBlocking {
        // Host record EXISTS (the wrapper has bound) but the target agent is
        // NOT in the backend → resolver returns Unavailable("unknown_agent").
        // The sender must short-circuit to Unaddressable and never touch the
        // (here-unused) endpoint.
        val store = HostEndpointAddressStore(tmp, FakeBackendAdminStore(present = emptySet()))
        store.register(IrohAgentAddress("host", "nodehex", listOf("1.2.3.4:1")))
        val resolver = IrohAgentAddressResolver(store)
        // The endpoint PROVIDER must never be invoked because resolution fails
        // first — a throwing provider proves no dial happens.
        val sender = IrohAgentMessageSender(
            endpointProvider = { throw AssertionError("must not dial when the target is unaddressable") },
            resolver = resolver,
        )
        val result = sender.send(IrohAgentMessage("from", "nope", "body", "m-1", 1L))
        val unaddressable = assertIs<AgentSendResult.Unaddressable>(result)
        assertEquals("nope", unaddressable.toAgentId)
        assertEquals("unknown_agent", unaddressable.reason)
    }

    /**
     * letta-mobile-u6hwa: the send path must reach its target whichever namespace
     * each side spells the agent in. This is the exact CLI invocation that failed
     * live (`--to letta_agent-<uuid>` → `unaddressable / unknown_agent` while the
     * wrapper had published the bare `agent-<uuid>`).
     *
     * Asserting "not Unaddressable" is the whole point: resolution is the step under
     * test, and dialing needs a live QUIC endpoint we deliberately do not bring up
     * here. The provider throws a distinctive marker, so reaching the dial proves
     * resolution SUCCEEDED — the sender's runCatching turns that throw into Failed.
     * Unaddressable would mean we never got past the address book.
     *
     * letta-mobile-xmpqm: the host record is shared, so "published as" is
     * decorative here — what matters is that the agentId exists in the
     * backend (regardless of the `agentId` field on the wire). The host
     * record's wire `agentId` is a placeholder for the kv format; resolve()
     * reads the membership oracle with the caller's spelling.
     */
    private fun assertResolvesAcrossNamespaces(targetId: String, addressedAs: String) = runBlocking {
        // The fake backend is queried with the CANONICAL (bare) form — that's
        // what the resolver passes to agentExists(). Both spellings of an
        // agent canonicalize to the same bare id, so the fake's present set
        // always uses the bare form.
        val canonicalId = com.letta.mobile.data.model.AgentIdNamespace
            .normalizeToBareId(targetId)
        val store = HostEndpointAddressStore(tmp, FakeBackendAdminStore(present = setOf(canonicalId)))
        store.register(IrohAgentAddress("host", nodeIdHex = "aabb", directAddrs = listOf("10.0.0.1:60008")))

        val sender = IrohAgentMessageSender(
            endpointProvider = { throw IllegalStateException("dial-attempted") },
            resolver = IrohAgentAddressResolver(store),
        )
        val result = sender.send(IrohAgentMessage("from", addressedAs, "body", "m-2", 1L))

        val failed = assertIs<AgentSendResult.Failed>(
            result,
            "target '$targetId' must resolve when addressed as '$addressedAs' and proceed to dial",
        )
        assertEquals("dial-attempted", failed.reason)
    }

    @Test
    fun sendResolvesTargetKnownAsBareKeyWhenAddressedWithPrefix() =
        assertResolvesAcrossNamespaces(targetId = "agent-X", addressedAs = "letta_agent-X")

    @Test
    fun sendResolvesTargetKnownAsPrefixedKeyWhenAddressedBare() =
        assertResolvesAcrossNamespaces(targetId = "letta_agent-X", addressedAs = "agent-X")

    /**
     * Canonicalization must widen SPELLINGS, never membership. An id that names no
     * agent stays unaddressable in both forms — otherwise the CLI would start
     * dialing garbage ids instead of reporting them.
     */
    @Test
    fun unknownTargetStaysUnaddressableInBothNamespaces(): Unit = runBlocking {
        val store = HostEndpointAddressStore(tmp, FakeBackendAdminStore(present = setOf("agent-X")))
        store.register(IrohAgentAddress("host", nodeIdHex = "aabb"))
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
