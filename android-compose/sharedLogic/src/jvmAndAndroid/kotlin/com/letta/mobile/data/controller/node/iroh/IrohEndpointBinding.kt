package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.IrohRelayConfig

/**
 * Network binding and identity-key sources for a host-owned endpoint. Explicit keys take precedence over
 * paths; an injected store takes precedence over both.
 */
class IrohEndpointBinding(
    /** App Server protocol accepted alongside the endpoint's optional side protocols. */
    val alpn: ByteArray = IrohNodeEndpoint.DEFAULT_ALPN,
    /** Relay discovery and routing mode used when binding the endpoint. */
    val relayConfig: IrohRelayConfig = IrohRelayConfig.Default,
    /**
     * UDP bind address. Default binds an OS-assigned random port, which makes
     * the ticket rotate on every restart (direct addrs change). Servers that
     * want a STABLE ticket should pin a port, e.g. "0.0.0.0:4501".
     */
    val bindAddr: String = "0.0.0.0:0",
    /**
     * Backward-compatible explicit key injection. Prefer [secretKeyStore].
     */
    val secretKey: ByteArray? = null,
    /**
     * Backward-compatible file-backed key path. Prefer [secretKeyStore].
     */
    val secretKeyPath: String? = null,
    /**
     * Identity key source. Use [FileIrohSecretKeyStore] for stable server
     * identity; default is derived from [secretKey]/[secretKeyPath] or an
     * ephemeral safe key when neither is configured.
     */
    val secretKeyStore: IrohSecretKeyStore? = null,
)
