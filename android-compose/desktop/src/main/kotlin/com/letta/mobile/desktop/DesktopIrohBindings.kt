package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.schedules.CronApi
import com.letta.mobile.data.commands.SlashCommandApi
import com.letta.mobile.data.commands.SlashCommandsApi
import com.letta.mobile.data.skills.SkillsApi
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import com.letta.mobile.desktop.commands.DesktopIrohSlashCommandApi
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopWsChannelTransport
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopHttpBlockApi
import com.letta.mobile.desktop.memory.DesktopIrohBlockApi
import com.letta.mobile.desktop.skills.DesktopIrohSkillsApi
import com.letta.mobile.data.skills.SkillApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS = 30_000L
internal const val ARCHIVED_CONVERSATION_IDS_KEY = "conversations.archived_ids"

private fun desktopIrohConnectConfig(config: LettaConfig): IrohConnectConfig =
    IrohConnectConfig(
        baseShimUrl = config.serverUrl,
        token = config.accessToken.orEmpty(),
        deviceId = "letta-desktop",
        clientVersion = "letta-desktop-iroh",
    )

private data class ResolveDesktopAgentFieldParams<T : Any>(
    val agentIds: Set<String>,
    val irohDirectory: IrohAdminRpcAgentDirectory?,
    val httpAgentRepository: () -> IAgentRepository,
    val fromAgent: (Agent) -> T?,
    val refreshBeforeResolve: Boolean = false,
)

private suspend fun <T : Any> resolveDesktopAgentField(
    params: ResolveDesktopAgentFieldParams<T>,
): Map<String, T> {
    val irohDirectory = params.irohDirectory
    if (irohDirectory != null) {
        return runCatching { irohDirectory.listAgents() }.getOrDefault(emptyList())
            .mapNotNull { agent -> params.fromAgent(agent)?.let { agent.id.value to it } }
            .toMap()
    }
    val agentRepository = params.httpAgentRepository()
    if (params.refreshBeforeResolve) {
        runCatching { agentRepository.refreshAgentsIfStale(maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS) }
    }
    val resolved = mutableMapOf<String, T>()
    agentRepository.agents.value.forEach { agent ->
        params.fromAgent(agent)?.let { resolved[agent.id.value] = it }
    }
    params.agentIds.filter { it !in resolved }.forEach { id ->
        val value = agentRepository.getCachedAgent(id)?.let(params.fromAgent)
            ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.let(params.fromAgent)
        value?.let { resolved[id] = it }
    }
    return resolved
}

private fun nonBlank(value: String?): String? = value?.takeIf { it.isNotBlank() }

/**
 * Resolves agent id -> display name for the chat shell. Over iroh:// there is
 * no HTTP agent repository, so names come from the admin_rpc agent directory;
 * otherwise from the cached repository, fetching any still-unresolved id
 * directly. [httpAgentRepository] is only evaluated on the HTTP path.
 */
internal suspend fun resolveDesktopAgentNames(
    agentIds: Set<String>,
    irohDirectory: IrohAdminRpcAgentDirectory?,
    httpAgentRepository: () -> IAgentRepository,
): Map<String, String> = resolveDesktopAgentField(
    ResolveDesktopAgentFieldParams(
        agentIds = agentIds,
        irohDirectory = irohDirectory,
        httpAgentRepository = httpAgentRepository,
        fromAgent = { agent -> nonBlank(agent.name) },
        refreshBeforeResolve = true,
    ),
)

/** Agent id -> model handle, mirroring [resolveDesktopAgentNames]. */
internal suspend fun resolveDesktopAgentModels(
    agentIds: Set<String>,
    irohDirectory: IrohAdminRpcAgentDirectory?,
    httpAgentRepository: () -> IAgentRepository,
): Map<String, String> = resolveDesktopAgentField(
    ResolveDesktopAgentFieldParams(
        agentIds = agentIds,
        irohDirectory = irohDirectory,
        httpAgentRepository = httpAgentRepository,
        fromAgent = { agent -> nonBlank(agent.model) },
    ),
)

