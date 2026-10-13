package com.letta.mobile.desktop.data

import com.letta.mobile.data.repository.appserver.AppServerAgentBlockRepository
import com.letta.mobile.data.repository.appserver.AppServerAgentRepository
import com.letta.mobile.data.repository.appserver.DefaultAppServerLocalRepositoryTransport
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import java.util.UUID

internal data class DesktopAppServerRepositoryBundle(
    val agentRepository: IAgentRepository,
    val blockRepository: IAgentBlockRepository,
)

/**
 * Agents and memory blocks read over the App Server session [registry] holds: the bundled runtime's
 * (`shared`) or any directly dialled App Server's (`direct`).
 */
internal fun buildDesktopAppServerRepositories(
    registry: DesktopLocalAppServerClientRegistry = DesktopLocalAppServerClientRegistry.shared,
): DesktopAppServerRepositoryBundle {
    val transport = DefaultAppServerLocalRepositoryTransport(
        clientProvider = registry::currentClient,
        requestId = { operation -> "desktop-local-$operation-${UUID.randomUUID()}" },
    )
    return DesktopAppServerRepositoryBundle(
        agentRepository = AppServerAgentRepository(transport),
        blockRepository = AppServerAgentBlockRepository(transport),
    )
}
