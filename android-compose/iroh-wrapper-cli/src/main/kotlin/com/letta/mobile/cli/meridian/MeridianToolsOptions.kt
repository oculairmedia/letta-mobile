package com.letta.mobile.cli.meridian

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.letta.mobile.data.meridian.AgentToolsMode
import com.letta.mobile.data.meridian.AgentToolsModePolicy
import com.letta.mobile.data.meridian.endpoint.MeridianCallerBindingMode
import com.letta.mobile.data.meridian.socket.MeridianToolsConfig
import com.letta.mobile.data.meridian.socket.MeridianToolsTransport

/**
 * True when any agent on this host is offered its tools through the `meridian` CLI
 * (`agent-tools-mode` cli, as the host default or a per-agent override): only then is the local
 * `meridian/tools/1` endpoint served, so native and meta hosts are unchanged.
 */
fun AgentToolsModePolicy.servesCli(): Boolean =
    hostDefault == AgentToolsMode.CLI || AgentToolsMode.CLI in perAgent.values

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
        help = "live-call (default: bind each call by the token issued to one in-flight `meridian` shell call) | env-scoped (soft: trust the shell env scope).",
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
