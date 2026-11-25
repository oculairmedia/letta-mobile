package com.letta.mobile.data.node

import com.letta.mobile.data.subagents.AppServerProcessIdentity
import com.letta.mobile.data.transport.appserver.AppServerClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

/**
 * Where a node reports what it is doing. The node writes plain lines; the host decides where they
 * go and how they are prefixed (a terminal, a log file, a desktop log sink).
 */
interface NodeLog {
    /** A progress or status line. */
    fun info(line: String)

    /** A failure the operator should see. */
    fun error(line: String)
}

/**
 * The host's local tools socket: a listener that lets an agent's shell call this node's tools.
 * Started once the node has its App Server client, which is null when the node runs without one.
 */
fun interface NodeToolsSocket {
    suspend fun start(scope: CoroutineScope, client: AppServerClient?)

    companion object {
        /** A host with no tools socket. */
        val None: NodeToolsSocket = NodeToolsSocket { _, _ -> }
    }
}

/**
 * Builds the HTTP clients the node dials its App Server with: the platform's engine and WebSocket
 * extensions. The engine must support the App Server's frame limits.
 */
fun interface AppServerHttpClients {
    /** A client for the App Server; a null [requestTimeoutMs] builds one without request timeouts. */
    fun create(requestTimeoutMs: Long?): HttpClient
}

/**
 * The platform-specific parts a node needs but does not own. Each platform supplies its own
 * binding; the composition only sees these interfaces.
 *
 * @property httpClients the HTTP clients the node reaches its App Server with.
 * @property log where the node's status lines go.
 * @property processIdentity proof of which App Server process the node is talking to, used to
 *   reconcile durable subagent chips. Unknown (null) on hosts that cannot prove it.
 * @property toolsSocket the local tools listener, [NodeToolsSocket.None] where there is none.
 */
class NodePorts(
    val httpClients: AppServerHttpClients,
    val log: NodeLog,
    val processIdentity: AppServerProcessIdentity = AppServerProcessIdentity { null },
    val toolsSocket: NodeToolsSocket = NodeToolsSocket.None,
)
