package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-xmpqm + letta-mobile-u6hwa: the host-level address book must
 * canonicalize SPELLINGS through the backend-membership gate (not through a
 * duplicated per-agent row), and must NOT widen MEMBERSHIP.
 *
 * In the prior shape, canonicalization collapsed a bare + `letta_`-prefixed
 * pair of rows onto one row. With the host record, there is no per-agent row
 * to collide: canonicalization now applies to the membership lookup, so
 * `resolve("letta_agent-X")` and `resolve("agent-X")` both pass through
 * [LocalBackendAdminStore.agentExists] under the canonical (bare) key, and
 * both return the same host record.
 *
 * Pinned regression: an id that names no agent must stay unaddressable in both
 * spellings — otherwise the CLI would silently start dialing garbage ids
 * instead of reporting them.
 *
 * Hermetic: the LocalBackendFixtureStore writes only synthetic ids under a
 * temp root, so no real-user data leaks.
 */
class HostEndpointAddressStoreCanonicalKeyTest {

    private val tmpKv = File.createTempFile("xmpqm-canonical-addr", ".kv").apply { deleteOnExit() }
    private val tmpBackend = File.createTempFile("xmpqm-canonical-backend", "").apply {
        delete(); mkdirs(); deleteOnExit()
    }
    private val backend = LocalBackendAdminStore(tmpBackend)

    @AfterTest fun cleanup() {
        tmpKv.delete()
        tmpBackend.deleteRecursively()
    }

    private fun store() = HostEndpointAddressStore(tmpKv, backend)

    private val hostAddr = IrohAgentAddress(
        agentId = "host-only",
        nodeIdHex = "330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f",
        directAddrs = listOf("192.168.50.90:4501"),
    )

    @Test
    fun `registered host resolves under the letta_ prefixed form of an existing agent`() {
        // Seed the backend with `agent-X` (bare).
        com.letta.mobile.data.controller.node.iroh.LocalBackendFixtureStore
            .writeAgent(tmpBackend, "agent-X", name = "canonical fixture")

        store().register(hostAddr)

        val found = assertIs<AddressResolution.Found>(store().resolve("letta_agent-X"))
        assertEquals(hostAddr.nodeIdHex, found.address.nodeIdHex)
        assertEquals(hostAddr.directAddrs, found.address.directAddrs)
    }

    @Test
    fun `registered host resolves under the bare form of an existing agent`() {
        // Seed the backend with `agent-Y` (the canonical bare form). The
        // membership oracle is asked with the canonical key by the resolver,
        // so the file must be at the canonical location regardless of what
        // spelling the caller uses to ask.
        com.letta.mobile.data.controller.node.iroh.LocalBackendFixtureStore
            .writeAgent(tmpBackend, "agent-Y", name = "canonical fixture y")

        store().register(hostAddr)

        val found = assertIs<AddressResolution.Found>(store().resolve("agent-Y"))
        assertEquals(hostAddr.nodeIdHex, found.address.nodeIdHex)
    }

    /**
     * An unknown id — one with NO backend record — must stay unavailable in both
     * spellings. This is the canonical-key regression that u6hwa left behind: the
     * keyspace widens SPELLINGS (so the lookup hits a row that exists) but must
     * NEVER widen MEMBERSHIP (so a spelling of a non-existent agent does NOT
     * silently become a hit).
     */
    @Test
    fun `unknown agent is unavailable and echoes the caller's spelling`() {
        com.letta.mobile.data.controller.node.iroh.LocalBackendFixtureStore
            .writeAgent(tmpBackend, "agent-X", name = "canonical fixture")

        store().register(hostAddr)

        val res = assertIs<AddressResolution.Unavailable>(store().resolve("letta_agent-nope"))
        assertEquals("letta_agent-nope", res.agentId)
        assertEquals("unknown_agent", res.reason)

        val resBare = assertIs<AddressResolution.Unavailable>(store().resolve("agent-nope"))
        assertEquals("agent-nope", resBare.agentId)
        assertEquals("unknown_agent", resBare.reason)
    }

    /**
     * Even with the host bound, an absent host record means the wrapper has
     * never bound on this disk — return `unknown_host`, distinct from
     * `unknown_agent`, so an operator can tell "wrapper not bound" from
     * "agent not in backend dir".
     */
    @Test
    fun `no host record returns unknown_host even when agent exists`() {
        com.letta.mobile.data.controller.node.iroh.LocalBackendFixtureStore
            .writeAgent(tmpBackend, "agent-X", name = "canonical fixture")

        // No register() call — host record absent.
        val res = assertIs<AddressResolution.Unavailable>(store().resolve("agent-X"))
        assertEquals("unknown_host", res.reason)
    }

    /**
     * resolve() must echo the agentId the CALLER used, not the canonical form,
     * so an operator reading the failure hunts the spelling they sent.
     */
    @Test
    fun `resolve echoes the caller spelling on Found`() {
        com.letta.mobile.data.controller.node.iroh.LocalBackendFixtureStore
            .writeAgent(tmpBackend, "agent-X", name = "canonical fixture")

        store().register(hostAddr)

        val found = assertIs<AddressResolution.Found>(store().resolve("letta_agent-X"))
        assertEquals("letta_agent-X", found.address.agentId, "found must echo the caller's spelling")
    }

    /**
     * Multi-host YAGNI for Phase 1, but the kv format already supports it:
     * `host:<hostKey>=<wire>` per host. A second register() with a DIFFERENT
     * node id would write a second line. This test pins the single-host
     * contract by registering the SAME node id twice and asserting the kv
     * file stays at one line.
     */
    @Test
    fun `repeated register of the same host collapses to one kv line`() {
        val s = store()
        s.register(hostAddr)
        s.register(hostAddr)
        s.register(hostAddr.copy(directAddrs = listOf("192.168.50.90:60008")))

        val lines = tmpKv.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(1, lines.size, "kv file must collapse repeated host registers to one line")
        assertTrue(lines.single().startsWith("host:"))
        assertTrue(lines.single().contains(":60008"), "wire must reflect the latest bind")
    }
}
