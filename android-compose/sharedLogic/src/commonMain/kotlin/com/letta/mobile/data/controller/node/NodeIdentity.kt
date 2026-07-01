package com.letta.mobile.data.controller.node

import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Stable identity for a node in the distributed controller network.
 *
 * A node is both a SERVER (hosting runtimes) and a CLIENT (dialing other nodes).
 * This identity describes WHERE a node can be reached (endpoints) and WHAT it
 * can do (capabilities).
 *
 * USAGE:
 * - Node A advertises its identity (id + endpoints + capabilities)
 * - Node B discovers this identity (via mDNS, registry, static config)
 * - Node B's client connects to node A's endpoint
 * - Node B can now drive node A's runtimes (agent mobility)
 *
 * @property id Stable node ID (UUID, hostname, device ID, etc.)
 * @property displayName Human-readable node name (e.g., "Pixel 8 Pro", "Desktop")
 * @property endpoints Set of endpoints this node can be reached on (WebSocket, Iroh, etc.)
 * @property capabilities Advertised capabilities (extras beyond baseline App Server v2)
 */
@Serializable
data class NodeIdentity(
    val id: String,
    val displayName: String,
    val endpoints: Set<AppServerEndpoint>,
    @Contextual
    val capabilities: RemoteCapabilities = RemoteCapabilities.FACTORY_DEFAULT,
) {
    init {
        require(id.isNotBlank()) { "Node ID must not be blank" }
        require(displayName.isNotBlank()) { "Display name must not be blank" }
        require(endpoints.isNotEmpty()) { "At least one endpoint required" }
    }

    companion object {
        /**
         * Creates a local node identity with a single loopback WebSocket endpoint.
         *
         * This is a convenience for testing and local-only setups.
         *
         * @param id Node ID (defaults to "local")
         * @param displayName Display name (defaults to "Local Node")
         * @param port WebSocket port (defaults to 4500)
         * @param capabilities Advertised capabilities (defaults to factory baseline)
         * @return NodeIdentity with a single ws://127.0.0.1:port endpoint
         */
        fun local(
            id: String = "local",
            displayName: String = "Local Node",
            port: Int = 4500,
            capabilities: RemoteCapabilities = RemoteCapabilities.FACTORY_DEFAULT,
        ): NodeIdentity {
            return NodeIdentity(
                id = id,
                displayName = displayName,
                endpoints = setOf(
                    AppServerEndpoint(
                        scheme = "ws",
                        address = "127.0.0.1:$port",
                        bearerToken = null,
                    )
                ),
                capabilities = capabilities,
            )
        }
    }
}
