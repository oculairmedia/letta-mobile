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
 * letta-mobile-ckmub.17.1: desktop's binding of the shared [HostSessionGraphFactory]. The graph
 * itself is common code; desktop supplies only its JVM sessions: the bundled runtime's App Server
 * ([DesktopLocalAppServerClientRegistry.shared]), a directly dialled one
 * ([DesktopLocalAppServerClientRegistry.direct]) and the Iroh channel transport.
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

/** The bundled runtime's session for a local backend, the directly dialled one for a ws:// host. */
internal val DesktopAppServerClients = HostAppServerClients { kind ->
    when (kind) {
        BackendKind.LOCAL_RUNTIME -> DesktopLocalAppServerClientRegistry.shared::currentClient
        BackendKind.DIRECT_WS -> DesktopLocalAppServerClientRegistry.direct::currentClient
        BackendKind.IROH -> null
    }
}
