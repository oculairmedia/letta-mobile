package com.letta.mobile.cli.commands

import com.letta.mobile.data.canvas.CanvasRelayStore
import com.letta.mobile.data.canvas.HostCanvasDirectory
import com.letta.mobile.data.canvas.HostCanvasMountLookup
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.ConversationViewers
import com.letta.mobile.data.controller.node.iroh.FileIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohNodeIdentity
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountCatalog
import com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountDeviceUpgrade
import com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountDirectory
import com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountRoutes
import com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountSources
import com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountStoreLocation
import com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountToolLookup
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.transport.appserver.AppServerClient
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope

/** The host canvas records the mount routes read: its agent canvas directory and its relay topic bindings. */
internal class HostCanvasRecords(val directory: HostCanvasDirectory, val relay: CanvasRelayStore)

/**
 * Opt-in (`--enable-canvas-mounts`) host wiring of the canvas mounts: the mount records live
 * in `canvas-mounts/` of the product-state directory that already holds `host-canvases.json`, under this host's
 * stable node id. Not for use until the security review of the canvas mount routes passes; without the flag nothing
 * is built, registered or advertised, and the canvas tools keep using each conversation's own canvas.
 */
internal class CanvasMountWiring private constructor(
    private val location: CanvasMountStoreLocation,
    private val catalog: CanvasMountCatalog,
    private val scope: CoroutineScope,
) {
    /** What the host canvas tools read to find the canvas a call naming none acts on. */
    val toolLookup: HostCanvasMountLookup = CanvasMountToolLookup(location, scope)

    /**
     * The six canvas mount routes for [policy] (they register nothing under the anonymous test policy); null without an App
     * Server client, whose conversation and agent records the authority needs: incomplete wiring registers nothing.
     */
    fun routes(policy: IrohAuthPolicy, client: AppServerClient?, viewers: ConversationViewers): AdminRpcRoutes? =
        client?.let {
            CanvasMountRoutes(policy, location, CanvasMountSources(catalog, CanvasMountDirectory.appServer(it), viewers), scope)
        }

    companion object {
        /** Null unless [options] enable mounts; then requires the secret key file that keeps the host id stable. */
        suspend fun of(options: CanvasMountOptions, productState: Path, records: HostCanvasRecords, scope: CoroutineScope): CanvasMountWiring? {
            if (!options.enabled) return null
            val keyFile = requireNotNull(options.secretKeyFile) {
                "--enable-canvas-mounts requires --iroh-secret-key-file for a stable host id"
            }
            val hostId = HostId(IrohNodeIdentity.nodeIdHexFromSecretBytes(FileIrohSecretKeyStore(keyFile).loadOrCreate()))
            val catalog = CanvasMountCatalog(records.directory, records.relay)
            return CanvasMountWiring(CanvasMountStoreLocation(productState, hostId), catalog, scope)
        }
    }
}

/** The operator's choices: [enabled] is `--enable-canvas-mounts` (default off); [secretKeyFile] the node key file. */
internal class CanvasMountOptions(val enabled: Boolean, val secretKeyFile: String?)

/**
 * Owner decision D7: the one-time upgrade that gives devices paired before canvas mounts the two mount capabilities
 * ([CanvasMountDeviceUpgrade]). Runs only when [options] enable mounts and pairing has a store ([pairedPeers]); null
 * (nothing read or written) otherwise.
 */
internal fun upgradePairedDevicesForCanvasMounts(options: CanvasMountOptions, pairedPeers: Path?): CanvasMountDeviceUpgrade.Outcome? {
    if (!options.enabled || pairedPeers == null) return null
    return CanvasMountDeviceUpgrade.forPairingStore(pairedPeers).runOnce()
}
