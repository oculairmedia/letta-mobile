package com.letta.mobile.cli.meridian

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.letta.mobile.data.meridian.AgentToolsMode
import com.letta.mobile.data.meridian.AgentToolsModePolicy
import com.letta.mobile.data.meridian.endpoint.MeridianCallerBindingMode

/**
 * True when any agent on this host is offered its tools through the `meridian` CLI
 * (`agent-tools-mode` cli, as the host default or a per-agent override): only then is the local
 * `meridian/tools/1` endpoint served, so native and meta hosts are unchanged.
 */
fun AgentToolsModePolicy.servesCli(): Boolean =
    hostDefault == AgentToolsMode.CLI || AgentToolsMode.CLI in perAgent.values

/** Which listener(s) the endpoint opens: the Unix socket, loopback TCP, or the socket with TCP as fallback. */
enum class MeridianToolsTransport(val wire: String) {
    AUTO("auto"),
    UNIX("unix"),
    TCP("tcp"),
    ;

    companion object {
        fun parse(value: String): MeridianToolsTransport = entries.firstOrNull { it.wire == value.trim().lowercase() }
            ?: throw IllegalArgumentException("--meridian-tools-transport must be one of ${entries.joinToString("|") { it.wire }}")
    }
}

/** The resolved endpoint settings, independent of clikt so tests can build them directly. */
data class MeridianToolsConfig(
    /** Whether `agent-tools-mode` puts any agent on the CLI ([servesCli]); off, nothing is served. */
    val enabled: Boolean = false,
    val transport: MeridianToolsTransport = MeridianToolsTransport.AUTO,
    val socketPath: String = DEFAULT_SOCKET,
    val tcpPort: Int = 0,
    val endpointFile: String = defaultEndpointFile(),
    val binding: MeridianCallerBindingMode = MeridianCallerBindingMode.LIVE_CALL,
    val callsPerMinute: Int = DEFAULT_CALLS_PER_MINUTE,
) {
    companion object {
        /** systemd `RuntimeDirectory=meridian`; not /tmp, which the unit's `PrivateTmp=true` hides. */
        const val DEFAULT_SOCKET = "/run/meridian/tools.sock"
        const val DEFAULT_CALLS_PER_MINUTE = 120

        fun defaultEndpointFile(): String =
            "${System.getProperty("user.home") ?: "."}/.letta/meridian/tools.endpoint"
    }
}

/**
 * The `app-server-serve-iroh` options for the local `meridian/tools/1` endpoint (letta-mobile-jna0o.4).
 * Whether it is served at all follows `--agent-tools-mode` (jna0o.9): see [servesCli].
 */
class MeridianToolsOptions : OptionGroup(name = "Meridian CLI endpoint (jna0o.4)") {
    private val transport by option(
        "--meridian-tools-transport",
        envvar = "MERIDIAN_TOOLS_TRANSPORT",
        help = "auto (Unix socket, loopback TCP + token file if the socket cannot be bound) | unix | tcp.",
    ).convert { MeridianToolsTransport.parse(it) }.default(MeridianToolsTransport.AUTO)

    private val socketPath by option(
        "--meridian-tools-socket",
        envvar = "MERIDIAN_TOOLS_SOCKET",
        help = "Unix socket path (default ${MeridianToolsConfig.DEFAULT_SOCKET}; mode 0660).",
    ).default(MeridianToolsConfig.DEFAULT_SOCKET)

    private val tcpPort by option(
        "--meridian-tools-tcp-port",
        envvar = "MERIDIAN_TOOLS_TCP_PORT",
        help = "Loopback TCP port of the fallback listener (0 = OS-assigned).",
    ).int().default(0)

    private val endpointFile by option(
        "--meridian-tools-endpoint-file",
        envvar = "MERIDIAN_TOOLS_ENDPOINT_FILE",
        help = "Where the TCP fallback writes its port and bearer token (mode 0600). Default ~/.letta/meridian/tools.endpoint.",
    )

    private val binding by option(
        "--meridian-caller-binding",
        envvar = "MERIDIAN_CALLER_BINDING",
        help = "live-call (default: bind each call to an in-flight `meridian` shell call) | env-scoped (soft: trust the shell env scope).",
    ).convert { MeridianCallerBindingMode.parse(it) ?: fail("must be live-call or env-scoped") }
        .default(MeridianCallerBindingMode.LIVE_CALL)

    private val callsPerMinute by option(
        "--meridian-calls-per-minute",
        envvar = "MERIDIAN_CALLS_PER_MINUTE",
        help = "Tool-running meridian calls allowed per conversation per minute.",
    ).int().default(MeridianToolsConfig.DEFAULT_CALLS_PER_MINUTE)

    fun config(cliServed: Boolean): MeridianToolsConfig = MeridianToolsConfig(
        enabled = cliServed,
        transport = transport,
        socketPath = socketPath,
        tcpPort = tcpPort,
        endpointFile = endpointFile ?: MeridianToolsConfig.defaultEndpointFile(),
        binding = binding,
        callsPerMinute = callsPerMinute,
    )
}
