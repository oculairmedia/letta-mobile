package com.letta.mobile.data.node

import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.PairingQrConfig
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.transport.iroh.IrohCanvasRelay
import java.io.File

/**
 * Everything a node is configured with that is plain data. Built by the host from its own
 * configuration source (command-line options, desktop settings).
 *
 * @property modelExposureFile the model exposure decisions file. The node's host-held state (turn
 *   journal, turn identity, pins, shell state, voice and service credentials) lives in its directory.
 * @property requestTimeoutMs the App Server request timeout.
 * @property endpoint where the node's Iroh endpoint binds and which identity it uses.
 * @property admin the admin_rpc router's host services.
 */
data class NodeConfig(
    val modelExposureFile: String,
    val requestTimeoutMs: Long,
    val endpoint: NodeEndpointConfig,
    val admin: NodeAdminConfig = NodeAdminConfig(),
) {
    /** The directory that holds the node's host-held state. */
    val hostStateDirectory: File get() = File(modelExposureFile).absoluteFile.parentFile
}

/**
 * @property bindAddr the UDP socket address the Iroh endpoint binds, e.g. `0.0.0.0:0`.
 * @property secretKeyPath the persisted secret key that keeps the node id stable; null makes one per run.
 */
data class NodeEndpointConfig(
    val bindAddr: String,
    val secretKeyPath: String? = null,
)

/**
 * The admin_rpc router's host services. Null fields leave the matching methods unregistered or
 * failing closed, as each describes.
 *
 * @property vibesyncBaseUrl the VibeSync service `project.*` methods call.
 * @property beadsBaseUrl the `bd serve` URL for `beads.*` methods.
 * @property beadsTokenFile the bearer token file for [beadsBaseUrl].
 * @property subagentRegistryFile the durable subagent chip store; null keeps chips in memory.
 * @property skillsDir the letta-code skills root `skill.list` enumerates.
 * @property localBackendDir the read-only letta-code backend root for store-tier reads.
 * @property runRecordsFile the run/lease record file; null puts it beside the model exposure file.
 */
data class NodeAdminConfig(
    val vibesyncBaseUrl: String? = null,
    val beadsBaseUrl: String? = null,
    val beadsTokenFile: String? = null,
    val subagentRegistryFile: String? = null,
    val skillsDir: String? = null,
    val localBackendDir: String? = null,
    val runRecordsFile: String? = null,
)

/**
 * The parts of the Iroh endpoint the host decides. Every protocol handler is registered here
 * because an endpoint's protocols are fixed when it binds.
 *
 * @property authPolicy who may use the endpoint.
 * @property pairingService the paired-peer and invite stores; null when pairing is off.
 * @property canvasRelay the canvas relay the endpoint serves; null serves none.
 * @property protocolHandlers extra protocols served on the same endpoint.
 */
class NodeEndpointParts(
    val authPolicy: IrohAuthPolicy,
    val pairingService: IrohPairingService? = null,
    val canvasRelay: IrohCanvasRelay? = null,
    val protocolHandlers: List<IrohNodeProtocolHandler> = emptyList(),
)

/**
 * Host features that join the node's admin_rpc router.
 *
 * @property pairingService the same pairing service the endpoint was built with.
 * @property pairingQr the identity used to mint scannable invites; null mints none.
 * @property pluginRoutes the plugin host's methods; null when plugins are off.
 * @property canvasMountRoutes the canvas mount methods; null keeps them unregistered.
 * @property voiceMemoryDir where each agent's `voice` memory block is read; null reads none.
 * @property hostRoutes further routes the host registers on the router before it is served.
 */
class NodeAdminExtras(
    val pairingService: IrohPairingService? = null,
    val pairingQr: PairingQrConfig? = null,
    val pluginRoutes: AdminRpcRoutes? = null,
    val canvasMountRoutes: AdminRpcRoutes? = null,
    val voiceMemoryDir: File? = null,
    val hostRoutes: List<AdminRpcRoutes> = emptyList(),
)
