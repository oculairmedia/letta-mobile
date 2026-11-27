package com.letta.mobile.data.node.embedded

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.node.JvmAppServerHttpClients
import com.letta.mobile.data.node.NodeAdminConfig
import com.letta.mobile.data.node.NodeConfig
import com.letta.mobile.data.node.NodeEndpointConfig
import com.letta.mobile.data.node.NodeLog
import com.letta.mobile.data.node.NodePorts
import com.letta.mobile.data.node.NodeToolsSocket
import com.letta.mobile.data.node.host.AppServerProcessSource
import com.letta.mobile.data.node.host.NodeHostBindings
import com.letta.mobile.data.runtime.host.LocalAppServerLease
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A desktop's own node: an [EmbeddedNodeHost] in this process that supervises the bundled letta-code runtime and serves
 * the shell everything an Iroh node serves (host-held shell state, pins, the turn journal and feed, approvals, the
 * subagent registry). The shell reaches it only through its carrier ([host]), as it reaches a node elsewhere over Iroh.
 *
 * Its platform ports come from [NodeHostBindings.current]: on Windows the bundled child's identity is its PID plus
 * creation time. Its host-held state lives in [stateDirectory].
 *
 * @property ownedProcess the bundled child, so the node proves which process it talks to.
 * @property backendDirectory letta-code's store, read for each generation since Settings may move it.
 * @property appServer leases the bundled App Server for each generation.
 * @property logFile where the node's lines go, tagged, beside the bundled runtime's own.
 * @property stateDirectory the node's host-held state.
 */
open class BundledRuntimeNode(
    private val ownedProcess: AppServerProcessSource.Owned,
    private val backendDirectory: () -> File,
    private val appServer: suspend () -> LocalAppServerLease?,
    private val logFile: File,
    val stateDirectory: File = File(System.getProperty("user.home"), ".letta-mobile/node"),
) {
    @Suppress("NoDetachedCoroutineLifecycle") // Lives as long as the host process, like the runtime it supervises.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The tools the node advertises to its runtimes. The window's composition sets it before the first dial; until
     * then the node offers the shared defaults.
     */
    @Volatile
    var tools: () -> ExternalToolRegistry = { ExternalToolRegistry.factoryDefault() }

    /** The node, started by the shell's first dial and restarted with the bundled runtime. */
    val host: EmbeddedNodeHost by lazy {
        EmbeddedNodeHost(spec(), scope).also { node -> Runtime.getRuntime().addShutdownHook(Thread(node::close, "node-shutdown")) }
    }

    /** Ends the running generation after the runtime restarted on a new port and token; attached shells redial. */
    suspend fun restart() = host.restart()

    /** The node's composition: its state in [stateDirectory] and the App Server [appServer] leases. */
    fun spec(): EmbeddedNodeSpec = EmbeddedNodeSpec(
        config = { nodeConfig(backendDirectory()) },
        ports = nodePorts(NodeHostBindings.current()),
        tools = { tools() },
        appServer = appServer,
    )

    private fun nodeConfig(backendDirectory: File): NodeConfig = NodeConfig(
        modelExposureFile = File(stateDirectory, "model-exposure.json").path,
        requestTimeoutMs = REQUEST_TIMEOUT_MS,
        // Never bound here: only this process reaches the node until a host offers it over Iroh.
        endpoint = NodeEndpointConfig(bindAddr = LOOPBACK_ANY_PORT),
        admin = NodeAdminConfig(
            subagentRegistryFile = File(stateDirectory, "subagent-registry.json").path,
            localBackendDir = backendDirectory.absolutePath,
        ),
    )

    /** The local tools socket stays off: the host runs agent tools natively, so nothing needs the endpoint. */
    private fun nodePorts(bindings: NodeHostBindings) = NodePorts(
        httpClients = JvmAppServerHttpClients,
        log = TaggedFileLog(logFile),
        processIdentity = bindings.processIdentity(ownedProcess),
        toolsSocket = NodeToolsSocket.None,
    )

    private class TaggedFileLog(private val file: File) : NodeLog {
        override fun info(line: String) = append("[node] $line")

        override fun error(line: String) = append("[node] ERROR $line")

        private fun append(line: String) {
            try {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
            } catch (_: IOException) {
                // A log that cannot be written must never take the node down.
            }
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val LOOPBACK_ANY_PORT = "127.0.0.1:0"
    }
}
