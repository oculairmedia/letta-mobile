package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.context.ContextReadingSnapshots
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.ActiveConfigSettingsRepository
import com.letta.mobile.data.repository.CachedProjectWorkRepository
import com.letta.mobile.data.repository.ScheduleRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.appserver.AppServerAgentBlockRepository
import com.letta.mobile.data.repository.appserver.AppServerAgentRepository
import com.letta.mobile.data.repository.appserver.DefaultAppServerLocalRepositoryTransport
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAgentRepository
import com.letta.mobile.data.repository.iroh.IrohScheduleRepository
import com.letta.mobile.data.repository.routedBackendKind
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * letta-mobile-ckmub.17.1: where a host's App Server sessions come from. [clientFor] is the session
 * a backend of that kind is read over (the bundled runtime's, a dialled App Server's), or null where
 * this host cannot reach one (a browser has no bundled runtime).
 */
fun interface HostAppServerClients {
    fun clientFor(kind: BackendKind): (suspend () -> AppServerClient)?
}

/** What one graph generation binds: the backend, how the host reaches it, and what survives a restart. */
class HostSessionBinding(
    val platform: SessionPlatform,
    val config: LettaConfig?,
    val appServerClients: HostAppServerClients,
    val channelTransport: IChannelTransport = NoOpChannelTransport(),
    val irohAgentDirectoryProvider: () -> IrohAdminRpcAgentDirectory? = { null },
    /** letta-mobile-wdm6i: where the context chip's last readings survive a restart. */
    val contextReadingSnapshots: ContextReadingSnapshots? = null,
)

/**
 * letta-mobile-by4vm.86 / ckmub.17.1: the repositories a desktop or browser session binds itself,
 * one copy for both (it was desktop's `DesktopRepositoryAdapters`). An App Server the host dials
 * directly (the bundled runtime or a ws:// host) serves agents and memory; an Iroh host serves the
 * admin set over `admin_rpc`. Everything else is its shared absent-route form.
 */
@Suppress("NoDetachedCoroutineLifecycle")
class HostRepositoryAdapters(binding: HostSessionBinding) {
    private val channelTransport = binding.channelTransport

    // letta-mobile-9v9nu: mode is authoritative — LOCAL never binds remote Iroh
    // even if serverUrl still carries a leftover iroh:// ticket.
    private val backendKind = binding.config.routedBackendKind()
    private val irohMode = backendKind == BackendKind.IROH
    private val servedKind = hostServedKind(binding.config)

    /**
     * letta-mobile-by4vm.13: what this session serves — the host's row of the
     * [com.letta.mobile.data.backend.SessionRouteMatrix]. The bindings below decide independently;
     * the shared matrix test holds the two together.
     */
    val capabilities: SessionCapabilities = SessionCapabilities.forSession(
        platform = binding.platform,
        kind = servedKind,
        hostCapabilities = { channelTransport.hostCapabilities },
    )

    /** True when this session's admin surfaces ride an Iroh host's relays (skills, slash commands). */
    val servesIrohRelays: Boolean get() = irohMode

    // letta-mobile-ckmub.4: every non-Iroh backend is an App Server this host dials directly; its
    // agents and memory are read over that session.
    private val appServerClient = binding.appServerClients
        .clientFor(backendKind)
        .takeIf { !irohMode && servedKind != null }
    private val transportBoundJob = SupervisorJob()
    private val transportBoundScope = CoroutineScope(transportBoundJob + Dispatchers.Default)

    // The Iroh agent roster follows the host's agent_updated pushes (and refreshes on reconnect) on
    // the transport-bound scope, so changes made on another device show without a restart.
    private val irohAgentRepository = if (irohMode) {
        IrohAgentRepository(binding.irohAgentDirectoryProvider, channelTransport, transportBoundScope)
    } else {
        null
    }

    // letta-mobile-by4vm.87/.103: the admin repositories Android builds too. Only an Iroh host serves
    // them; anywhere else they are their absent-route form.
    private val admin = SharedSessionGraph.adminRepositories(
        transport = channelTransport,
        settings = if (irohMode) ActiveConfigSettingsRepository(binding.config) else null,
    )

    // letta-mobile-by4vm.24: built by the shared graph builder, as Android's are. The Background
    // tasks panel has a "Finished" section, so it lists every subagent.
    val transportBound = SharedSessionGraph.transportBound(
        transport = channelTransport,
        scope = transportBoundScope,
        snapshots = binding.contextReadingSnapshots,
        subagents = SubagentListing.ALL,
    )

    /** Fed by the same transport-bound scope, so it retires with the transport generation. */
    val contextTokenReadings: ContextTokenReadings = transportBound.contextTokenReadings

    val closeables: List<AutoCloseable> = listOf(
        AutoCloseable {
            transportBound.subagentRepository.close()
            transportBoundJob.cancel()
        },
    )

    val agentRepository: IAgentRepository = irohAgentRepository
        ?: AppServerAgentRepository(appServerTransport(binding.platform, appServerClient))
    val blockRepository: IAgentBlockRepository? = when {
        irohMode -> admin.blockRepository
        appServerClient != null -> AppServerAgentBlockRepository(appServerTransport(binding.platform, appServerClient))
        else -> null
    }
    val scheduleRepository: IScheduleRepository =
        if (irohMode) IrohScheduleRepository(binding.irohAgentDirectoryProvider) else ScheduleRepository()

    /** The repositories this host binds itself; the transport-bound ones come from [transportBound]. */
    fun hostRepositories(): HostSessionRepositories = HostSessionRepositories(
        agentRepository = agentRepository,
        blockRepository = blockRepository,
        conversationRepository = UnservedConversationRepository(),
        mcpServerRepository = admin.mcpServerRepository,
        modelRepository = admin.modelRepository,
        passageRepository = admin.passageRepository,
        projectRepository = admin.projectRepository,
        projectWorkRepository = CachedProjectWorkRepository(RemoteRoute.Absent(SessionRoute.PROJECT_WORK)),
        runRepository = admin.runRepository,
        providerRepository = admin.providerRepository,
        scheduleRepository = scheduleRepository,
        stepRepository = StepRepository(),
        toolRepository = admin.toolRepository,
        vibesyncEventStreamRepository = UnservedVibesyncEventStreamRepository(),
    )
}

/** The backend kind a host session serves; a remote host with no address is no backend at all. */
fun hostServedKind(config: LettaConfig?): BackendKind? =
    config.routedBackendKind().takeUnless { config == null || it == BackendKind.DIRECT_WS && config.serverUrl.isBlank() }

/**
 * The App Server repository transport over [client]; with no client (no App Server for this
 * session) every read fails as the unserved [SessionRoute.AGENTS] route.
 */
@OptIn(ExperimentalUuidApi::class)
private fun appServerTransport(platform: SessionPlatform, client: (suspend () -> AppServerClient)?) =
    DefaultAppServerLocalRepositoryTransport(
        clientProvider = client ?: { throw AppServerRouteUnavailable(SessionRoute.AGENTS, "IAgentRepository") },
        requestId = { operation -> "${platform.name.lowercase()}-local-$operation-${Uuid.random()}" },
    )
