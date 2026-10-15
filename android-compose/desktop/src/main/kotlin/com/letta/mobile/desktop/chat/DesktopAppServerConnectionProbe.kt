package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.reconnect.summary
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.toSettingsStatus
import com.letta.mobile.data.transport.appserver.AppServerProbe
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTTP `GET /app-server-info` for [serverUrl] without opening a socket (letta-mobile-bzvro.1, F01);
 * null for URLs it cannot test (Iroh tickets). Settings' "Test connection" calls it directly; a
 * dial passes it to the shared reconnect owner as its preflight hook (letta-mobile-by4vm.9), which
 * owns what each verdict means for the dial.
 */
internal suspend fun probeDesktopAppServer(serverUrl: String, accessToken: String?): AppServerProbeResult? =
    if (IrohChannelTransport.isIrohUrl(serverUrl)) {
        null
    } else {
        withContext(Dispatchers.IO) {
            createDesktopAppServerHttpClient().use { http -> AppServerProbe(http).probe(serverUrl, accessToken) }
        }
    }

/** The App Server answered, but retrying with these settings cannot work (F02). */
internal class DesktopAppServerUnusableException(val result: AppServerProbeResult) : IllegalStateException(result.summary())

/** What Settings' "Test connection" found. */
internal sealed interface DesktopConnectionTestState {
    data class Finished(val result: AppServerProbeResult) : DesktopConnectionTestState

    data class NotSupported(val reason: String) : DesktopConnectionTestState
}

/** The line the shared Backend section shows under Test connection (letta-mobile-ckmub.3.12). */
internal fun DesktopConnectionTestState.toSettingsStatus(): SettingsStatus = when (this) {
    is DesktopConnectionTestState.NotSupported -> SettingsStatus(reason)
    is DesktopConnectionTestState.Finished -> result.toSettingsStatus()
}

internal suspend fun runDesktopConnectionTest(
    serverUrl: String,
    accessToken: String?,
    probe: suspend (String, String?) -> AppServerProbeResult? = ::probeDesktopAppServer,
): DesktopConnectionTestState {
    val url = serverUrl.trim()
    if (url.isEmpty()) return DesktopConnectionTestState.NotSupported("Enter a server URL first.")
    val result = probe(url, accessToken?.trim()?.takeIf { it.isNotEmpty() })
        ?: return DesktopConnectionTestState.NotSupported(
            "Test connection checks ws://, wss:// and http(s):// App Servers. Iroh endpoints are checked when you connect.",
        )
    return DesktopConnectionTestState.Finished(result)
}
