package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.LettaConfig

/** The browser row of the shared route matrix; a blank address serves no backend. */
fun webSessionCapabilities(config: LettaConfig): SessionCapabilities =
    SessionCapabilities.forSession(
        platform = SessionPlatform.WEB,
        kind = hostServedKind(config),
    )

private const val NO_BROWSER_ROUTE = "This backend has no route from a browser (a browser cannot run the on-device runtime)."

/** Why [config] cannot list agents from a browser, or null when its session serves agents. */
fun unservedBrowserSessionStatus(config: LettaConfig): BackendConnectionStatus? = when {
    config.serverUrl.isBlank() -> BackendConnectionStatus.Unconfigured
    !webSessionCapabilities(config).supports(SessionRoute.AGENTS) -> BackendConnectionStatus.Failed(NO_BROWSER_ROUTE)
    else -> null
}
