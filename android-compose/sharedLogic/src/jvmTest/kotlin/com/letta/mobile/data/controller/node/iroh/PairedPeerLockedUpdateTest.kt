package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountDeviceUpgrade
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The pairing service's per-device mutators change the record under the store's lock, so a concurrent writer in
 * another process (the one-time mount capability upgrade, the host `pair` command) is never overwritten by a stale copy.
 */
class PairedPeerLockedUpdateTest {
    private val node = "d".repeat(64)

    @Test
    fun aRenameRacingTheCapabilityUpgradeKeepsBothChanges() {
        repeat(RACES) { round ->
            val file = Files.createTempDirectory("paired-peer-race").resolve("paired-peers.json")
            FilePairedPeerStore(file).save(PairedPeer(node, "before", 1L, capabilities = IrohPeerCapabilities.CHAT_ROLE))
            val pairing = IrohPairingService(FilePairedPeerStore(file))
            val upgrade = CanvasMountDeviceUpgrade.forPairingStore(file)
            val start = CountDownLatch(1)
            val renamer = thread { start.await(); pairing.rename(node, "after-$round") }
            val upgrader = thread { start.await(); upgrade.runOnce() }
            start.countDown()
            renamer.join()
            upgrader.join()

            val stored = checkNotNull(FilePairedPeerStore(file).get(node))
            assertEquals("after-$round", stored.name, "round $round lost the rename")
            assertEquals(IrohPeerCapabilities.CHAT_ROLE + IrohPeerCapabilities.MOUNT_CAPABILITIES, stored.capabilities, "round $round lost the upgrade")
        }
    }

    @Test
    fun updateWritesOnlyAChangeAndNeverReKeysOrCreates() {
        val file = Files.createTempDirectory("paired-peer-update").resolve("paired-peers.json")
        val store = FilePairedPeerStore(file)
        assertNull(store.update(node) { it.copy(name = "ghost") }, "an unpaired node is not created")
        store.save(PairedPeer(node, "name", 1L))
        val written = Files.getLastModifiedTime(file)
        assertEquals("name", store.update(node) { it }?.name)
        assertEquals(written, Files.getLastModifiedTime(file), "an unchanged record is not rewritten")
        assertFailsWith<IllegalArgumentException> { store.update(node) { it.copy(nodeId = "other") } }
        assertEquals(listOf(node), store.list().map { it.nodeId })
    }

    @Test
    fun touchSetAgentsAndSetCapabilitiesGoThroughTheLockedUpdate() {
        val store = InMemoryPairedPeerStore()
        store.save(PairedPeer(node, "name", 1L, capabilities = IrohPeerCapabilities.CHAT_ROLE))
        var now = 10_000_000L
        val pairing = IrohPairingService(store, nowMs = { now })
        pairing.touch(node)
        assertEquals(now, store.get(node)?.lastSeenAtMs)
        val seen = now
        now += 1
        pairing.touch(node)
        assertEquals(seen, store.get(node)?.lastSeenAtMs, "a touch within the interval is throttled")
        assertEquals(setOf("agent-a"), pairing.setAgentScope(node, setOf("agent-a"))?.agentIds)
        assertEquals(setOf(IrohPeerCapabilities.CHAT_READ), pairing.setCapabilities(node, setOf(IrohPeerCapabilities.CHAT_READ))?.capabilities)
        assertNull(pairing.rename("e".repeat(64), "nobody"))
        assertEquals(setOf("agent-a"), store.get(node)?.agentIds)
    }

    private companion object {
        const val RACES = 20
    }
}
