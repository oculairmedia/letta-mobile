package com.letta.mobile.data.node.embedded

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.node.NodeAdminExtras
import com.letta.mobile.data.node.NodeComposition
import com.letta.mobile.data.node.NodeConfig
import com.letta.mobile.data.node.NodeEndpointParts
import com.letta.mobile.data.node.NodePorts
import com.letta.mobile.data.node.carrier.InProcessNodeCarrier
import com.letta.mobile.data.node.carrier.NodeAddress
import com.letta.mobile.data.node.carrier.NodeCarrier
import com.letta.mobile.data.node.carrier.NodeCarrierException
import com.letta.mobile.data.node.carrier.NodeCarrierFailure
import com.letta.mobile.data.node.carrier.NodeLink
import com.letta.mobile.data.node.carrier.NodePeerIdentity
import com.letta.mobile.data.runtime.host.LocalAppServerLease
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What an embedded node is built from. [config], [tools] and [appServer] are read again for every generation, so a
 * changed backend directory or a restarted App Server reaches the next one.
 *
 * @property config the node's plain configuration (its state directory, admin services).
 * @property ports the host's platform ports (process identity, tools socket, log, HTTP clients).
 * @property tools the external tools the node advertises to its runtimes.
 * @property appServer leases the App Server the node supervises; null runs the node without one.
 */
class EmbeddedNodeSpec(
    val config: () -> NodeConfig,
    val ports: NodePorts,
    val tools: () -> ExternalToolRegistry,
    val appServer: suspend () -> LocalAppServerLease?,
)

/**
 * A node that runs inside the host's own process and is reached only from it: the desktop's node. It is a
 * [NodeCarrier] for the one address [ADDRESS]; the shell dials it exactly as it dials a node over Iroh and holds no
 * reference into it, so moving the node into its own process later swaps this carrier for one that serializes.
 *
 * The node starts on the first dial and lives in generations: each generation is one [NodeComposition] over one App
 * Server lease, started in a child of [scope]. [restart] ends the current generation, which loses every link to it, so
 * attached shells redial and the next dial starts a fresh generation (a restarted App Server listens on a new port and
 * token, which a running node cannot follow). Nothing binds an Iroh endpoint here: remote peers reach a node through
 * its Iroh endpoint, which a host binds separately when it offers one.
 */
class EmbeddedNodeHost(
    private val spec: EmbeddedNodeSpec,
    private val scope: CoroutineScope,
) : NodeCarrier, AutoCloseable {
    private val mutex = Mutex()
    @Volatile private var current: Generation? = null

    @Volatile private var closed = false
    private val runningState = MutableStateFlow(false)

    /** Whether a generation is running now. */
    val running: StateFlow<Boolean> = runningState.asStateFlow()

    override suspend fun dial(address: NodeAddress): NodeLink {
        if (address != ADDRESS) throw unreachable("no embedded node at ${address.value}")
        return generation().carrier.dial(address)
    }

    /**
     * Ends the current generation, if any, and waits for it to finish, so that a replacement never opens the stores the
     * old one is still closing. Its links are lost and the next dial starts a new one.
     */
    suspend fun restart() {
        mutex.withLock {
            val ending = current?.job
            stopCurrent()
            ending?.cancelAndJoin()
        }
    }

    /** Ends the current generation and refuses every later dial. Idempotent. */
    override fun close() {
        // Not under the mutex: close may run from a shutdown hook that must not suspend.
        closed = true
        current?.job?.cancel()
        current = null
        runningState.value = false
    }

    private suspend fun generation(): Generation = mutex.withLock {
        current?.takeIf { it.job.isActive }?.let { return it }
        if (closed) throw unreachable("the embedded node has stopped")
        start().also { started ->
            current = started
            if (closed) {
                // close() ran while start() was suspended; it could not cancel a generation that was not yet published.
                stopCurrent()
                throw unreachable("the embedded node has stopped")
            }
            runningState.value = true
            // A generation that ends by itself (its scope failed) reads as stopped until the next dial starts one.
            started.job.invokeOnCompletion { if (current === started) runningState.value = false }
        }
    }

    private fun stopCurrent() {
        current?.job?.cancel()
        current = null
        runningState.value = false
    }

    private suspend fun start(): Generation {
        val job = SupervisorJob(scope.coroutineContext[Job])
        @Suppress("NoDetachedCoroutineLifecycle") // A child of the owner's scope, cancelled when the generation ends.
        val generationScope = CoroutineScope(scope.coroutineContext + job)
        return runCatching {
            val lease = spec.appServer()
            // The lease is the generation's: released when the generation ends, however it ends.
            job.invokeOnCompletion { lease?.close() }
            Generation(job, compose(generationScope, lease))
        }.onFailure { job.cancel() }.getOrThrow()
    }

    private suspend fun compose(generationScope: CoroutineScope, lease: LocalAppServerLease?): InProcessNodeCarrier {
        val config = spec.config()
        val node = NodeComposition(config, spec.ports, generationScope)
        val endpoint = node.endpoint(NodeEndpointParts(authPolicy = IrohAuthPolicy.PairedPeersOnly))
        val tools = spec.tools()
        val connection = lease?.let { node.connect(it, tools) } ?: node.connect(null as AppServerEndpoint?, tools)
        node.installAdminRpc(
            endpoint = endpoint,
            connection = connection,
            extras = NodeAdminExtras(voiceMemoryDir = config.admin.localBackendDir?.let(::File)),
        )
        node.startLocal(endpoint, connection)
        return node.inProcessCarrier(endpoint, NODE_IDENTITY)
    }

    private fun unreachable(detail: String) = NodeCarrierException(NodeCarrierFailure.Unreachable(detail))

    private class Generation(val job: Job, val carrier: InProcessNodeCarrier)

    companion object {
        /** The one address an embedded node answers. */
        val ADDRESS: NodeAddress = NodeComposition.IN_PROCESS_ADDRESS

        /** The identity the shell sees for its own process's node. */
        val NODE_IDENTITY: NodePeerIdentity = NodePeerIdentity("local-node")
    }
}
