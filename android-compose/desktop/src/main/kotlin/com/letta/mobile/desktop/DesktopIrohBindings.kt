package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.schedules.CronApi
import com.letta.mobile.data.commands.SlashCommandApi
import com.letta.mobile.data.skills.SkillsApi
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
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
): Map<String, String> {
    if (irohDirectory != null) {
        return runCatching { irohDirectory.listAgents() }.getOrDefault(emptyList())
            .mapNotNull { agent -> agent.name.takeIf { it.isNotBlank() }?.let { agent.id.value to it } }
            .toMap()
    }
    val agentRepository = httpAgentRepository()
    runCatching { agentRepository.refreshAgentsIfStale(maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS) }
    val resolved = mutableMapOf<String, String>()
    agentRepository.agents.value.forEach { agent ->
        agent.name.takeIf { it.isNotBlank() }?.let { resolved[agent.id.value] = it }
    }
    agentIds.filter { it !in resolved }.forEach { id ->
        val name = agentRepository.getCachedAgent(id)?.name
            ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.name
        name?.takeIf { it.isNotBlank() }?.let { resolved[id] = it }
    }
    return resolved
}

/** Agent id -> model handle, mirroring [resolveDesktopAgentNames]. */
internal suspend fun resolveDesktopAgentModels(
    agentIds: Set<String>,
    irohDirectory: IrohAdminRpcAgentDirectory?,
    httpAgentRepository: () -> IAgentRepository,
): Map<String, String> {
    if (irohDirectory != null) {
        return runCatching { irohDirectory.listAgents() }.getOrDefault(emptyList())
            .mapNotNull { agent -> agent.model?.takeIf { it.isNotBlank() }?.let { agent.id.value to it } }
            .toMap()
    }
    val agentRepository = httpAgentRepository()
    val resolved = mutableMapOf<String, String>()
    agentRepository.agents.value.forEach { agent ->
        agent.model?.takeIf { it.isNotBlank() }?.let { resolved[agent.id.value] = it }
    }
    agentIds.filter { it !in resolved }.forEach { id ->
        val model = agentRepository.getCachedAgent(id)?.model
            ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.model
        model?.takeIf { it.isNotBlank() }?.let { resolved[id] = it }
    }
    return resolved
}

/**
 * iroh:// backend: one QUIC channel transport shared by the chat gateway,
 * admin_rpc reads, and (once implemented server-side) registries. Selected
 * purely by URL scheme, same as Android's SessionGraphFactory; null for HTTP
 * backends. Connects on entering composition and disconnects on dispose.
 */
@Composable
internal fun rememberIrohTransport(
    activeConfig: LettaConfig,
    chatScope: CoroutineScope,
): IrohChannelTransport? {
    val irohTransport = remember(activeConfig) {
        activeConfig.takeIf { IrohChannelTransport.isIrohUrl(it.serverUrl) }?.let { config ->
            IrohChannelTransport(
                activeConfigProvider = {
                    IrohConnectConfig(
                        baseShimUrl = config.serverUrl,
                        token = config.accessToken.orEmpty(),
                        deviceId = "letta-desktop",
                        clientVersion = "letta-desktop-iroh",
                    )
                },
            )
        }
    }
    DisposableEffect(irohTransport) {
        val transport = irohTransport
        if (transport != null) {
            chatScope.launch {
                runCatching {
                    transport.connect(
                        baseShimUrl = activeConfig.serverUrl,
                        token = activeConfig.accessToken.orEmpty(),
                        deviceId = "letta-desktop",
                        clientVersion = "letta-desktop-iroh",
                    )
                }
            }
        }
        onDispose {
            if (transport != null) {
                chatScope.launch { runCatching { transport.disconnect() } }
            }
        }
    }
    return irohTransport
}

