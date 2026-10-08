package com.letta.mobile.desktop.workspace

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Binds the shared agent-workspace sources (letta-mobile-bzvro.24–.26) to the desktop's direct
 * App Server session. Over Iroh there is none, and every request fails with [NO_DIRECT_SESSION],
 * which the pages show in place of their content.
 */
internal class DesktopWorkspaceSources(
    private val registry: DesktopLocalAppServerClientRegistry = DesktopLocalAppServerClientRegistry.direct,
) {
    /** The session's inbound frames, following it across reconnects. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: Flow<AppServerReceivedFrame> =
        registry.clients.flatMapLatest { client -> client?.events ?: emptyFlow() }

    /** The current direct session; fails with [NO_DIRECT_SESSION] when there is none. */
    fun client(): AppServerClient = registry.currentOrNull() ?: throw IllegalStateException(NO_DIRECT_SESSION)

    fun requestId(operation: String): String = "desktop-$operation-${UUID.randomUUID()}"

    companion object {
        const val NO_DIRECT_SESSION: String =
            "This needs a direct App Server connection: the bundled local runtime or an App Server URL. " +
                "It is not available over Iroh yet."
    }
}
