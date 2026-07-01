package com.letta.mobile.data.controller.node

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransportAdapter
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

/**
 * A local node that is BOTH a server AND a client.
 *
 * This is the core abstraction for agent mobility: every node is simultaneously:
 * - A SERVER: hosting runtimes for its local App Server, accepting inbound connections
 * - A CLIENT: dialing other nodes and driving their runtimes
 *
 * AGENT MOBILITY:
 * - A runtime hosted on node A can be driven by node B's client
 * - Node A's server exposes the runtime to the network
 * - Node B's client connects to node A's endpoint
 * - Node B issues runtime_start/input/sync/abort commands to node A's controller
 *
 * USAGE:
 * ```
 * // Node A: server + client
 * val nodeA = LocalNode.create(
 *     identity = NodeIdentity.local(id = "node-a", port = 4500),
 *     controller = localControllerA,
 *     scope = scope,
 * )
 *
 * // Node A hosts a runtime
 * val runtime = nodeA.server.hostedRuntimes().first()
 *
 * // Node B: server + client
 * val nodeB = LocalNode.create(
 *     identity = NodeIdentity.local(id = "node-b", port = 4501),
 *     controller = localControllerB,
 *     scope = scope,
 * )
 *
 * // Node B connects to node A and drives the runtime
 * val handle = nodeB.client.connectTo(nodeA.server.advertise())
 * val turnEvents = handle.runTurn(TurnCommand(...))
 * ```
 *
 * @property server The server-side interface (hosting runtimes)
 * @property client The client-side interface (connecting to other nodes)
 */
data class LocalNode(
    val server: NodeServer,
    val client: NodeClient,
) {
    /**
     * Returns this node's advertised identity.
     *
     * Convenience delegate to [server.advertise].
     */
    suspend fun advertise(): NodeIdentity = server.advertise()

    /**
     * Returns the runtimes this node hosts.
     *
     * Convenience delegate to [server.hostedRuntimes].
     */
    suspend fun hostedRuntimes(): List<CanonicalRuntime> = server.hostedRuntimes()

    /**
     * Starts accepting inbound connections on the server.
     *
     * Convenience delegate to [server.acceptConnections].
     */
    suspend fun acceptConnections(port: Int = 4500) {
        server.acceptConnections(port)
    }

    /**
     * Connects to a remote node by identity.
     *
     * Convenience delegate to [client.connectTo].
     */
    suspend fun connectTo(identity: NodeIdentity): RemoteNodeHandle {
        return client.connectTo(identity)
    }

    /**
     * Connects to a remote node by endpoint.
     *
     * Convenience delegate to [client.connectTo].
     */
    suspend fun connectTo(endpoint: AppServerEndpoint): RemoteNodeHandle {
        return client.connectTo(endpoint)
    }

    companion object {
        /**
         * Creates a local node with default server and client implementations.
         *
         * @param identity The identity this node advertises
         * @param controller The controller managing this node's local runtimes
         * @param scope Coroutine scope for client transport I/O
         * @return A LocalNode with server and client interfaces
         */
        fun create(
            identity: NodeIdentity,
            controller: AppServerController,
            scope: CoroutineScope,
            httpClient: HttpClient,
        ): LocalNode {
            KtorAppServerWebSocketTransportAdapter.registerDefault(httpClient)
            val server = DefaultNodeServer(identity, controller)
            val client = DefaultNodeClient(scope)
            return LocalNode(server, client)
        }

        /**
         * Creates a local node with custom server and client implementations.
         *
         * This is useful for testing with fake servers/clients.
         *
         * @param server The server implementation
         * @param client The client implementation
         * @return A LocalNode with the provided server and client
         */
        fun create(
            server: NodeServer,
            client: NodeClient,
        ): LocalNode {
            return LocalNode(server, client)
        }
    }
}
