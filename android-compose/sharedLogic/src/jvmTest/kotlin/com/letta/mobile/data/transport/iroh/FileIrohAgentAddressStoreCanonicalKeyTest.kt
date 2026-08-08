package com.letta.mobile.data.transport.iroh

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-u6hwa: the address book keyspace is CANONICAL.
 *
 * `agent-X` and `letta_agent-X` name the same agent, so they must name the same
 * row — regardless of which form the writer used or the reader asks for. These are
 * hermetic (a temp kv file, no networking, no iroh native binding).
 *
 * The regression being pinned is NOT merely "prefixed lookups miss". The live
 * failure was worse: the wrapper published under the bare key while an older bind
 * had left a `letta_`-prefixed row behind, so the prefixed row survived pointing at
 * a port nothing was listening on. A prefixed lookup then resolved to a STALE
 * address and blackholed, instead of cleanly reporting `not_registered`. A test
 * that only asserts "the row is present" would have passed while the system was
 * broken — so the eviction cases below assert the duplicate is GONE, not merely
 * shadowed.
 */
class FileIrohAgentAddressStoreCanonicalKeyTest {

    private val tmp = File.createTempFile("u6hwa-addr", ".kv").apply { deleteOnExit() }

    @AfterTest fun cleanup() { tmp.delete() }

    private fun store() = FileIrohAgentAddressStore(tmp)

    private fun rows(): List<String> =
        tmp.readLines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `registered bare resolves under the letta_ prefixed form`() {
        store().register(IrohAgentAddress("agent-X", nodeIdHex = "aabb", directAddrs = listOf("1.2.3.4:5")))

        val found = assertIs<AddressResolution.Found>(store().resolve("letta_agent-X"))
        assertEquals("aabb", found.address.nodeIdHex)
        assertEquals(listOf("1.2.3.4:5"), found.address.directAddrs)
    }

    @Test
    fun `registered letta_ prefixed resolves under the bare form`() {
        store().register(IrohAgentAddress("letta_agent-X", nodeIdHex = "ccdd", directAddrs = listOf("9.9.9.9:1")))

        val found = assertIs<AddressResolution.Found>(store().resolve("agent-X"))
        assertEquals("ccdd", found.address.nodeIdHex)
    }

    @Test
    fun `registering both forms writes exactly one row`() {
        val s = store()
        s.register(IrohAgentAddress("letta_agent-X", nodeIdHex = "1111"))
        s.register(IrohAgentAddress("agent-X", nodeIdHex = "2222"))

        assertEquals(listOf("agent-X=2222"), rows())
    }

    /**
     * The live-corruption regression. A pre-existing file holds BOTH namespaces for
     * one agent at DIFFERENT wire values (the 49357-vs-60008 incident). A single
     * register() must collapse them to one row at the new value — if the stale row
     * merely stops being preferred but stays on disk, the fix is incomplete and the
     * next reader can still dial a dead port.
     */
    @Test
    fun `register evicts a stale duplicate row from the other namespace`() {
        tmp.writeText(
            """
            letta_agent-X=deadbeef@10.0.0.1:49357
            agent-X=deadbeef@10.0.0.1:49357
            agent-other=cafe@10.0.0.2:60008
            """.trimIndent() + "\n",
        )

        store().register(IrohAgentAddress("agent-X", nodeIdHex = "deadbeef", directAddrs = listOf("10.0.0.1:60008")))

        val remaining = rows()
        assertTrue(
            remaining.none { it.startsWith("letta_") },
            "stale letta_-prefixed row survived eviction: $remaining",
        )
        assertEquals(1, remaining.count { it.startsWith("agent-X=") }, "expected exactly one row for agent-X: $remaining")
        assertTrue(
            remaining.any { it == "agent-X=deadbeef@10.0.0.1:60008" },
            "agent-X row was not updated to the live address: $remaining",
        )
        // An unrelated agent must not be collateral damage of the collapse.
        assertTrue(remaining.any { it == "agent-other=cafe@10.0.0.2:60008" }, "unrelated row lost: $remaining")
    }

    /**
     * Reading a legacy dual-namespace file must prefer the BARE row: it is the form
     * the live publish path writes, so it is the one backed by the current bind.
     * Preferring the prefixed row here would resolve to precisely the stale address
     * that caused the incident.
     */
    @Test
    fun `bare row wins when a legacy file holds both namespaces`() {
        tmp.writeText(
            """
            letta_agent-X=stale@10.0.0.1:49357
            agent-X=live@10.0.0.1:60008
            """.trimIndent() + "\n",
        )

        val viaPrefixed = assertIs<AddressResolution.Found>(store().resolve("letta_agent-X"))
        val viaBare = assertIs<AddressResolution.Found>(store().resolve("agent-X"))
        assertEquals("live", viaPrefixed.address.nodeIdHex, "prefixed lookup resolved to the stale row")
        assertEquals("live", viaBare.address.nodeIdHex)
    }

    /** Bare-row precedence must not depend on line order in the file. */
    @Test
    fun `bare row wins regardless of file line order`() {
        tmp.writeText(
            """
            agent-X=live@10.0.0.1:60008
            letta_agent-X=stale@10.0.0.1:49357
            """.trimIndent() + "\n",
        )

        val found = assertIs<AddressResolution.Found>(store().resolve("letta_agent-X"))
        assertEquals("live", found.address.nodeIdHex)
    }

    @Test
    fun `unregister removes the row whichever namespace is used`() {
        val s = store()
        s.register(IrohAgentAddress("agent-X", nodeIdHex = "1111"))
        s.unregister("letta_agent-X")

        assertIs<AddressResolution.Unavailable>(s.resolve("agent-X"))
        assertTrue(rows().isEmpty(), "row survived unregister: ${rows()}")
    }

    @Test
    fun `unregister via bare form clears a legacy prefixed row`() {
        tmp.writeText("letta_agent-X=stale@10.0.0.1:49357\n")

        store().unregister("agent-X")

        assertIs<AddressResolution.Unavailable>(store().resolve("letta_agent-X"))
        assertTrue(rows().isEmpty(), "legacy prefixed row survived unregister: ${rows()}")
    }

    /**
     * An unknown agent must stay unaddressable — canonicalization widens which
     * spellings hit a row, never which agents exist. It must also echo back the id
     * the CALLER passed, or an operator reading the error hunts the wrong agent.
     */
    @Test
    fun `unknown agent is unavailable and echoes the caller's spelling`() {
        store().register(IrohAgentAddress("agent-X", nodeIdHex = "1111"))

        val res = assertIs<AddressResolution.Unavailable>(store().resolve("letta_agent-nope"))
        assertEquals("letta_agent-nope", res.agentId)
        assertEquals("not_registered", res.reason)
    }

    /**
     * `normalizeToBareId` strips a leading `letta_` from ANY id, so a non-agent id
     * is canonicalized the same way. Pinned so the store's behaviour stays defined
     * if the namespace helper's scope ever changes.
     */
    @Test
    fun `canonicalization applies consistently to non-agent ids`() {
        store().register(IrohAgentAddress("letta_custom", nodeIdHex = "3333"))

        val found = assertIs<AddressResolution.Found>(store().resolve("custom"))
        assertEquals("3333", found.address.nodeIdHex)
        assertEquals(listOf("custom=3333"), rows())
    }
}
