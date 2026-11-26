package com.letta.mobile.data.meridian.socket

import com.letta.mobile.data.meridian.endpoint.MeridianCallerBindingMode

/** Which listener(s) the endpoint opens: the Unix socket, loopback TCP, or the socket with TCP as fallback. */
enum class MeridianToolsTransport(
    /** The value the host's flag and environment variable spell this transport with. */
    val wire: String,
) {
    /** The Unix socket, falling back to loopback TCP when the socket cannot be bound. */
    AUTO("auto"),

    /** The Unix socket only. */
    UNIX("unix"),

    /** Loopback TCP only, its port and bearer token in the owner-only endpoint file. */
    TCP("tcp"),
    ;

    companion object {
        /** The transport spelled [value], case-insensitively; throws for anything else. */
        fun parse(value: String): MeridianToolsTransport = entries.firstOrNull { it.wire == value.trim().lowercase() }
            ?: throw IllegalArgumentException("--meridian-tools-transport must be one of ${entries.joinToString("|") { it.wire }}")
    }
}

/**
 * The resolved settings of the local `meridian/tools/1` endpoint, independent of any flag parser so
 * hosts and tests can build them directly.
 *
 * @property enabled whether any agent on this host is offered its tools through the `meridian` CLI;
 *   off, nothing is served.
 * @property transport which listener(s) to open.
 * @property socketPath the Unix socket path.
 * @property tcpPort the loopback TCP port (0 = OS-assigned).
 * @property endpointFile where the TCP listener writes its port and bearer token, readable by this
 *   user alone.
 * @property binding how a call is bound to the agent shell call that made it.
 * @property callsPerMinute tool-running calls allowed per conversation per minute.
 */
data class MeridianToolsConfig(
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

        /** The default per-conversation rate limit. */
        const val DEFAULT_CALLS_PER_MINUTE = 120

        /** `~/.letta/meridian/tools.endpoint`, where the `meridian` shim looks for the TCP descriptor. */
        fun defaultEndpointFile(): String =
            "${System.getProperty("user.home") ?: "."}/.letta/meridian/tools.endpoint"
    }
}
