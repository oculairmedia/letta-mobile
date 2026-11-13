package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.data.controller.node.iroh.mounts.upgrade.CanvasMountDeviceUpgrade
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

private val MOUNT = setOf(IrohPeerCapabilities.CANVAS_MOUNT_READ, IrohPeerCapabilities.CANVAS_MOUNT_WRITE)

private fun preMount(role: String): Set<String> = IrohPeerCapabilities.ROLES.getValue(role) - MOUNT

/** Owner decision D7: the one-time, idempotent mount-capability upgrade of devices paired before canvas mounts. */
class CanvasMountDeviceUpgradeTest {
    private val file = Files.createTempDirectory("canvas-mount-upgrade").resolve("paired-peers.json")
    private val store = FilePairedPeerStore(file)

    @Test
    fun exactlyThePreMountChatRolesGainTheTwoMountCapabilitiesAndNothingElse() {
        seed()
        val outcome = CanvasMountDeviceUpgrade.forPairingStore(file).runOnce()
        assertEquals(CanvasMountDeviceUpgrade.Outcome.Applied(upgraded = 4), outcome)
        listOf("chat", "desktop", "vibesync").forEach { role ->
            assertEquals(IrohPairingService.rolesFor(mountsEnabled = true).getValue(role), store.get(role)?.capabilities, role)
        }
        assertEquals(preMount("chat") + MOUNT, store.get("scoped-chat")?.capabilities)
        assertEquals(setOf("agent-a"), store.get("scoped-chat")?.agentIds, "agent scope is kept")
        assertEquals(setOf(IrohPeerCapabilities.CHAT_READ), store.get("hand-scoped")?.capabilities)
        assertEquals(setOf(IrohPeerCapabilities.ADMIN_FULL), store.get("admin")?.capabilities)
    }

    @Test
    fun itRunsOnceAndRerunningBeforeTheMarkerChangesNothing() {
        seed()
        val upgrade = CanvasMountDeviceUpgrade.forPairingStore(file)
        upgrade.runOnce()
        val afterFirst = store.list()
        // An administrator later re-scopes a device to the pre-mount chat role: the marker keeps that choice.
        store.save(checkNotNull(store.get("chat")).copy(capabilities = preMount("chat")))
        assertEquals(CanvasMountDeviceUpgrade.Outcome.AlreadyApplied, upgrade.runOnce())
        assertEquals(preMount("chat"), store.get("chat")?.capabilities)

        // Without the marker (a crash before it was written) a rerun only touches still-pre-mount records.
        store.save(checkNotNull(store.get("chat")).copy(capabilities = preMount("chat") + MOUNT))
        Files.list(file.parent).use { files -> files.filter { it.fileName.toString().endsWith(".canvas-mount-v1") }.toList() }
            .forEach(Files::delete)
        assertEquals(CanvasMountDeviceUpgrade.Outcome.Applied(upgraded = 0), upgrade.runOnce())
        assertEquals(afterFirst, store.list())
    }

    private fun seed() {
        listOf("chat", "desktop", "vibesync").forEachIndexed { index, role -> store.save(peer(role, preMount(role), index)) }
        store.save(peer("scoped-chat", preMount("chat"), 3).copy(agentIds = setOf("agent-a")))
        store.save(peer("hand-scoped", setOf(IrohPeerCapabilities.CHAT_READ), 4))
        store.save(peer("admin", setOf(IrohPeerCapabilities.ADMIN_FULL), 5))
    }

    private fun peer(id: String, capabilities: Set<String>, order: Int) =
        PairedPeer(id, id, pairedAtMs = order.toLong(), capabilities = capabilities)
}
