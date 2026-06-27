package com.letta.mobile.data.controller.node

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerTransportRegistry
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Client-side interface for a node that CONNECTS TO remote nodes.
 *
 * A node can dial other nodes' endpoints and drive their runtimes. This is the
 * "dial out and control remote runtimes" side of agent mobility.
 *
 * RESPONSIBILITIES:
 * - Connect to a remote node via its [NodeIdentity] or [AppServerEndpoint]
 * - Return a [RemoteNodeHandle] for controlling the remote node's runtimes
 * - Delegate actual transport to the existing [AppServerTransportRegistry]
 *
 * This is transport-agnostic: the client uses the .1 bead transport registry
 * to resolve endpoints to concrete transports (WebSocket, Iroh, etc.).
 */
interface NodeClient {
    /**
     * Connects to a remote node by its identity.
     *
     * Selects the first available endpoint from the identity's endpoint set
     * and delegates to [connectTo] with that endpoint.
     *
     * @param identity The remote node's identity
     * @return A handle for controlling the remote node's runtimes
     * @throws IllegalArgumentException if the identity has no endpoints
     */
    suspend fun connectTo(identity: NodeIdentity): RemoteNodeHandle {
        val endpoint = identity.endpoints.firstOrNull()
            ?: throw IllegalArgumentException("NodeIdentity has no endpoints")
        return connectTo(endpoint)
    }

    /**
     * Connects to a remote node by its endpoint.
     *
     * Uses the [AppServerTransportRegistry] to resolve the endpoint to a concrete
     * transport, then wraps the transport in an [AppServerController] and returns
     * a handle for controlling the remote node's runtimes.
     *
     * @param endpoint The remote endpoint to connect to
     * @return A handle for controlling the remote node's runtimes
     * @throws IllegalArgumentException if no transport adapter is available for the endpoint
     */
    suspend fun connectTo(endpoint: AppServerEndpoint): RemoteNodeHandle
}

/**
 * Handle for controlling a remote node's runtimes.
 *
 * This is a thin wrapper around an [AppServerController] for a remote endpoint.
 * It exposes the controller's runtime lifecycle API (startRuntime, runTurn, sync, abort)
 * in a node-centric context.
 *
 * @property controller The underlying controller for the remote node
 */
data class RemoteNodeHandle(
    val controller: AppServerController,
) {
    /**
     * Starts a runtime on the remote node.
     *
     * Delegates to the underlying controller's startRuntime.
     */
    suspend fun startRuntime(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String? = null,
        mode: AppServerPermissionMode? = null,
        recoverApprovals: Boolean = true,
        forceDeviceStatus: Boolean = true,
    ): CanonicalRuntime {
        return controller.startRuntime(
            agentId = agentId,
            conversationId = conversationId,
            cwd = cwd,
            mode = mode,
            recoverApprovals = recoverApprovals,
            forceDeviceStatus = forceDeviceStatus,
        )
    }

    /**
     * Runs a turn on the remote node.
     *
     * Delegates to the underlying controller's runTurn.
     */
    fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> {
        return controller.runTurn(command)
    }

    /**
     * Syncs a runtime on the remote node.
     *
     * Delegates to the underlying controller's sync.
     */
    suspend fun sync(
        runtime: AppServerRuntimeScope,
        recoverApprovals: Boolean = false,
        forceDeviceStatus: Boolean = false,
    ): AppServerInboundFrame.SyncResponse {
        return controller.sync(
            runtime = runtime,
            recoverApprovals = recoverApprovals,
            forceDeviceStatus = forceDeviceStatus,
        )
    }

    /**
     * Aborts a runtime on the remote node.
     *
     * Delegates to the underlying controller's abort.
     */
    suspend fun abort(
        runtime: AppServerRuntimeScope,
        runId: String? = null,
    ): AppServerInboundFrame.AbortMessageResponse {
        return controller.abort(
            runtime = runtime,
            runId = runId,
        )
    }
}

/**
 * Default implementation of [NodeClient].
 *
 * Uses the existing [AppServerTransportRegistry] to resolve endpoints to
 * transports, then wraps the transport in a controller.
 *
 * @param scope Coroutine scope for transport I/O
 * @param controllerFactory Factory for creating controllers from AppServerClient (defaults to DefaultAppServerController)
 */
class DefaultNodeClient(
    private val scope: CoroutineScope,
    private val controllerFactory: (com.letta.mobile.data.transport.appserver.AppServerClient) -> AppServerController =
        { client -> com.letta.mobile.data.controller.DefaultAppServerController(client) },
) : NodeClient {
    override suspend fun connectTo(endpoint: AppServerEndpoint): RemoteNodeHandle {
        // Resolve endpoint to transport via the registry
        val transport = AppServerTransportRegistry.createTransport(
            endpoint = endpoint,
            scope = scope,
        )

        // Create a client from the transport using DefaultAppServerClient
        val client = com.letta.mobile.data.transport.appserver.DefaultAppServerClient(transport)

        // Wrap in a controller
        val controller = controllerFactory(client)

        return RemoteNodeHandle(controller)
    }
}
