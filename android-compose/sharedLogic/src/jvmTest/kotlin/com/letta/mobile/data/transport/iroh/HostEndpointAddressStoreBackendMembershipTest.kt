package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * letta-mobile-xmpqm: backend-membership gate for the host-level address book.
 *
 * Background. With [FileIrohAgentAddressStore] (the prior shape), an agent was
 * reachable iff a row for it existed in the kv file. That row was written by
 * the wrapper's bind-time publish step, so reachability required enumeration
 * of every agent at boot — the defect that forced LETTA_A2A_PUBLISH_AGENTS to
 * exist as an allowlist (and to need scaling to 1462+ ids).
 *
 * The new [HostEndpointAddressStore] drops the per-agent row. Reachability is
 * gated by [LocalBackendAdminStore.agentExists] instead: an agent is
 * reachable iff (a) the host has bound (host record exists) AND (b) the
 * agentId names an agent in the local backend dir.
 *
 * This file pins that contract with a hermetic, in-memory fake of
 * [LocalBackendAdminStore] (no on-disk backend, no iroh native binding).
 */
class HostEndpointAddressStoreBackendMembershipTest {

    private val tmpKv = File.createTempFile("xmpqm-backend-addr", ".kv").apply { deleteOnExit() }

    @AfterTest fun cleanup() { tmpKv.delete() }

    private val hostAddr = IrohAgentAddress(
        agentId = "host-only",
        nodeIdHex = "330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f",
        directAddrs = listOf("192.168.50.90:4501"),
    )

    /** In-memory subclass — no on-disk backend, no iroh binding. */
    private class FakeBackendAdminStore(
        private val present: Set<String>,
    ) : LocalBackendAdminStore(File.createTempFile("xmpqm-fake-backend", "")) {
        override fun agentExists(agentId: String): Boolean = agentId in present
    }

    @Test
    fun `resolve returns Found when host record exists AND backend has the agent`() {
        val backend = FakeBackendAdminStore(present = setOf("agent-1"))
        val store = HostEndpointAddressStore(tmpKv, backend)
        store.register(hostAddr)

        val found = assertIs<AddressResolution.Found>(store.resolve("agent-1"))
        assertEquals(hostAddr.nodeIdHex, found.address.nodeIdHex)
        assertEquals(hostAddr.directAddrs, found.address.directAddrs)
        assertEquals("agent-1", found.address.agentId, "found must echo the caller's spelling")
    }

    @Test
    fun `resolve returns unknown_agent for a ghost id even when the host is bound`() {
        val backend = FakeBackendAdminStore(present = setOf("agent-1"))
        val store = HostEndpointAddressStore(tmpKv, backend)
        store.register(hostAddr)

        val res = assertIs<AddressResolution.Unavailable>(store.resolve("ghost"))
        assertEquals("ghost", res.agentId)
        assertEquals("unknown_agent", res.reason)
    }

    @Test
    fun `resolve returns unknown_agent when host bound but backend has no agents at all`() {
        val backend = FakeBackendAdminStore(present = emptySet())
        val store = HostEndpointAddressStore(tmpKv, backend)
        store.register(hostAddr)

        val res = assertIs<AddressResolution.Unavailable>(store.resolve("anything"))
        assertEquals("unknown_agent", res.reason)
    }

    @Test
    fun `resolve returns unknown_host when no host record has ever been written`() {
        val backend = FakeBackendAdminStore(present = setOf("agent-1"))
        val store = HostEndpointAddressStore(tmpKv, backend)
        // No register() call — host record absent.

        val res = assertIs<AddressResolution.Unavailable>(store.resolve("anything"))
        assertEquals("unknown_host", res.reason, "absent host record must report unknown_host")
    }

    /**
     * The wrapper never calls `register()` per agent — it calls `register()`
     * once at bind, then `resolve()` looks up the agent via membership.
     * Pin that the membership-gate path actually finds agents that exist in
     * the backend WITHOUT needing them to be published: this is the
     * unreachable-bug fix in xmpqm.
     */
    @Test
    fun `resolve finds agents that were never published explicitly`() {
        val backend = FakeBackendAdminStore(present = setOf("agent-1", "agent-2", "agent-3"))
        val store = HostEndpointAddressStore(tmpKv, backend)
        store.register(hostAddr)

        // None of these were ever passed to register() — only the host record was.
        for (id in listOf("agent-1", "agent-2", "agent-3")) {
            val res = store.resolve(id)
            val found = assertIs<AddressResolution.Found>(res, "agent $id must resolve via membership")
            assertEquals(hostAddr.nodeIdHex, found.address.nodeIdHex)
            assertEquals(id, found.address.agentId, "found must echo the caller's spelling for $id")
        }
    }

    /**
     * Without a backend store injected, the membership gate is disabled — the
     * store answers "host reachable" only. This is the back-compat path for
     * callers that don't have a backend root handy; it MUST NOT crash, and it
     * MUST still return Found for any spelling the caller asks about (the
     * gated contract is opt-in).
     */
    @Test
    fun `no backend store injected disables the membership gate`() {
        val store = HostEndpointAddressStore(tmpKv, backendStore = null)
        store.register(hostAddr)

        val found = assertIs<AddressResolution.Found>(store.resolve("ghost"))
        assertEquals(hostAddr.nodeIdHex, found.address.nodeIdHex)
    }

    /**
     * The host record carries ONE node id. Re-registering with the SAME node
     * id but different direct addrs (a rebind with a new port) must update the
     * wire in place — the membership gate does NOT need to be re-asked.
     */
    @Test
    fun `rebind updates the wire in place and resolve reflects the new port`() {
        val backend = FakeBackendAdminStore(present = setOf("agent-1"))
        val store = HostEndpointAddressStore(tmpKv, backend)
        store.register(hostAddr)

        val rebound = hostAddr.copy(directAddrs = listOf("192.168.50.90:60008"))
        store.register(rebound)

        val found = assertIs<AddressResolution.Found>(store.resolve("agent-1"))
        assertEquals(listOf("192.168.50.90:60008"), found.address.directAddrs)
    }
}
