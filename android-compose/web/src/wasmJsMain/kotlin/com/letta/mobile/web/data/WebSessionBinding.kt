package com.letta.mobile.web.data

import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.session.graph.HostAppServerClients
import com.letta.mobile.data.session.graph.HostSessionBinding
import com.letta.mobile.data.transport.api.AppServerClientChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import kotlinx.coroutines.CoroutineScope

/**
 * letta-mobile-ckmub.17.1: the browser's binding of the shared session graph
 * ([com.letta.mobile.data.session.graph.HostSessionGraphFactory], the one desktop builds). A browser
 * holds one App Server session, reached over ws:// or an Iroh host's relay: a ws:// host serves
 * agents and memory over it directly, an Iroh host serves the admin set over its `admin_rpc`.
 */
internal fun webSessionBinding(
    config: LettaConfig,
    client: AppServerClient,
    scope: CoroutineScope,
    hostCapabilities: Set<String> = emptySet(),
): HostSessionBinding {
    val channel = AppServerClientChannel(client, scope, hostCapabilities)
    val directory = IrohAdminRpcAgentDirectory(channel)
    return HostSessionBinding(
        platform = SessionPlatform.WEB,
        config = config,
        appServerClients = HostAppServerClients { kind -> if (kind == BackendKind.DIRECT_WS) ({ client }) else null },
        channelTransport = channel,
        irohAgentDirectoryProvider = { directory },
    )
}

/** No session yet (or no longer): the graph of no backend, every route unserved. */
internal fun disconnectedWebBinding(): HostSessionBinding = HostSessionBinding(
    platform = SessionPlatform.WEB,
    config = null,
    appServerClients = HostAppServerClients { null },
)
