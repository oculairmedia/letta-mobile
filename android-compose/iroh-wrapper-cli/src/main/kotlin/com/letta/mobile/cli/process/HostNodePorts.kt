package com.letta.mobile.cli.process

import com.letta.mobile.data.node.JvmAppServerHttpClients
import com.letta.mobile.data.node.NodeLog
import com.letta.mobile.data.node.NodePorts
import com.letta.mobile.data.node.NodeToolsSocket
import com.letta.mobile.data.node.host.AppServerProcessSource
import com.letta.mobile.data.node.host.NodeHostBindings

/** The wrapper's node log: status lines to stdout and failures to stderr, each tagged with [prefix]. */
internal class ConsoleNodeLog(private val prefix: String) : NodeLog {
    override fun info(line: String) = println("$prefix $line")

    override fun error(line: String) = System.err.println("$prefix $line")
}

/**
 * The wrapper's node ports on this OS's [bindings]: the App Server process identity from
 * [identitySource] (systemd MainPID file and `/proc` on Linux, PID plus creation time on Windows)
 * and the local tools socket.
 */
internal fun hostNodePorts(
    bindings: NodeHostBindings,
    identitySource: AppServerProcessSource,
    toolsSocket: NodeToolsSocket,
): NodePorts = NodePorts(
    httpClients = JvmAppServerHttpClients,
    log = ConsoleNodeLog("[iroh-app-server]"),
    processIdentity = bindings.processIdentity(identitySource),
    toolsSocket = toolsSocket,
)
