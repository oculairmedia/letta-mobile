package com.letta.mobile.data.controller.node

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.ConversationId

/**
 * Server-side interface for a node that HOSTS runtimes.
 *
 * A node exposes its local App Server runtimes to other nodes. This is the
 * "accept inbound connections and expose local state" side of agent mobility.
 *
 * This is a TRANSPORT-AGNOSTIC abstraction. The actual socket listener, process
 * spawn, or Iroh endpoint is hidden behind expect/interface boundaries. This
 * interface works in commonMain.
 *
 * RESPONSIBILITIES:
 * - Advertise this node's identity and capabilities
 * - Return the set of runtimes this node hosts (queryable by remote clients)
 * - Accept inbound controller connections (via expect/interface boundary)
 *
 * The expectation is that a platform implementation provides the actual I/O
 * (WebSocket listener, Iroh QUIC endpoint, etc.) and routes inbound frames
 * to the controller's transport layer.
 */
interface NodeServer {
    /**
     * Returns this node's advertised identity.
     *
     * Includes stable node ID, display name, reachable endpoints, and capabilities.
     */
    suspend fun advertise(): NodeIdentity

    /**
     * Returns the set of runtimes this node currently hosts.
     *
     * Remote clients can query this to discover which agents/conversations are
     * available on this node.
     *
     * @return List of canonical runtimes hosted by this node
     */
    suspend fun hostedRuntimes(): List<CanonicalRuntime>

    /**
     * Boundary for accepting inbound controller connections.
     *
     * This is an INTERFACE BOUNDARY — the actual socket listener is platform-specific
     * (expect/actual). Implementations should:
     * - Listen for inbound connections (WebSocket, Iroh QUIC, etc.)
     * - Route frames to the underlying controller's transport layer
     * - Handle auth/TLS if required
     *
     * This method is a lifecycle hook: call it to start accepting connections.
     * The actual I/O is delegated to platform code.
     *
     * @param port Port to listen on (WebSocket) or address binding (platform-specific)
     * @throws UnsupportedOperationException if no platform listener is available
     */
    suspend fun acceptConnections(port: Int = 4500)
}

/**
 * Default in-memory implementation of [NodeServer].
 *
 * Exposes runtimes from a local [AppServerController] but does NOT actually
 * listen on a socket (the acceptConnections boundary is a no-op in commonMain).
 *
 * This is the reference impl for testing and for nodes that only act as clients.
 *
 * @param identity The identity this node advertises
 * @param controller The controller that manages this node's local runtimes
 */
class DefaultNodeServer(
    private val identity: NodeIdentity,
    private val controller: AppServerController,
) : NodeServer {
    /**
     * Cached list of hosted runtimes.
     * In a real impl, this would be queried from the controller's runtime registry.
     * For now, we return an empty list (the controller doesn't expose a runtime list API yet).
     */
    private val runtimesCache = mutableListOf<CanonicalRuntime>()

    override suspend fun advertise(): NodeIdentity = identity

    override suspend fun hostedRuntimes(): List<CanonicalRuntime> {
        val controllerRuntimes = controller.hostedRuntimes()
        return if (controllerRuntimes.isNotEmpty()) controllerRuntimes else runtimesCache.toList()
    }

    override suspend fun acceptConnections(port: Int) {
        // NO-OP in commonMain. Platform-specific expect/actual implementations
        // would start a WebSocket listener, Iroh endpoint, etc.
        // For now, this is just a lifecycle hook.
    }

    /**
     * Manually add a runtime to the hosted set.
     * This is a testing helper. In a real impl, runtimes would be auto-discovered
     * from the controller's registry.
     */
    fun addHostedRuntime(runtime: CanonicalRuntime) {
        runtimesCache.add(runtime)
    }
}