/** [DesktopChatController] wired for either backend (iroh admin_rpc or HTTP). */
@Composable
internal fun rememberDesktopChatController(
    bootstrapState: DesktopBootstrapState,
    chatScope: CoroutineScope,
    dataBindings: DesktopDataBindings,
    irohTransport: IrohChannelTransport?,
    irohAgentDirectory: IrohAdminRpcAgentDirectory?,
    secureSettingsStore: DesktopFileSecureSettingsStore,
): DesktopChatController = remember(bootstrapState, chatScope, dataBindings.sessionGraphProvider, irohTransport) {
    DesktopChatController(
        bootstrapState = bootstrapState,
        scope = chatScope,
        gatewayFactory = {
            irohTransport?.let { IrohAdminRpcChatGateway(it, deviceLabel = "letta-desktop") }
                ?: createDefaultDesktopChatGateway(bootstrapState.config)
        },
        agentNamesByIdProvider = { agentIds ->
            resolveDesktopAgentNames(agentIds, irohAgentDirectory) {
                dataBindings.sessionGraphProvider.current.agentRepository
            }
        },
        agentModelByIdProvider = { agentIds ->
            resolveDesktopAgentModels(agentIds, irohAgentDirectory) {
                dataBindings.sessionGraphProvider.current.agentRepository
            }
        },
        loadArchivedConversationIds = {
            secureSettingsStore.getString(ARCHIVED_CONVERSATION_IDS_KEY)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
        },
        persistArchivedConversationIds = { ids ->
            secureSettingsStore.putString(ARCHIVED_CONVERSATION_IDS_KEY, ids.joinToString(","))
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
    val slashCommandApi: SlashCommandApi?,
)

@Composable
internal fun rememberDesktopHttpApis(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    irohAgentDirectory: IrohAdminRpcAgentDirectory?,
): DesktopHttpApis =
    remember(activeConfig, irohMode, irohAgentDirectory) {
        val httpConfig = activeConfig.takeIf { it.serverUrl.isNotBlank() && !irohMode }
        DesktopHttpApis(
            blockApi = if (irohMode) {
                irohAgentDirectory?.let { DesktopIrohBlockApi(it) }
            } else {
                httpConfig?.let { DesktopHttpBlockApi(it) }
            },
            cronApi = httpConfig?.let { CronApi(it, createDesktopLettaHttpClient()) },
            skillApi = if (irohMode) {
                irohAgentDirectory?.let { DesktopIrohSkillsApi(it) }
            } else {
                httpConfig?.let { SkillApi(it, createDesktopLettaHttpClient()) }
            },
            slashCommandApi = httpConfig?.let { SlashCommandApi(it, createDesktopLettaHttpClient()) },
        )
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
@Composable
internal fun rememberSubagentRegistry(
    activeConfig: LettaConfig,
    irohMode: Boolean,
    chatScope: CoroutineScope,
): DesktopSubagentRegistry {
    val subagentTransport = remember(activeConfig, irohMode) {
        activeConfig.takeIf { it.serverUrl.isNotBlank() && !it.accessToken.isNullOrBlank() && !irohMode }
            ?.let { DesktopWsChannelTransport(chatScope) }
    }
    val subagentRepository = remember(subagentTransport) {
        subagentTransport?.let { SubagentRepository(it, includeAll = true) }
    }
    DisposableEffect(subagentTransport) {
        val transport = subagentTransport
        if (transport != null) {
            chatScope.launch {
                runCatching {
                    transport.connect(
                        baseShimUrl = activeConfig.serverUrl,
                        token = activeConfig.accessToken.orEmpty(),
                        deviceId = "letta-desktop",
                        clientVersion = "letta-desktop",
                    )
                }
            }
        }
        onDispose { transport?.close() }
    }
    val activeSubagents = produceState(emptyList<SubagentEntry>(), subagentRepository) {
        val repo = subagentRepository
        if (repo == null) {
            value = emptyList()
        } else {
            repo.activeSubagentsFlow().collect { value = it }
        }
    }
    return DesktopSubagentRegistry(subagentRepository, activeSubagents)
}
