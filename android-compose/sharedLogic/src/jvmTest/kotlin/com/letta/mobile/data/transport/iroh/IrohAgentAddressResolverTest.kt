package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * letta-mobile-bn008.1 + letta-mobile-xmpqm: headless probe for the agent
 * address book. No networking — pure store/resolver logic + typed-unavailable
 * contract.
 *
 * The store is the host-level [HostEndpointAddressStore] backed by an in-memory
 * fake [LocalBackendAdminStore] (so membership is deterministic per test).
 */
class IrohAgentAddressResolverTest {

    private val tmp = File.createTempFile("bn008-addr", ".kv").apply { deleteOnExit() }

    /**
     * In-memory fake backend: every test sets the set of "present" agents
     * explicitly so resolve() is fully deterministic.
     */
    private class FakeBackendAdminStore(
        private val present: Set<String> = emptySet(),
    ) : LocalBackendAdminStore(File.createTempFile("bn008-fake-backend", "")) {
        override fun agentExists(agentId: String): Boolean = agentId in present
    }

    @AfterTest fun cleanup() { tmp.delete() }

    private fun resolver(present: Set<String> = setOf("agent-A")) =
        IrohAgentAddressResolver(HostEndpointAddressStore(tmp, FakeBackendAdminStore(present)))

    @Test
    fun registeredHostResolvesAgentWhenBackendHasTheAgent() {
        val r = resolver(present = setOf("agent-A"))
        // The store no longer accepts per-agent addresses — it accepts ONE host
        // record. The wire still parses as `nodeIdHex@directAddrs` so a caller
        // can construct it directly.
        val hostAddr = IrohAgentAddress("host", "aabbcc", listOf("1.2.3.4:5"))
        r.publish(hostAddr)

        val res = r.resolve("agent-A")
        val found = assertIs<AddressResolution.Found>(res)
        assertEquals("aabbcc", found.address.nodeIdHex)
        assertEquals(listOf("1.2.3.4:5"), found.address.directAddrs)
    }

    @Test
    fun unresolvedAgentReturnsTypedUnavailable_neverThrows() {
        // The host record must exist for the membership gate to even run; if
        // it's missing, resolve() returns `unknown_host` (a distinct reason).
        // For `unknown_agent` we need the host bound AND the agent absent.
        val r = resolver(present = emptySet())
        r.publish(IrohAgentAddress("host", "nodehex", listOf("1.2.3.4:1")))
        val res = r.resolve("agent-nope")
        val unavailable = assertIs<AddressResolution.Unavailable>(res)
        assertEquals("agent-nope", unavailable.agentId)
        assertEquals("unknown_agent", unavailable.reason)
    }

    @Test
    fun blankAgentIdIsTypedUnavailable_neverThrows() {
        val res = resolver().resolve("")
        assertIs<AddressResolution.Unavailable>(res)
    }

    @Test
    fun wireRoundTripPreservesNodeIdAndAddrs() {
        val addr = IrohAgentAddress("agent-A", "deadbeef", listOf("a:1", "b:2"))
        val back = IrohAgentAddress.fromWire("agent-A", addr.toWire())
        assertEquals(addr.nodeIdHex, back.nodeIdHex)
        assertEquals(addr.directAddrs, back.directAddrs)
    }

    /**
     * The host record is a single line; rebinding with a new port updates it
     * in place. unregister(agentId) is a no-op (host records are keyed by
     * host) — pinned so callers that call unregister on agentId don't crash.
     */
    @Test
    fun reRegisterOverwritesAndUnregisterIsNoOp() {
        val r = resolver(present = setOf("agent-A"))
        r.publish(IrohAgentAddress("host", "1111", listOf("x:1")))
        r.publish(IrohAgentAddress("host", "2222", emptyList()))
        val found = assertIs<AddressResolution.Found>(r.resolve("agent-A"))
        assertEquals("2222", found.address.nodeIdHex)

        HostEndpointAddressStore(tmp, FakeBackendAdminStore(setOf("agent-A"))).unregister("agent-A")
        // Host record survives — only unregister of the HOST would drop it.
        val stillFound = assertIs<AddressResolution.Found>(r.resolve("agent-A"))
        assertEquals("2222", stillFound.address.nodeIdHex)
    }
}