/**
 * iroh:// backend: one QUIC channel transport shared by the chat gateway,
 * admin_rpc reads, and (once implemented server-side) registries. Selected
 * purely by URL scheme, same as Android's SessionGraphFactory; null for HTTP
 * backends. Connects on entering composition and disconnects on dispose.
 */
private fun createIrohTransport(config: LettaConfig): IrohChannelTransport =
    IrohChannelTransport(activeConfigProvider = { desktopIrohConnectConfig(config) })

private suspend fun connectIrohTransport(transport: IrohChannelTransport, config: LettaConfig) {
    val connectConfig = desktopIrohConnectConfig(config)
    transport.connect(
        baseShimUrl = connectConfig.baseShimUrl,
        token = connectConfig.token,
        deviceId = connectConfig.deviceId,
        clientVersion = connectConfig.clientVersion,
    )
}

@Composable
internal fun rememberIrohTransport(
    activeConfig: LettaConfig,
    chatScope: CoroutineScope,
): IrohChannelTransport? {
    val irohTransport = remember(activeConfig) {
        activeConfig.takeIf { IrohChannelTransport.isIrohUrl(it.serverUrl) }?.let(::createIrohTransport)
    }
    DisposableEffect(irohTransport, activeConfig) {
        val transport = irohTransport
        if (transport != null) {
            chatScope.launch { runCatching { connectIrohTransport(transport, activeConfig) } }
        }
        onDispose {
            transport?.let { t -> chatScope.launch { runCatching { t.disconnect() } } }
        }
    }
    return irohTransport
}

internal data class DesktopChatControllerBindings(
    val bootstrapState: DesktopBootstrapState,
    val chatScope: CoroutineScope,
    val dataBindings: DesktopDataBindings,
    val irohTransport: IrohChannelTransport?,
    val irohAgentDirectory: IrohAdminRpcAgentDirectory?,
    val secureSettingsStore: DesktopFileSecureSettingsStore,
)

/** [DesktopChatController] wired for either backend (iroh admin_rpc or HTTP). */
@Composable
internal fun rememberDesktopChatController(
    bindings: DesktopChatControllerBindings,
): DesktopChatController = remember(
    bindings.bootstrapState,
    bindings.chatScope,
    bindings.dataBindings.sessionGraphProvider,
    bindings.irohTransport,
) {
    DesktopChatController(
        bootstrapState = bindings.bootstrapState,
        scope = bindings.chatScope,
        gatewayFactory = {
            bindings.irohTransport?.let { IrohAdminRpcChatGateway(it, deviceLabel = "letta-desktop") }
                ?: createDefaultDesktopChatGateway(bindings.bootstrapState.config)
        },
        agentNamesByIdProvider = { agentIds ->
            resolveDesktopAgentNames(agentIds, bindings.irohAgentDirectory) {
                bindings.dataBindings.sessionGraphProvider.current.agentRepository
            }
        },
        agentModelByIdProvider = { agentIds ->
            resolveDesktopAgentModels(agentIds, bindings.irohAgentDirectory) {
                bindings.dataBindings.sessionGraphProvider.current.agentRepository
            }
        },
        loadArchivedConversationIds = { loadArchivedConversationIds(bindings.secureSettingsStore) },
        persistArchivedConversationIds = { ids ->
            persistArchivedConversationIds(bindings.secureSettingsStore, ids)
        },
    )
}

/**
 * HTTP-only management APIs — an iroh:// backend has no HTTP base URL, so
 * their panels degrade to empty instead of dialing iroh:// as HTTP.
 */
internal class DesktopHttpApis(
    val blockApi: DesktopBlockApi?,
    val cronApi: CronApi?,
    val skillApi: SkillsApi?,
    val slashCommandApi: SlashCommandsApi?,
)

private data class DesktopApiBackend(
    val irohMode: Boolean,
    val irohAgentDirectory: IrohAdminRpcAgentDirectory?,
    val httpConfig: LettaConfig?,
)

