package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.node.iroh.FilePairingInviteStore
import java.nio.file.Path

/**
 * letta-mobile-dpv7m.1: the serving wrapper and the host `pair` command must
 * agree on where pending invites live, or a CLI-minted invite is invisible to
 * the server that redeems it. Both resolve it here.
 */
internal object PairingStorePaths {
    fun inviteStore(peerStore: Path, override: String?): Path =
        override?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: FilePairingInviteStore.besidePeerStore(peerStore)
}
