package com.letta.mobile.desktop.data

import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.context.ContextReadingSnapshots
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.session.graph.HostAppServerClients
import com.letta.mobile.data.session.graph.HostSessionBinding
import com.letta.mobile.data.session.graph.HostSessionGraphFactory
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry

/**
 * Desktop's binding of the shared [HostSessionGraphFactory]. The graph itself is common code; desktop
 * supplies only its JVM sessions: the node channel transport (an Iroh node, or the desktop's own node
 * for the bundled runtime) and a directly dialled App Server ([DesktopLocalAppServerClientRegistry.shared],
 * a developer-only setting).
 */
fun desktopSessionGraphFactory(
    configProvider: () -> LettaConfig? = { null },
    channelTransportFactory: () -> IChannelTransport = ::NoOpChannelTransport,
    irohAgentDirectoryProvider: () -> IrohAdminRpcAgentDirectory? = { null },
    contextReadingSnapshots: ContextReadingSnapshots? = null,
): HostSessionGraphFactory = HostSessionGraphFactory {
    HostSessionBinding(
        platform = SessionPlatform.DESKTOP,
        config = configProvider(),
        appServerClients = DesktopAppServerClients,
        channelTransport = channelTransportFactory(),
        irohAgentDirectoryProvider = irohAgentDirectoryProvider,
        contextReadingSnapshots = contextReadingSnapshots,
    )
}

/** The directly dialled session for a ws:// host; every other backend is a node, read over its channel transport. */
internal val DesktopAppServerClients = HostAppServerClients { kind ->
    when (kind) {
        BackendKind.DIRECT_WS -> DesktopLocalAppServerClientRegistry.shared::currentClient
        BackendKind.LOCAL_RUNTIME, BackendKind.IROH -> null
    }
}