private fun <T> createDesktopBackendApi(
    backend: DesktopApiBackend,
    irohFactory: (IrohAdminRpcAgentDirectory) -> T,
    httpFactory: (LettaConfig) -> T,
): T? = when {
    backend.irohMode -> backend.irohAgentDirectory?.let(irohFactory)
    else -> backend.httpConfig?.let(httpFactory)
}

private fun createDesktopHttpApis(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    irohAgentDirectory: IrohAdminRpcAgentDirectory?,
): DesktopHttpApis {
    val httpConfig = activeConfig.takeIf { it.serverUrl.isNotBlank() && !irohMode }
    val backend = DesktopApiBackend(irohMode, irohAgentDirectory, httpConfig)
    val httpClient = createDesktopLettaHttpClient()
    return DesktopHttpApis(
        blockApi = createDesktopBackendApi(backend, ::DesktopIrohBlockApi, ::DesktopHttpBlockApi),
        cronApi = httpConfig?.let { CronApi(it, httpClient) },
        skillApi = createDesktopBackendApi(
            backend,
            ::DesktopIrohSkillsApi,
        ) { SkillApi(it, createDesktopLettaHttpClient()) },
        slashCommandApi = createDesktopBackendApi(
            backend,
            ::DesktopIrohSlashCommandApi,
        ) { SlashCommandApi(it, createDesktopLettaHttpClient()) },
    )
}

private fun loadArchivedConversationIds(store: DesktopFileSecureSettingsStore): Set<String> =
    store.getString(ARCHIVED_CONVERSATION_IDS_KEY)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()

private fun persistArchivedConversationIds(
    store: DesktopFileSecureSettingsStore,
    ids: Set<String>,
) {
    store.putString(ARCHIVED_CONVERSATION_IDS_KEY, ids.joinToString(","))
}

@Composable
internal fun rememberDesktopHttpApis(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    irohAgentDirectory: IrohAdminRpcAgentDirectory?,
): DesktopHttpApis =
    remember(activeConfig, irohMode, irohAgentDirectory) {
        createDesktopHttpApis(activeConfig, irohMode, irohAgentDirectory)
    }

/** The subagent side-channel plus the live active-subagent list it feeds. */
internal class DesktopSubagentRegistry(
    val repository: SubagentRepository?,
    val activeSubagents: State<List<SubagentEntry>>,
)

/**
 * Active-subagent registry (Background tasks). Desktop streams chat over SSE,
 * but the subagent registry only exists on the shim's mobile WS protocol, so
 * we open a lean WS side-channel and feed the shared SubagentRepository.
 * Skipped in iroh mode: IrohChannelTransport stubs sendSubagentList until
 * the iroh node serves the subagent registry.
 */
private fun createSubagentTransport(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    chatScope: CoroutineScope,
): DesktopWsChannelTransport? =
    activeConfig.takeIf { it.serverUrl.isNotBlank() && !it.accessToken.isNullOrBlank() && !irohMode }
        ?.let { DesktopWsChannelTransport(chatScope) }

private suspend fun connectSubagentTransport(transport: DesktopWsChannelTransport, config: LettaConfig) {
    transport.connect(
        baseShimUrl = config.serverUrl,
        token = config.accessToken.orEmpty(),
        deviceId = "letta-desktop",
        clientVersion = "letta-desktop",
    )
}

@Composable
internal fun rememberSubagentRegistry(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    chatScope: CoroutineScope,
): DesktopSubagentRegistry {
    val subagentTransport = remember(activeConfig, irohMode) {
        createSubagentTransport(activeConfig, irohMode, chatScope)
    }
    val subagentRepository = remember(subagentTransport) {
        subagentTransport?.let { SubagentRepository(it, includeAll = true) }
    }
    DisposableEffect(subagentTransport, activeConfig) {
        subagentTransport?.let { transport ->
            chatScope.launch { runCatching { connectSubagentTransport(transport, activeConfig) } }
        }
        onDispose { subagentTransport?.close() }
    }
    val activeSubagents = produceState(emptyList<SubagentEntry>(), subagentRepository) {
        subagentRepository?.activeSubagentsFlow()?.collect { value = it } ?: run { value = emptyList() }
    }
    return DesktopSubagentRegistry(subagentRepository, activeSubagents)
}
