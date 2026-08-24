package com.letta.mobile.desktop.data

import com.letta.mobile.data.repository.appserver.AppServerAgentBlockRepository
import com.letta.mobile.data.repository.appserver.AppServerAgentRepository
import com.letta.mobile.data.repository.appserver.DefaultAppServerLocalRepositoryTransport
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import java.util.UUID

internal data class DesktopLocalRepositoryBundle(
    val agentRepository: IAgentRepository,
    val blockRepository: IAgentBlockRepository,
)

internal fun buildDesktopLocalRepositories(): DesktopLocalRepositoryBundle {
    val transport = DefaultAppServerLocalRepositoryTransport(
        clientProvider = DesktopLocalAppServerClientRegistry.shared::currentClient,
        requestId = { operation -> "desktop-local-$operation-${UUID.randomUUID()}" },
    )
    return DesktopLocalRepositoryBundle(
        agentRepository = AppServerAgentRepository(transport),
        blockRepository = AppServerAgentBlockRepository(transport),
    )
}
