package com.letta.mobile.data.transport.iroh

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-xmpqm: regression-pin the host-level address-book shape.
 *
 * Background. The previous [FileIrohAgentAddressStore] wrote ONE kv row per
 * agent (`agentId=<wire>`). Every row carried the SAME node id + the SAME
 * 54-element direct-address list, so the file held 1462 identical rows at
 * ~1.1 KB each (~1.6 MB). `register()` did `readAll() + writeAll()` of the
 * whole file per agent under `@Synchronized`, so publishing n agents was
 * O(n²) bytes of file I/O — and every rebind rewrote all 1462 rows.
 *
 * The new [HostEndpointAddressStore] holds exactly ONE `host:` record per
 * host regardless of agent count. These tests pin that contract: the kv
 * file MUST stay O(1) in size even when `register(host)` is called 1462
 * times, AND a legacy per-agent file MUST be collapsed to the single host
 * record on the next `register()` (no stale per-agent rows can survive
 * migration — that was the live 49357-era incident, generalized).
 *
 * Hermetic: pure file I/O over a temp file, no iroh native binding.
 */
class HostEndpointAddressStoreRegressionTest {

    @Test
    fun `register is O(1) regardless of agent count`() {
        val tempFile = File.createTempFile("host-endpoint-test", ".kv").apply { deleteOnExit() }
        val store = HostEndpointAddressStore(tempFile)

        repeat(PRODUCTION_AGENT_COUNT) {
            store.register(
                IrohAgentAddress(
                    agentId = "letta_agent-test-$it",
                    nodeIdHex = PRODUCTION_NODE_ID_HEX,
                    directAddrs = listOf("192.168.50.90:4501"),
                ),
            )
        }

        val content = tempFile.readText()
        val lineCount = content.lines().filter { it.isNotBlank() }.size
        assertEquals(
            1,
            lineCount,
            "kv file must be O(1) in size; got $lineCount lines (regression: per-agent rows returned)",
        )
        assertTrue(content.startsWith("host:"), "kv file must use the host: prefix")
        assertTrue(
            content.contains(PRODUCTION_NODE_ID_HEX),
            "kv file must carry the live node id",
        )
        tempFile.delete()
    }

    /**
     * A legacy per-agent file (the shape [FileIrohAgentAddressStore] used to
     * write) must collapse to a single host record on the next `register()`
     * — including any `letta_`-prefixed or bare-keyed rows. Stale rows
     * lingering on disk after migration is the exact failure mode that
     * produced the 49357 blackhole (u6hwa), generalized across the whole
     * keyspace. Pin it.
     */
    @Test
    fun `register from legacy per-agent file migrates to single host record`() {
        val tempFile = File.createTempFile("legacy-test", ".kv").apply { deleteOnExit() }
        tempFile.writeText(
            """
            agent-1=$PRODUCTION_NODE_ID_HEX@192.168.50.90:4501
            letta_agent-1=$PRODUCTION_NODE_ID_HEX@192.168.50.90:4501
            agent-2=$PRODUCTION_NODE_ID_HEX@192.168.50.90:4501
            agent-3=$PRODUCTION_NODE_ID_HEX@192.168.50.90:4501
            letta_agent-4=$PRODUCTION_NODE_ID_HEX@192.168.50.90:4501
            """.trimIndent(),
        )

        val store = HostEndpointAddressStore(tempFile)
        store.register(
            IrohAgentAddress(
                agentId = "agent-1",
                nodeIdHex = PRODUCTION_NODE_ID_HEX,
                directAddrs = listOf("192.168.50.90:4501"),
            ),
        )

        val content = tempFile.readText()
        val lineCount = content.lines().filter { it.isNotBlank() }.size
        assertEquals(1, lineCount, "kv file must collapse legacy per-agent rows to one host record")
        assertTrue(content.startsWith("host:"), "kv file must use the host: prefix after migration")
        assertTrue(
            "agent-1" !in content && "agent-2" !in content && "agent-3" !in content &&
                "letta_agent-4" !in content,
            "legacy per-agent rows must NOT survive migration: $content",
        )
        tempFile.delete()
    }

    /**
     * Even when the legacy file has BARE and `letta_`-prefixed rows pointing
     * at DIFFERENT wires (the u6hwa canonical-key incident), the next
     * `register()` collapses to a single host record. The old
     * canonical-key-collapsing logic at `readAll()` is gone — the host
     * store has nothing to canonicalize per-agent — so this pins the new
     * contract: legacy rows get evicted wholesale on migration, not
     * shadowed.
     */
    @Test
    fun `register evicts legacy dual-namespace rows with divergent wires`() {
        val tempFile = File.createTempFile("legacy-divergent", ".kv").apply { deleteOnExit() }
        tempFile.writeText(
            """
            letta_agent-X=stale@10.0.0.1:49357
            agent-X=live@10.0.0.1:60008
            agent-other=cafe@10.0.0.2:60008
            """.trimIndent(),
        )

        val store = HostEndpointAddressStore(tempFile)
        store.register(
            IrohAgentAddress(
                agentId = "agent-X",
                nodeIdHex = PRODUCTION_NODE_ID_HEX,
                directAddrs = listOf("10.0.0.1:60008"),
            ),
        )

        val content = tempFile.readText()
        val lineCount = content.lines().filter { it.isNotBlank() }.size
        assertEquals(1, lineCount, "legacy dual-namespace file must collapse to one host record")
        assertTrue(content.startsWith("host:"), "kv file must use the host: prefix after migration")
        assertTrue("49357" !in content, "stale 49357 wire must be evicted: $content")
        tempFile.delete()
    }

    @Test
    fun `register rewrites the host record on rebind with new direct addrs`() {
        // bind #1
        val tempFile = File.createTempFile("rebind-test", ".kv").apply { deleteOnExit() }
        val store = HostEndpointAddressStore(tempFile)
        store.register(
            IrohAgentAddress(
                agentId = "host-only",
                nodeIdHex = PRODUCTION_NODE_ID_HEX,
                directAddrs = listOf("192.168.50.90:4501"),
            ),
        )
        // bind #2: same host (same node id), new port after rebind
        store.register(
            IrohAgentAddress(
                agentId = "host-only",
                nodeIdHex = PRODUCTION_NODE_ID_HEX,
                directAddrs = listOf("192.168.50.90:60008"),
            ),
        )

        val content = tempFile.readText()
        val lines = content.lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size, "kv file must stay at one line across rebinds")
        assertTrue(lines.single().contains(":60008"), "wire must reflect the rebind port")
        assertTrue(":4501" !in content, "stale pre-rebind port must be gone")
        tempFile.delete()
    }

    companion object {
        // Pinned to the live observed values from the bead description
        // (letta-mobile-xmpqm, raised 2026-08-08). The kv file holds the host
        // record regardless of how many agents share it; the count here is
        // the regression value (1462 agents at the time of the bead).
        const val PRODUCTION_AGENT_COUNT = 1462
        const val PRODUCTION_NODE_ID_HEX =
            "330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f"
    }
}
