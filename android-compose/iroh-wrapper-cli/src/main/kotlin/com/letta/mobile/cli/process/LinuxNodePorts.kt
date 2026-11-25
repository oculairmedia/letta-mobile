package com.letta.mobile.cli.process

import com.letta.mobile.data.node.JvmAppServerHttpClients
import com.letta.mobile.data.node.NodeLog
import com.letta.mobile.data.node.NodePorts
import com.letta.mobile.data.node.NodeToolsSocket
import java.nio.file.Path

/** The wrapper's node log: status lines to stdout and failures to stderr, each tagged with [prefix]. */
internal class ConsoleNodeLog(private val prefix: String) : NodeLog {
    override fun info(line: String) = println("$prefix $line")

    override fun error(line: String) = System.err.println("$prefix $line")
}

/**
 * The Linux host's node ports: App Server process identity from an operator-bound systemd MainPID
 * file and `/proc`, and the wrapper's local tools socket.
 *
 * @param externalServerUrl the external App Server URL; null when the node owns its App Server or
 *   has none, which leaves the process identity unknown.
 * @param pidFile the trusted MainPID file of that App Server.
 */
internal fun linuxNodePorts(
    externalServerUrl: String?,
    pidFile: Path?,
    toolsSocket: NodeToolsSocket,
): NodePorts = NodePorts(
    httpClients = JvmAppServerHttpClients,
    log = ConsoleNodeLog("[iroh-app-server]"),
    processIdentity = LinuxAppServerProcessIdentity(externalServerUrl, pidFile),
    toolsSocket = toolsSocket,
)
