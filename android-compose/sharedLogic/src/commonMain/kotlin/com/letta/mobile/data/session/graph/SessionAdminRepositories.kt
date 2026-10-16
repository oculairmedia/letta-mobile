package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.repository.CachedBlockRepository
import com.letta.mobile.data.repository.CachedMcpServerRepository
import com.letta.mobile.data.repository.CachedModelRepository
import com.letta.mobile.data.repository.CachedPassageRepository
import com.letta.mobile.data.repository.CachedProjectRepository
import com.letta.mobile.data.repository.CachedProviderRepository
import com.letta.mobile.data.repository.CachedRunRepository
import com.letta.mobile.data.repository.IrohAdminRpcBlockSource
import com.letta.mobile.data.repository.IrohAdminRpcMcpSource
import com.letta.mobile.data.repository.IrohAdminRpcModelSource
import com.letta.mobile.data.repository.IrohAdminRpcPassageSource
import com.letta.mobile.data.repository.IrohAdminRpcProjectSource
import com.letta.mobile.data.repository.IrohAdminRpcProviderSource
import com.letta.mobile.data.repository.IrohAdminRpcRunSource
import com.letta.mobile.data.repository.IrohAdminRpcToolSource
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.data.repository.api.BlockRemoteSource
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.data.repository.api.McpServerRemoteSource
import com.letta.mobile.data.repository.api.ModelRemoteSource
import com.letta.mobile.data.repository.api.PassageRemoteSource
import com.letta.mobile.data.repository.api.ProjectRemoteSource
import com.letta.mobile.data.repository.api.ProviderRemoteSource
import com.letta.mobile.data.repository.api.RunRemoteSource
import com.letta.mobile.data.repository.api.ToolRemoteSource
import com.letta.mobile.data.transport.api.IChannelTransport

/**
 * letta-mobile-by4vm.87: the admin repositories (MCP servers, models, archival passages, projects,
 * providers, runs; letta-mobile-by4vm.103: tools and memory blocks) a session graph serves over an Iroh host's `admin_rpc`, built the same way for
 * Android and desktop. Each one asks its Iroh source first and falls back to its [AdminRemoteRoutes]
 * entry, which production leaves absent (the call then fails as an unsupported route).
 */
class SessionAdminRepositories internal constructor(
    val mcpServerRepository: IMcpServerRepository,
    val modelRepository: IModelRepository,
    val passageRepository: IPassageRepository,
    val projectRepository: IProjectRepository,
    val providerRepository: IProviderRepository,
    val runRepository: IRunRepository,
    val toolRepository: IToolRepository,
    val blockRepository: IBlockRepository,
)

/** The non-Iroh backend seam for [SessionAdminRepositories]; every route is absent by default. */
class AdminRemoteRoutes(
    val mcpServers: RemoteRoute<McpServerRemoteSource> = RemoteRoute.Absent(SessionRoute.MCP_SERVERS),
    val models: RemoteRoute<ModelRemoteSource> = RemoteRoute.Absent(SessionRoute.MODELS),
    val passages: RemoteRoute<PassageRemoteSource> = RemoteRoute.Absent(SessionRoute.PASSAGES),
    val projects: RemoteRoute<ProjectRemoteSource> = RemoteRoute.Absent(SessionRoute.PROJECTS),
    val providers: RemoteRoute<ProviderRemoteSource> = RemoteRoute.Absent(SessionRoute.PROVIDERS),
    val runs: RemoteRoute<RunRemoteSource> = RemoteRoute.Absent(SessionRoute.RUNS),
    val tools: RemoteRoute<ToolRemoteSource> = RemoteRoute.Absent(SessionRoute.TOOLS),
    val blocks: RemoteRoute<BlockRemoteSource> = RemoteRoute.Absent(SessionRoute.BLOCKS),
)

/**
 * The graph generation's admin repositories over [transport]. [settings] scopes the Iroh sources
 * (they serve only while the active backend is Iroh); without it no Iroh source is bound.
 * [localModelSource] lists the on-device runtime's models when that runtime is the active backend.
 */
fun SharedSessionGraph.adminRepositories(
    transport: IChannelTransport,
    settings: ISettingsRepository?,
    remotes: AdminRemoteRoutes = AdminRemoteRoutes(),
    localModelSource: LocalRuntimeModelSource? = null,
): SessionAdminRepositories {
    fun <T> iroh(source: (IChannelTransport, ISettingsRepository) -> T): T? = settings?.let { source(transport, it) }
    return SessionAdminRepositories(
        mcpServerRepository = CachedMcpServerRepository(remotes.mcpServers, iroh(::IrohAdminRpcMcpSource)),
        modelRepository = CachedModelRepository(
            remote = remotes.models,
            localModelSource = localModelSource,
            settingsRepository = settings,
            irohModelSource = iroh(::IrohAdminRpcModelSource),
        ),
        passageRepository = CachedPassageRepository(remotes.passages, iroh(::IrohAdminRpcPassageSource)),
        projectRepository = CachedProjectRepository(remotes.projects, iroh(::IrohAdminRpcProjectSource)),
        providerRepository = CachedProviderRepository(remotes.providers, iroh(::IrohAdminRpcProviderSource)),
        runRepository = CachedRunRepository(remotes.runs, iroh(::IrohAdminRpcRunSource)),
        toolRepository = ToolRepository(remotes.tools, iroh(::IrohAdminRpcToolSource)),
        blockRepository = CachedBlockRepository(remotes.blocks, iroh { t, s -> IrohAdminRpcBlockSource(t, s) }),
    )
}
