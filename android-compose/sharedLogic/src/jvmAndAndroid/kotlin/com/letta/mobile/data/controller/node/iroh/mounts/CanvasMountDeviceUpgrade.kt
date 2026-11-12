package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.controller.node.iroh.AtomicJsonFile
import com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.data.controller.node.iroh.PairedPeerStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Owner decision D7: devices paired before canvas mounts existed gain `canvas.mount.read` and `canvas.mount.write`
 * once, as if paired today under the role they were paired with (D2).
 *
 * - A record is upgraded only when its capability set is EXACTLY the chat, desktop or vibesync role as it was before
 *   D2 (that role minus the two mount capabilities). Any other set (hand-scoped, admin, already upgraded) is left as
 *   it is, and nothing but the two mount capabilities is ever added. Agent scope and every other field are kept.
 * - All records change in one locked read-modify-write ([PairedPeerStore.updateEach]), so a concurrent pair or revoke
 *   is neither lost nor undone.
 * - [marker] records that the upgrade ran; it never runs again, so a device an administrator later re-scopes to a
 *   pre-D2 role keeps that choice. Re-running before the marker is written is harmless: an upgraded set no longer
 *   matches a pre-D2 role.
 * - The host calls this only when it runs with `--enable-canvas-mounts`.
 */
class CanvasMountDeviceUpgrade(private val peers: PairedPeerStore, private val marker: Path) {
    /** How one call ended. */
    sealed interface Outcome {
        /** The marker was already there: nothing was read or written. */
        data object AlreadyApplied : Outcome

        /** The upgrade ran now; [upgraded] records gained the two mount capabilities. */
        data class Applied(val upgraded: Int) : Outcome
    }

    fun runOnce(): Outcome {
        if (Files.exists(marker)) return Outcome.AlreadyApplied
        val upgraded = peers.updateEach(::upgraded)
        AtomicJsonFile.write(marker, """{"upgrade":"$UPGRADE_NAME","upgraded":$upgraded}""")
        return Outcome.Applied(upgraded)
    }

    private fun upgraded(peer: PairedPeer): PairedPeer =
        if (peer.capabilities in PRE_MOUNT_ROLES) peer.copy(capabilities = peer.capabilities + MOUNT_CAPABILITIES) else peer

    companion object {
        private const val UPGRADE_NAME = "canvas-mount-v1"

        private val MOUNT_CAPABILITIES = setOf(IrohPeerCapabilities.CANVAS_MOUNT_READ, IrohPeerCapabilities.CANVAS_MOUNT_WRITE)

        /** The chat, desktop and vibesync roles exactly as they were before D2 added the mount capabilities. */
        private val PRE_MOUNT_ROLES: Set<Set<String>> =
            listOf("chat", "desktop", "vibesync").mapTo(mutableSetOf()) { IrohPeerCapabilities.ROLES.getValue(it) - MOUNT_CAPABILITIES }

        /** The upgrade of the paired-device file [pairedPeers], its marker a hidden sibling of that file. */
        fun forPairingStore(pairedPeers: Path): CanvasMountDeviceUpgrade =
            CanvasMountDeviceUpgrade(FilePairedPeerStore(pairedPeers), pairedPeers.resolveSibling(".${pairedPeers.fileName}.$UPGRADE_NAME"))
    }
}
