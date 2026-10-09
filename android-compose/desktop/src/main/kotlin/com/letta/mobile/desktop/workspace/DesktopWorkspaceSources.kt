package com.letta.mobile.desktop.workspace

import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.workspace.relay.WorkspaceClientRoute
import com.letta.mobile.data.workspace.relay.WorkspaceRelayCall
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * The Iroh connection the desktop is on, or null; the shell publishes it alongside the chat's
 * transport. Read per request by [DesktopWorkspaceSources].
 */
internal object DesktopIrohWorkspaceRelay {
    @Volatile
    var transport: IChannelTransport? = null
}

/**
 * Binds the shared agent-workspace sources (letta-mobile-bzvro.24–.26) to the desktop's App Server:
 * the direct session when there is one, otherwise the Iroh host's workspace relay
 * (letta-mobile-bzvro.37). With neither, every request fails with [NO_DIRECT_SESSION], which the
 * pages show in place of their content.
 */
internal class DesktopWorkspaceSources(
    private val registry: DesktopLocalAppServerClientRegistry = DesktopLocalAppServerClientRegistry.direct,
    private val irohTransport: () -> IChannelTransport? = { DesktopIrohWorkspaceRelay.transport },
) {
    private val route = WorkspaceClientRoute(
        direct = registry::currentOrNull,
        relay = { irohTransport()?.let { transport -> WorkspaceRelayCall.overTransport { transport } } },
    )

    /** The direct session's inbound frames, following it across reconnects. The relay pushes none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: Flow<AppServerReceivedFrame> =
        registry.clients.flatMapLatest { client -> client?.events ?: emptyFlow() }

    /** The direct session, else the Iroh relay; fails with [NO_DIRECT_SESSION] when there is neither. */
    fun client(): AppServerClient = route.client()

    fun requestId(operation: String): String = "desktop-$operation-${UUID.randomUUID()}"

    companion object {
        const val NO_DIRECT_SESSION: String = WorkspaceClientRoute.NO_CONNECTION
    }
}
