package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.reconnect.summary
import com.letta.mobile.data.transport.appserver.AppServerProbe
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Probes an App Server URL over HTTP without opening a socket (letta-mobile-bzvro.1, F01).
 * Returns null for URLs this probe cannot test (Iroh tickets).
 */
fun interface DesktopAppServerProbe {
    suspend fun probe(serverUrl: String, accessToken: String?): AppServerProbeResult?
}

internal val defaultDesktopAppServerProbe = DesktopAppServerProbe { serverUrl, accessToken ->
    if (IrohChannelTransport.isIrohUrl(serverUrl)) {
        null
    } else {
        withContext(Dispatchers.IO) {
            createDesktopLettaHttpClient().use { http -> AppServerProbe(http).probe(serverUrl, accessToken) }
        }
    }
}

/** The App Server answered, but retrying with these settings cannot work (F02). */
internal class DesktopAppServerUnusableException(val result: AppServerProbeResult) : IllegalStateException(result.summary())

/**
 * Runs before every desktop App Server dial (F02). An `Authentication` or `Incompatible` answer
 * fails the connect with that classification instead of dialling a socket that cannot succeed;
 * a transient `Unavailable` lets the dial proceed and report its own error. So does a 404 on
 * `/app-server-info`: App Servers older than the HTTP discovery route still speak the socket
 * protocol, and the readiness handshake checks them over `app_server_info` instead.
 */
internal suspend fun preflightDesktopAppServer(
    serverUrl: String,
    accessToken: String?,
    probe: DesktopAppServerProbe,
) {
    val result = probe.probe(serverUrl, accessToken) ?: return
    if (result is AppServerProbeResult.Incompatible && result.endpointMissing) return
    if (result.isTerminal) throw DesktopAppServerUnusableException(result)
}

/** The Settings card's "Test connection" state. */
internal sealed interface DesktopConnectionTestState {
    data object Idle : DesktopConnectionTestState

    data object Running : DesktopConnectionTestState

    data class Finished(val result: AppServerProbeResult) : DesktopConnectionTestState

    data class NotSupported(val reason: String) : DesktopConnectionTestState
}

internal suspend fun runDesktopConnectionTest(
    serverUrl: String,
    accessToken: String?,
    probe: DesktopAppServerProbe = defaultDesktopAppServerProbe,
): DesktopConnectionTestState {
    val url = serverUrl.trim()
    if (url.isEmpty()) return DesktopConnectionTestState.NotSupported("Enter a server URL first.")
    val result = probe.probe(url, accessToken?.trim()?.takeIf { it.isNotEmpty() })
        ?: return DesktopConnectionTestState.NotSupported(
            "Test connection checks ws://, wss:// and http(s):// App Servers. Iroh endpoints are checked when you connect.",
        )
    return DesktopConnectionTestState.Finished(result)
}
