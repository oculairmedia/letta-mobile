package com.letta.mobile.data.node.host

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.IrohCliRunner
import com.letta.mobile.data.meridian.socket.MeridianToolsConfig
import com.letta.mobile.data.meridian.socket.MeridianToolsEndpoint
import com.letta.mobile.data.meridian.socket.MeridianToolsTransport
import com.letta.mobile.data.node.NodeToolsSocket
import com.letta.mobile.data.subagents.AppServerProcessIdentity
import java.nio.file.Path

/** The operating systems a node has its own bindings for. */
enum class NodeHostOs {
    /** Linux, and every other non-Windows JVM host: `/proc`, systemd and Unix sockets. */
    LINUX,

    /** Windows: process handles, loopback TCP and an in-process agent-to-agent sender. */
    WINDOWS,
    ;

    companion object {
        /** The OS named [osName] (the `os.name` system property's spelling). */
        fun of(osName: String): NodeHostOs = if (osName.startsWith("Windows", ignoreCase = true)) WINDOWS else LINUX

        /** The OS this JVM runs on. */
        fun current(): NodeHostOs = of(System.getProperty("os.name").orEmpty())
    }
}

/** Where the App Server whose process identity a node proves comes from. */
sealed interface AppServerProcessSource {
    /**
     * An external App Server at [serverUrl] whose PID the operator binds in [pidFile]. Only a
     * numeric-loopback URL can be proven; a remote, hostname or missing URL is unknown.
     */
    data class PidFile(val serverUrl: String?, val pidFile: Path?) : AppServerProcessSource

    /** A child the node spawned itself; [pid] reads its PID, null while none is running. */
    class Owned(val pid: () -> Long?) : AppServerProcessSource
}

/**
 * How a host offers `agent_message_send`: the `meridian` [binary] the tool names, and the [runner]
 * that sends (null runs the binary as a subprocess).
 */
class AgentMessagingBinding(
    /** The `meridian` binary path the tool passes to its runner; ignored by an in-process runner. */
    val binary: String,
    /** The runner the tool sends with; null uses the platform's subprocess runner. */
    val runner: IrohCliRunner?,
)

/**
 * The platform-specific parts of a node, chosen once per process by OS ([forOs], [current]). Each
 * OS supplies its own binding of the node's ports; hosts compose through this interface and never
 * name a platform's implementation.
 */
interface NodeHostBindings {
    /** The OS these bindings are for. */
    val os: NodeHostOs

    /** The proof of which App Server process [source] names, stable against PID reuse. */
    fun processIdentity(source: AppServerProcessSource): AppServerProcessIdentity

    /** [requested] adjusted to the listeners this OS serves the local tools endpoint on. */
    fun toolsConfig(requested: MeridianToolsConfig): MeridianToolsConfig

    /**
     * How `agent_message_send` is offered, given the configured `meridian` [binary] (blank when none
     * is configured); null when this host does not offer it.
     */
    fun agentMessaging(binary: String): AgentMessagingBinding?

    /**
     * The node's local tools socket: the `meridian/tools/1` endpoint over [registry] with [config]
     * (adjusted by [toolsConfig]), relaying through the App Server client the node starts it with.
     */
    fun toolsSocket(config: MeridianToolsConfig, registry: ExternalToolRegistry, log: (String) -> Unit): NodeToolsSocket {
        val effective = toolsConfig(config)
        return NodeToolsSocket { scope, client ->
            if (!effective.enabled) return@NodeToolsSocket
            if (client == null) {
                log("[meridian-tools] DISABLED: no App Server to relay")
                return@NodeToolsSocket
            }
            MeridianToolsEndpoint(effective, registry, client.events, log).start(scope)
        }
    }

    companion object {
        /** The bindings for [os]. */
        fun forOs(os: NodeHostOs): NodeHostBindings = when (os) {
            NodeHostOs.LINUX -> LinuxNodeBindings
            NodeHostOs.WINDOWS -> WindowsNodeBindings
        }

        /** The bindings for the OS this JVM runs on. */
        fun current(): NodeHostBindings = forOs(NodeHostOs.current())
    }
}

/**
 * Linux: the external App Server's identity from its systemd MainPID file and `/proc`, the tools
 * endpoint as configured (Unix socket first), and `agent_message_send` through the deployed
 * `meridian` binary when one is configured. A node that owns its App Server leaves the identity
 * unknown, as it always has here.
 */
internal object LinuxNodeBindings : NodeHostBindings {
    override val os = NodeHostOs.LINUX

    override fun processIdentity(source: AppServerProcessSource): AppServerProcessIdentity = when (source) {
        is AppServerProcessSource.PidFile -> LinuxAppServerProcessIdentity(source.serverUrl, source.pidFile)
        is AppServerProcessSource.Owned -> AppServerProcessIdentity { null }
    }

    override fun toolsConfig(requested: MeridianToolsConfig): MeridianToolsConfig = requested

    override fun agentMessaging(binary: String): AgentMessagingBinding? =
        binary.takeIf { it.isNotBlank() }?.let { AgentMessagingBinding(it, runner = null) }
}

/**
 * Windows: identity from the PID plus its creation time, the tools endpoint on loopback TCP only
 * (never a Unix socket) with its bearer token in a file only this user can read, and
 * `agent_message_send` sent inside this process, so no `meridian` binary is needed on PATH.
 */
internal object WindowsNodeBindings : NodeHostBindings {
    override val os = NodeHostOs.WINDOWS

    override fun processIdentity(source: AppServerProcessSource): AppServerProcessIdentity = when (source) {
        is AppServerProcessSource.PidFile -> WindowsAppServerProcessIdentity({ TrustedPidFile.read(source.serverUrl, source.pidFile) })
        is AppServerProcessSource.Owned -> WindowsAppServerProcessIdentity(source.pid)
    }

    override fun toolsConfig(requested: MeridianToolsConfig): MeridianToolsConfig =
        requested.copy(transport = MeridianToolsTransport.TCP)

    override fun agentMessaging(binary: String): AgentMessagingBinding =
        AgentMessagingBinding(binary, InProcessAgentMessageRunner())
}
