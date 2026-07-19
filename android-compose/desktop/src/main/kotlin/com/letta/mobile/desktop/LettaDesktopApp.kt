package com.letta.mobile.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Build
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.chat.runtime.groupSubagentConversations
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.onboarding.OnboardingTaskKind
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.desktop.channels.DesktopChannelLibraryController
import com.letta.mobile.desktop.channels.DesktopChannelLibraryState
import com.letta.mobile.desktop.channels.DesktopChannelLibrarySurface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.desktop.avatar.DesktopAvatarCompanion
import com.letta.mobile.desktop.avatar.DesktopAvatarLibraryWindow
import com.letta.mobile.desktop.avatar.defaultAvatarCatalogDir
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.chat.ChatDetailPane
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksPanel
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksToggle
import com.letta.mobile.desktop.chat.DesktopCommandPalette
import com.letta.mobile.desktop.chat.DesktopModelPickerSheet
import com.letta.mobile.desktop.data.DesktopWsChannelTransport
import com.letta.mobile.desktop.chat.ComposerCommand
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.agent.DesktopEditAgentSurface
import com.letta.mobile.desktop.agent.agentAvatarStyleKey
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.desktop.memory.DesktopMemoryController
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopMemorySurface
import com.letta.mobile.desktop.memory.DesktopMemorySurfaceState
import com.letta.mobile.data.schedules.CronApi
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryController
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.desktop.schedules.DesktopScheduleSurface
import com.letta.mobile.desktop.tools.DesktopToolLibraryController
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.commands.SlashCommandApi
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillApi
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import com.letta.mobile.desktop.skills.DesktopSkillsSurface
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu
import org.jetbrains.jewel.ui.component.Icon as JewelIcon
import org.jetbrains.jewel.ui.component.SimpleListItem as JewelSimpleListItem
import org.jetbrains.jewel.ui.component.Text as JewelText
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

private const val DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS = 30_000L
private const val ARCHIVED_CONVERSATION_IDS_KEY = "conversations.archived_ids"

/**
 * Resolves agent id -> display name for the chat shell. Over iroh:// there is
 * no HTTP agent repository, so names come from the admin_rpc agent directory;
 * otherwise from the cached repository, fetching any still-unresolved id
 * directly. [httpAgentRepository] is only evaluated on the HTTP path.
 * Extracted from [LettaDesktopApp] to keep that composable flat.
 */
private suspend fun resolveDesktopAgentNames(
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
    // Seed from the cached list (fast path).
    agentRepository.agents.value.forEach { agent ->
        agent.name.takeIf { it.isNotBlank() }?.let { resolved[agent.id.value] = it }
    }
    // For any conversation agent still unresolved, fetch it directly (robust
    // against list pagination / partial caches).
    agentIds.filter { it !in resolved }.forEach { id ->
        val name = agentRepository.getCachedAgent(id)?.name
            ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.name
        name?.takeIf { it.isNotBlank() }?.let { resolved[id] = it }
    }
    return resolved
}

/** Agent id -> model handle, mirroring [resolveDesktopAgentNames]. */
private suspend fun resolveDesktopAgentModels(
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
private fun rememberIrohTransport(
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
private fun rememberDesktopChatController(
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
private class DesktopHttpApis(
    val blockApi: DesktopBlockApi?,
    val cronApi: CronApi?,
    val skillApi: SkillApi?,
    val slashCommandApi: SlashCommandApi?,
)

@Composable
private fun rememberDesktopHttpApis(activeConfig: LettaConfig, irohMode: Boolean): DesktopHttpApis =
    remember(activeConfig, irohMode) {
        val httpConfig = activeConfig.takeIf { it.serverUrl.isNotBlank() && !irohMode }
        DesktopHttpApis(
            blockApi = httpConfig?.let { DesktopBlockApi(it) },
            cronApi = httpConfig?.let { CronApi(it, createDesktopLettaHttpClient()) },
            skillApi = httpConfig?.let { SkillApi(it, createDesktopLettaHttpClient()) },
            slashCommandApi = httpConfig?.let { SlashCommandApi(it, createDesktopLettaHttpClient()) },
        )
    }

/** The subagent side-channel plus the live active-subagent list it feeds. */
private class DesktopSubagentRegistry(
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
private fun rememberSubagentRegistry(
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

/** The per-agent library controllers behind the sidebar destinations. */
private class DesktopLibraryControllers(
    val memory: DesktopMemoryController,
    val schedules: DesktopScheduleLibraryController,
    val channels: DesktopChannelLibraryController,
    val tools: DesktopToolLibraryController,
)

@Composable
private fun rememberDesktopLibraryControllers(
    sessionGraphId: Long,
    sessionGraphProvider: DesktopSessionGraphProvider,
    chatScope: CoroutineScope,
): DesktopLibraryControllers {
    val memory = remember(sessionGraphId, chatScope) {
        DesktopMemoryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    val schedules = remember(sessionGraphId, chatScope) {
        DesktopScheduleLibraryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    val channels = remember(sessionGraphId, chatScope) {
        DesktopChannelLibraryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    val tools = remember(sessionGraphId, chatScope) {
        DesktopToolLibraryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    return DesktopLibraryControllers(memory, schedules, channels, tools)
}

/**
 * Start/stop lifecycles for the chat + library controllers, plus the
 * destination-driven agent selection and cron refresh — the same effects,
 * in the same order, that previously ran inline in [LettaDesktopApp].
 */
@Composable
private fun DesktopControllerLifecycles(
    chatController: DesktopChatController,
    libraries: DesktopLibraryControllers,
    selectedDestination: DesktopDestination,
    selectedConversationAgentId: String?,
    cronPanel: DesktopCronPanelState,
) {
    LaunchedEffect(chatController) {
        chatController.start()
    }
    DisposableEffect(chatController) {
        onDispose { chatController.close() }
    }
    LaunchedEffect(libraries.memory) {
        libraries.memory.start()
    }
    LaunchedEffect(libraries.schedules) {
        libraries.schedules.start()
    }
    LaunchedEffect(libraries.channels) {
        libraries.channels.start()
    }
    LaunchedEffect(libraries.tools) {
        libraries.tools.start()
    }
    LaunchedEffect(selectedDestination, selectedConversationAgentId, libraries.memory) {
        if (selectedDestination == DesktopDestination.Memory) {
            selectedConversationAgentId?.let(libraries.memory::selectAgent)
        }
    }
    LaunchedEffect(selectedDestination, selectedConversationAgentId, libraries.schedules) {
        if (selectedDestination == DesktopDestination.Schedules) {
            selectedConversationAgentId?.let(libraries.schedules::selectAgent)
        }
    }
    LaunchedEffect(selectedDestination, cronPanel) {
        if (selectedDestination == DesktopDestination.Schedules) {
            cronPanel.refresh()
        }
    }
    DisposableEffect(libraries.memory) {
        onDispose { libraries.memory.close() }
    }
    DisposableEffect(libraries.schedules) {
        onDispose { libraries.schedules.close() }
    }
    DisposableEffect(libraries.channels) {
        onDispose { libraries.channels.close() }
    }
    DisposableEffect(libraries.tools) {
        onDispose { libraries.tools.close() }
    }
}

/**
 * Cmd/Ctrl-K opens the command palette (Penpot shows the ⌘K hint). A global
 * AWT key dispatcher fires regardless of which Compose field has focus.
 */
@Composable
private fun CommandPaletteKeyDispatcherEffect(onOpenPalette: () -> Unit) {
    DisposableEffect(Unit) {
        val focusManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = java.awt.KeyEventDispatcher { event ->
            if (event.id == java.awt.event.KeyEvent.KEY_PRESSED &&
                event.keyCode == java.awt.event.KeyEvent.VK_K &&
                (event.isControlDown || event.isMetaDown)
            ) {
                onOpenPalette()
                true
            } else {
                false
            }
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }
}

/** Handle for the avatar companion: renderer control + current presence state. */
private class DesktopAvatarCompanionHandle(
    val companion: DesktopAvatarCompanion,
    val state: DesktopAvatarCompanion.State,
    val toggle: () -> Unit,
) {
    val isActive: Boolean
        get() = state is DesktopAvatarCompanion.State.Running ||
            state is DesktopAvatarCompanion.State.Starting
}

/**
 * Avatar companion (pet mode v1): loopback-hosted three-vrm renderer in
 * the default browser, driven by the agent's presence via AvatarDirector.
 * Owns the avatar library window — library-first: pick from imported avatars
 * (with their license on display) instead of a blind file dialog; imports run
 * the full pipeline from inside the library window.
 */
@Composable
private fun rememberAvatarCompanion(
    chatScope: CoroutineScope,
    secureSettingsStore: DesktopFileSecureSettingsStore,
): DesktopAvatarCompanionHandle {
    val avatarCompanion = remember(chatScope) { DesktopAvatarCompanion(chatScope) }
    val avatarCompanionState by avatarCompanion.state.collectAsState()
    DisposableEffect(avatarCompanion) {
        onDispose { avatarCompanion.stop() }
    }
    var showAvatarLibrary by remember { mutableStateOf(false) }
    var activeAvatarModelId by remember { mutableStateOf<String?>(null) }
    if (showAvatarLibrary) {
        DesktopAvatarLibraryWindow(
            catalogDir = remember { defaultAvatarCatalogDir() },
            activeModelId = activeAvatarModelId,
            onUseAvatar = { model, assetPath ->
                showAvatarLibrary = false
                secureSettingsStore.putString(AVATAR_COMPANION_VRM_PATH_KEY, assetPath.toString())
                activeAvatarModelId = model.id
                avatarCompanion.stop()
                avatarCompanion.start(assetPath)
            },
            onClose = { showAvatarLibrary = false },
        )
    }
    return DesktopAvatarCompanionHandle(
        companion = avatarCompanion,
        state = avatarCompanionState,
        toggle = {
            when (avatarCompanionState) {
                is DesktopAvatarCompanion.State.Starting,
                is DesktopAvatarCompanion.State.Running,
                -> {
                    avatarCompanion.stop()
                    activeAvatarModelId = null
                }
                else -> showAvatarLibrary = true
            }
        },
    )
}

/** Agent presence -> avatar companion behavior. */
@Composable
private fun AvatarPresenceEffects(
    avatar: DesktopAvatarCompanionHandle,
    isStreamingReplySelected: Boolean,
    thinkingConversationId: String?,
    errorMessage: String?,
) {
    LaunchedEffect(isStreamingReplySelected, thinkingConversationId, avatar.state) {
        if (avatar.isActive) {
            avatar.companion.setActivity(
                when {
                    isStreamingReplySelected -> AvatarActivity.SPEAKING
                    thinkingConversationId != null -> AvatarActivity.THINKING
                    else -> AvatarActivity.IDLE
                },
            )
        }
    }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) avatar.companion.flashError()
    }
}

/** A cron creation request from the schedules surface. */
private data class CronDraft(
    val agentId: String,
    val name: String,
    val prompt: String,
    val cron: String,
    val recurring: Boolean,
    val timezone: String,
)

/**
 * Snapshot-backed state + actions for the cron panel. Every mutation reloads
 * the list from the API so the panel reflects the server's view; all actions
 * no-op when the backend has no HTTP cron API (iroh mode).
 */
private class DesktopCronPanelState(
    private val cronApi: CronApi?,
    private val scope: CoroutineScope,
) {
    var crons by mutableStateOf<List<CronTask>>(emptyList())
        private set

    val available: Boolean get() = cronApi != null

    suspend fun refresh() {
        val api = cronApi ?: return
        crons = runCatching { api.listCrons() }.getOrDefault(emptyList())
    }

    fun delete(id: String) {
        val api = cronApi ?: return
        scope.launch {
            runCatching { api.deleteCron(id) }
            refresh()
        }
    }

    fun create(draft: CronDraft) {
        val api = cronApi ?: return
        scope.launch {
            runCatching {
                api.createCron(
                    agentId = draft.agentId,
                    name = draft.name,
                    description = draft.name,
                    prompt = draft.prompt,
                    cron = draft.cron,
                    timezone = draft.timezone,
                    recurring = draft.recurring,
                )
            }
            refresh()
        }
    }
}

/**
 * Snapshot-backed state + actions for the Skills page: the registry list,
 * the focused agent's installed set, and install/remove with reload. All
 * actions no-op when the backend has no HTTP skill API (iroh mode).
 */
private class DesktopSkillsPanelState(
    private val skillApi: SkillApi?,
    private val scope: CoroutineScope,
) {
    var all by mutableStateOf<List<Skill>>(emptyList())
        private set
    var installedNames by mutableStateOf<Set<String>>(emptySet())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val available: Boolean get() = skillApi != null

    /** Load the registry + [agentId]'s installed skills (null agent -> none). */
    suspend fun reload(agentId: String?) {
        val api = skillApi ?: return
        loading = true
        error = null
        runCatching {
            all = api.listSkills()
            installedNames = agentId?.let { api.listAgentSkills(it).map { s -> s.name }.toSet() }
                ?: emptySet()
        }.onFailure { error = it.message ?: "Could not load skills." }
        loading = false
    }

    fun install(agentId: String?, name: String) {
        val api = skillApi ?: return
        val agent = agentId ?: return
        scope.launch {
            runCatching { api.installSkill(agent, name) }
                .onFailure { error = it.message ?: "Could not install skill." }
            reload(agent)
        }
    }

    fun uninstall(agentId: String?, name: String) {
        val api = skillApi ?: return
        val agent = agentId ?: return
        scope.launch {
            runCatching { api.uninstallSkill(agent, name) }
                .onFailure { error = it.message ?: "Could not remove skill." }
            reload(agent)
        }
    }
}

/** The focused agent's server slash commands for the composer palette. */
private suspend fun loadAgentSlashCommands(
    api: SlashCommandApi?,
    agentId: String?,
): List<AgentSlashCommand> = if (api != null && agentId != null) {
    runCatching { api.listAgentSlashCommands(agentId) }.getOrDefault(emptyList())
} else {
    emptyList()
}

/** Model picker options: display label to model handle (or name fallback). */
private fun buildModelOptions(availableModels: List<LlmModel>): List<Pair<String, String>> =
    availableModels.map { model ->
        val label = model.displayNameOverride?.takeIf { it.isNotBlank() } ?: model.name
        val value = model.handle?.takeIf { it.isNotBlank() } ?: model.name
        label to value
    }

/**
 * Distinct agents (by id, since many agents share a display name) that have
 * conversations — the rail orbs — ordered most-recent-first so each stack's
 * "first" member (the click/open fallback) is the one with the most recent
 * conversation. Roster-only agents (no recent or any conversations, e.g.
 * bulk-imported fleets hidden by DEFAULT_CONVERSATION_LIMIT) follow
 * alphabetically.
 */
private fun buildRailAgents(
    conversations: List<DesktopConversationSummary>,
    rosterAgents: List<Agent>,
): List<Pair<String, String>> {
    val fromConversations = conversations
        .sortedByDescending { conversationRecency(it.updatedAtLabel) }
        .filter { !it.agentId.isNullOrBlank() }
        .distinctBy { it.agentId }
        .map { it.agentId!! to it.agentName }
    val seenIds = fromConversations.mapTo(mutableSetOf()) { it.first }
    val fromRoster = rosterAgents
        .filter { it.id.value !in seenIds }
        .map { it.id.value to it.name.ifBlank { it.id.value } }
        .sortedBy { it.second.lowercase() }
    return fromConversations + fromRoster
}

/**
 * The selected stack's conversations under the archive filter, newest first.
 *
 * letta-mobile-5172y.2: ephemeral "Letta Code" subagent conversations are now
 * grouped by AUTHORITATIVE PARENT PROVENANCE (via the shared
 * [groupSubagentConversations] model) instead of by display name. This fixes
 * the defect where unrelated agents that merely SHARE a display name (e.g. two
 * "Letta Code" spawns from different parents) were wrongly merged into one
 * stack by the old `it.agentName == agentName` test.
 *
 * Membership resolution:
 *  - If the SELECTED conversation is a subagent conversation (it appears in one
 *    of the shared model's provenance [SubagentStack]s), the stack's members
 *    are exactly that stack's [SubagentStack.memberConversationIds]. Two
 *    same-name-but-different-parent stacks therefore stay distinct.
 *  - Otherwise the agent is a NORMAL (non-subagent) agent — its conversations
 *    are exactly the shared model's `ungrouped` list, and membership falls back
 *    to the historical display-name equality test. Behaviour is UNCHANGED for
 *    normal agents.
 *
 * The archive filter and newest-first sort are applied AFTER grouping, exactly
 * as before (the shared model does not filter archived conversations — that is
 * the consumer's job).
 */
internal fun filterStackConversations(
    conversations: List<DesktopConversationSummary>,
    activeSubagents: List<SubagentEntry>,
    selectedAgentName: String,
    selectedConversationId: String?,
    archiveFilter: ConversationArchiveFilter,
): List<DesktopConversationSummary> {
    val grouping = groupSubagentConversations(conversations, activeSubagents)
    val selectedStack = selectedConversationId?.let { convId ->
        grouping.stacks.firstOrNull { convId in it.memberConversationIds }
    }
    val members: List<DesktopConversationSummary> = if (selectedStack != null) {
        // Subagent stack: authoritative provenance membership (NOT name).
        val memberIds = selectedStack.memberConversationIds.toSet()
        conversations.filter { it.id in memberIds }
    } else {
        // Normal agent: unchanged display-name membership over ungrouped convs.
        grouping.ungrouped.filter { it.agentName == selectedAgentName }
    }
    return members.applyArchiveFilterNewestFirst(archiveFilter)
}

/** Apply the active/archived/all filter and sort newest-first. */
private fun List<DesktopConversationSummary>.applyArchiveFilterNewestFirst(
    archiveFilter: ConversationArchiveFilter,
): List<DesktopConversationSummary> = this
    .filter { c ->
        when (archiveFilter) {
            ConversationArchiveFilter.Active -> !c.archived
            ConversationArchiveFilter.Archived -> c.archived
            ConversationArchiveFilter.All -> true
        }
    }
    .sortedByDescending { conversationRecency(it.updatedAtLabel) }

/**
 * @mention candidates: other agents + the focused agent's memory blocks.
 * (Files need a client-side index — tracked as a follow-up.)
 */
private fun buildMentionables(
    railAgents: List<Pair<String, String>>,
    memoryState: DesktopMemorySurfaceState,
): List<Mentionable> = buildList {
    railAgents.forEach { (id, name) ->
        add(Mentionable(id = id, label = name, sublabel = "agent", kind = MentionKind.Agent, insertText = name))
    }
    memoryState.memory.sections
        .flatMap { it.items }
        .filterIsInstance<MemoryParityItem.MemoryBlock>()
        .forEach { block ->
            add(
                Mentionable(
                    id = block.id,
                    label = block.title,
                    sublabel = "core block",
                    kind = MentionKind.Memory,
                    insertText = block.title,
                ),
            )
        }
}

/** Cmd/Ctrl-K command palette over conversations, agents, and destinations. */
private fun buildPaletteItems(
    conversations: List<DesktopConversationSummary>,
    railAgents: List<Pair<String, String>>,
    workPlayMode: WorkPlayMode,
): List<PaletteItem> = buildList {
    conversations.forEach { conversation ->
        val orbIndex = railAgents.indexOfFirst { it.first == conversation.agentId }.coerceAtLeast(0)
        add(
            PaletteItem(
                id = conversation.id,
                label = conversation.title,
                sublabel = conversation.agentName,
                kind = PaletteItemKind.Conversation,
                orbIndex = orbIndex,
            ),
        )
    }
    railAgents.forEachIndexed { index, (id, name) ->
        add(PaletteItem(id = id, label = name, sublabel = "agent", kind = PaletteItemKind.Agent, orbIndex = index))
    }
    WorkPlayLens.navDestinations(workPlayMode).forEach { lensDestination ->
        val target = lensNavTarget(workPlayMode, lensDestination)
        add(
            PaletteItem(
                id = target.first.name,
                label = WorkPlayLens.destinationLabel(workPlayMode, lensDestination),
                sublabel = null,
                kind = PaletteItemKind.Destination,
            ),
        )
    }
}

/**
 * Composer "/" palette: local navigation commands plus the focused agent's
 * server slash commands (goal mode + installed skills). Selecting a server
 * command fills the composer so the user can add args and send; the server
 * interprets the slash prefix.
 */
private fun buildComposerCommands(
    chatController: DesktopChatController,
    agentSlashCommands: List<AgentSlashCommand>,
    onCreateAgent: () -> Unit,
    onEditAgent: () -> Unit,
    onNavigate: (DesktopDestination) -> Unit,
): List<ComposerCommand> = buildList {
    add(ComposerCommand("new", "Start a new chat") { chatController.createConversation() })
    add(ComposerCommand("agent", "Create a new agent") { onCreateAgent() })
    add(ComposerCommand("edit", "Edit this agent") { onEditAgent() })
    add(ComposerCommand("memory", "Open memory") { onNavigate(DesktopDestination.Memory) })
    add(ComposerCommand("schedules", "Open schedules") { onNavigate(DesktopDestination.Schedules) })
    add(ComposerCommand("skills", "Open skills & tools") { onNavigate(DesktopDestination.Agents) })
    add(ComposerCommand("channels", "Open channels") { onNavigate(DesktopDestination.Channels) })
    add(ComposerCommand("settings", "Open settings") { onNavigate(DesktopDestination.Settings) })
    agentSlashCommands.forEach { cmd ->
        add(
            ComposerCommand(
                label = cmd.command,
                description = cmd.description.ifBlank {
                    cmd.skillName?.let { "Skill: $it" } ?: "Slash command"
                },
                fillsComposer = true,
            ) { chatController.updateComposerText("/${cmd.command} ") },
        )
    }
}

/** Model/embedding defaults for a new agent, copied from the focused agent. */
private fun resolveNewAgentDefaults(
    agentRepository: IAgentRepository,
    templateAgentId: String?,
    modelValue: String?,
): Pair<String?, String?> {
    val template = templateAgentId?.let { agentRepository.getCachedAgent(it) }
    return (modelValue ?: template?.model) to template?.embedding
}

@Composable
fun LettaDesktopApp(
    onActiveTitleChange: (String) -> Unit = {},
) {
    var selectedDestination by rememberSaveable { mutableStateOf(DesktopDestination.Conversations) }
    var showNewAgentDialog by remember { mutableStateOf(false) }
    // Avatar styles chosen via the editor this session, applied immediately to the
    // orbs regardless of whether the backend round-trips agent metadata.
    var avatarOverrides by remember { mutableStateOf(emptyMap<String, Int>()) }
    var editAgentId by remember { mutableStateOf<String?>(null) }
    val secureSettingsStore = remember { DesktopFileSecureSettingsStore() }
    val configStore = remember(secureSettingsStore) { DesktopLettaConfigStore(secureSettingsStore) }
    var activeConfig by remember { mutableStateOf(configStore.load()) }
    var currentIrohAgentDirectory by remember { mutableStateOf<IrohAdminRpcAgentDirectory?>(null) }
    val dataBindings = remember(configStore) {
        createDefaultDesktopDataBindings(
            secureSettingsStore = secureSettingsStore,
            configProvider = { activeConfig },
            irohAgentDirectoryProvider = { currentIrohAgentDirectory },
        )
    }
    var bootstrapState by remember(dataBindings) {
        mutableStateOf(defaultDesktopBootstrapState(dataBindings, activeConfig))
    }
    fun applyConfig(nextConfig: LettaConfig) {
        configStore.save(nextConfig)
        activeConfig = configStore.load()
        dataBindings.sessionGraphProvider.rebuild()
        bootstrapState = defaultDesktopBootstrapState(dataBindings, activeConfig)
    }
    val chatScope = rememberCoroutineScope()
    val irohTransport = rememberIrohTransport(activeConfig, chatScope)
    val irohMode = irohTransport != null
    val irohAgentDirectory = remember(irohTransport) {
        irohTransport?.let { IrohAdminRpcAgentDirectory(it) }
    }
    currentIrohAgentDirectory = irohAgentDirectory
    val chatController = rememberDesktopChatController(
        bootstrapState = bootstrapState,
        chatScope = chatScope,
        dataBindings = dataBindings,
        irohTransport = irohTransport,
        irohAgentDirectory = irohAgentDirectory,
        secureSettingsStore = secureSettingsStore,
    )
    val chatState by chatController.state.collectAsState()
    val availableModels by chatController.availableModels.collectAsState()
    val deletingConversationIds by chatController.deletingConversationIds.collectAsState()
    val modelOptions = remember(availableModels) { buildModelOptions(availableModels) }
    val httpApis = rememberDesktopHttpApis(activeConfig, irohMode)
    val blockApi = httpApis.blockApi
    val cronPanel = remember(httpApis.cronApi) { DesktopCronPanelState(httpApis.cronApi, chatScope) }
    val skillsPanel = remember(httpApis.skillApi) { DesktopSkillsPanelState(httpApis.skillApi, chatScope) }
    var agentSlashCommands by remember(httpApis.slashCommandApi) { mutableStateOf<List<AgentSlashCommand>>(emptyList()) }
    val subagents = rememberSubagentRegistry(activeConfig, irohMode, chatScope)
    val subagentRepository = subagents.repository
    val activeSubagents by subagents.activeSubagents
    var showBackgroundTasks by remember { mutableStateOf(false) }
    // Work | Play presentation lens over the same agents/memory/conversations.
    var workPlayMode by remember { mutableStateOf(WorkPlayMode.Work) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }
    val libraries = rememberDesktopLibraryControllers(
        sessionGraphId = bootstrapState.sessionGraphId,
        sessionGraphProvider = dataBindings.sessionGraphProvider,
        chatScope = chatScope,
    )
    val memoryState by libraries.memory.state.collectAsState()
    val scheduleLibraryState by libraries.schedules.state.collectAsState()
    val channelLibraryState by libraries.channels.state.collectAsState()
    val toolLibraryState by libraries.tools.state.collectAsState()
    CommandPaletteKeyDispatcherEffect(onOpenPalette = { showCommandPalette = true })
    val imageAttachmentLoader = remember { DesktopImageAttachmentLoader() }
    val pickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Single,
        dialogSettings = FileKitDialogSettings(title = "Attach image"),
    ) { file ->
        if (file != null) {
            chatScope.launch {
                runCatching {
                    val path = file.file.toPath()
                    imageAttachmentLoader.load(path)
                }.onSuccess(chatController::attachImage)
                    .onFailure {
                        chatController.showComposerError(
                            it.message ?: it::class.simpleName ?: "Could not attach image",
                        )
                    }
            }
        }
    }

    val avatar = rememberAvatarCompanion(chatScope, secureSettingsStore)

    DesktopControllerLifecycles(
        chatController = chatController,
        libraries = libraries,
        selectedDestination = selectedDestination,
        selectedConversationAgentId = chatState.selectedConversation?.agentId,
        cronPanel = cronPanel,
    )

    val activeTitle = when (selectedDestination) {
        DesktopDestination.Conversations ->
            chatState.selectedConversation?.title ?: "Letta Desktop"
        else -> selectedDestination.label
    }
    LaunchedEffect(activeTitle) { onActiveTitleChange(activeTitle) }

    // Same-named agents are stacked in the rail, and the sidebar lists the
    // whole stack's conversations together (see [buildRailAgents]).
    val sessionGraph by dataBindings.sessionGraphProvider.currentGraph.collectAsState()
    val rosterAgents by sessionGraph.agentRepository.agents.collectAsState()
    LaunchedEffect(sessionGraph, chatState.connectionState) {
        runCatching {
            sessionGraph.agentRepository.refreshAgentsIfStale(
                maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS,
            )
        }
    }
    val railAgents = remember(chatState.conversations, rosterAgents) {
        buildRailAgents(chatState.conversations, rosterAgents)
    }

    // Single entry point for "open this agent" from any surface (rail, command
    // palette): select its most-recent loaded conversation, or — for a
    // roster-only agent with none loaded (e.g. bulk-imported) — create its
    // first chat. createConversationForAgent serializes rapid opens.
    fun openAgent(agentId: String) {
        editAgentId = null
        val existing = chatState.conversations
            .filter { it.agentId == agentId }
            .maxByOrNull { conversationRecency(it.updatedAtLabel) }
        if (existing != null) {
            chatController.selectConversation(existing.id)
        } else {
            chatController.createConversationForAgent(agentId)
        }
        selectedDestination = DesktopDestination.Conversations
    }
    val selectedAgentId = chatState.selectedConversation?.agentId
        ?: railAgents.firstOrNull()?.first
    // Per-agent avatar-style override chosen in the editor (stored in agent
    // metadata). Re-derived whenever the roster changes — which includes the
    // post-save reload — so a freshly-saved icon is reflected on the orbs.
    // Agents without an override fall back to their position-derived colour.
    val cachedAvatarStyles = remember(railAgents) {
        railAgents.mapNotNull { (id, _) ->
            secureSettingsStore.getString(agentAvatarStyleKey(id))?.toIntOrNull()?.let { id to it }
        }.toMap()
    }
    // Session overrides win over the cached/backend value so a just-saved icon
    // shows instantly.
    val avatarStyleByAgentId = cachedAvatarStyles + avatarOverrides
    val selectedAgentOrbIndex = avatarStyleByAgentId[selectedAgentId]
        ?: railAgents.indexOfFirst { it.first == selectedAgentId }.coerceAtLeast(0)
    val selectedAgentName = railAgents.firstOrNull { it.first == selectedAgentId }?.second
        ?: chatState.selectedConversation?.agentName ?: "Letta"
    // List every conversation across the selected stack, newest first. For a
    // "Letta Code" subagent stack this is its same-PROVENANCE spawns (grouped by
    // authoritative parent identity via the shared model, so unrelated same-name
    // agents are NOT merged); for a normal agent it is its display-name convs,
    // unchanged. See [filterStackConversations].
    val archiveFilter by chatController.archiveFilter.collectAsState()
    val selectedConversationId = chatState.selectedConversationId
    val agentConversations = remember(
        chatState.conversations,
        activeSubagents,
        selectedAgentName,
        selectedConversationId,
        archiveFilter,
    ) {
        filterStackConversations(
            conversations = chatState.conversations,
            activeSubagents = activeSubagents,
            selectedAgentName = selectedAgentName,
            selectedConversationId = selectedConversationId,
            archiveFilter = archiveFilter,
        )
    }
    val mentionables = remember(railAgents, memoryState) {
        buildMentionables(railAgents, memoryState)
    }
    val paletteItems = remember(chatState.conversations, railAgents, workPlayMode) {
        buildPaletteItems(chatState.conversations, railAgents, workPlayMode)
    }
    // A conversation is "thinking" from the moment a prompt is sent until the
    // agent's reply starts landing (tracked by the controller — `isSending`
    // alone clears too early, while the reply streams over a separate channel).
    val thinkingConversationId by chatController.thinkingConversationId.collectAsState()
    val thinkingAgentId = thinkingConversationId?.let { tid ->
        chatState.conversations.firstOrNull { it.id == tid }?.agentId
    }
    val isThinkingSelected = thinkingConversationId != null &&
        thinkingConversationId == chatState.selectedConversationId
    // Reply is actively streaming for the selected conversation — outlives
    // "thinking" (which clears at the first token), so it gates the streamed-
    // text smoother in the message list. Derived by the shared
    // ChatStreamingPresencePolicy (the same rules Android uses) rather than a
    // bespoke desktop check, so the "is the agent working" semantics stay in one
    // place across platforms.
    val replyPresence by chatController.replyPresence.collectAsState()
    val isStreamingReplySelected = replyPresence.isStreaming

    AvatarPresenceEffects(
        avatar = avatar,
        isStreamingReplySelected = isStreamingReplySelected,
        thinkingConversationId = thinkingConversationId,
        errorMessage = chatState.errorMessage,
    )

    // Load the skills registry + the focused agent's installed skills when the
    // Skills page is open (or the focused agent changes).
    LaunchedEffect(selectedDestination, skillsPanel, selectedAgentId) {
        if (selectedDestination == DesktopDestination.Agents) {
            skillsPanel.reload(selectedAgentId)
        }
    }
    // Load the focused agent's server slash commands for the composer palette.
    LaunchedEffect(httpApis.slashCommandApi, selectedAgentId) {
        agentSlashCommands = loadAgentSlashCommands(httpApis.slashCommandApi, selectedAgentId)
    }

    DesktopMaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
          Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                // Far-left workspace/agent rail.
                DesktopAgentRail(
                    agents = railAgents,
                    avatarStyleByAgentId = avatarStyleByAgentId,
                    selectedAgentId = selectedAgentId,
                    thinkingAgentId = thinkingAgentId,
                    onAgentSelected = { agentId -> openAgent(agentId) },
                    onNewSession = { showNewAgentDialog = true },
                    onSearch = { showCommandPalette = true },
                    onAvatarCompanion = avatar.toggle,
                    avatarCompanionActive = avatar.isActive,
                )
                RailDivider()
                // Agent sidebar: agent header + nav + conversations.
                DesktopAgentSidebar(
                    agentName = selectedAgentName,
                    agentOrbIndex = selectedAgentOrbIndex,
                    conversations = agentConversations,
                    selectedConversationId = chatState.selectedConversationId,
                    thinkingConversationId = thinkingConversationId,
                    deletingConversationIds = deletingConversationIds,
                    archiveFilter = archiveFilter,
                    onArchiveFilterChange = chatController::setArchiveFilter,
                    onArchiveConversation = chatController::setConversationArchived,
                    selectedDestination = selectedDestination,
                    mode = workPlayMode,
                    onModeChange = { workPlayMode = it },
                    onDestinationSelected = { editAgentId = null; selectedDestination = it },
                    onConversationSelected = {
                        editAgentId = null
                        chatController.selectConversation(it)
                        selectedDestination = DesktopDestination.Conversations
                    },
                    onDeleteConversation = chatController::deleteConversation,
                    onNewChat = {
                        editAgentId = null
                        selectedDestination = DesktopDestination.Conversations
                        // Target the focused agent explicitly — for a roster-only
                        // agent, createConversation()'s conversation-derived agent
                        // id would miss it.
                        selectedAgentId
                            ?.let(chatController::createConversationForAgent)
                            ?: chatController.createConversation()
                    },
                    onEditAgent = { editAgentId = selectedAgentId },
                )
                RailDivider()
                // Main content pane.
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val editing = editAgentId
                    if (editing != null) {
                        DesktopEditAgentSurface(
                            agentId = editing,
                            modelOptions = modelOptions,
                            agentRepository = dataBindings.sessionGraphProvider.current.agentRepository,
                            blockApi = blockApi,
                            settings = secureSettingsStore,
                            scope = chatScope,
                            onClose = { editAgentId = null },
                            onSaved = { style, nameChanged ->
                                avatarOverrides = avatarOverrides + (editing to style)
                                editAgentId = null
                                // Only a name change is visible in the rail/sidebar,
                                // so skip the heavy reconnect otherwise.
                                if (nameChanged) chatController.retryConnection()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (selectedDestination == DesktopDestination.Conversations) {
                        val composerCommands = buildComposerCommands(
                            chatController = chatController,
                            agentSlashCommands = agentSlashCommands,
                            onCreateAgent = { showNewAgentDialog = true },
                            onEditAgent = { editAgentId = selectedAgentId },
                            onNavigate = { selectedDestination = it },
                        )
                        ChatDetailPane(
                            state = chatState,
                            isThinking = isThinkingSelected,
                            isStreamingReply = isStreamingReplySelected,
                            composerPlaceholder = WorkPlayLens.composerPlaceholder(workPlayMode, selectedAgentName),
                            onOpenModelPicker = { showModelPicker = true },
                            onOnboardingTask = { kind ->
                                when (kind) {
                                    OnboardingTaskKind.SetPersona -> editAgentId = selectedAgentId
                                    OnboardingTaskKind.ConnectChannel ->
                                        selectedDestination = DesktopDestination.Channels
                                    OnboardingTaskKind.AddSkills ->
                                        selectedDestination = DesktopDestination.Agents
                                }
                            },
                            modelOptions = modelOptions,
                            onComposerTextChanged = chatController::updateComposerText,
                            onSend = chatController::send,
                            onAttachImage = { pickerLauncher.launch() },
                            onRemoveImageAttachment = chatController::removeImageAttachment,
                            onRetryConnection = chatController::retryConnection,
                            onModelSelected = chatController::setConversationModel,
                            commands = composerCommands,
                            mentionables = mentionables,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        DestinationContent(
                            destination = selectedDestination,
                            state = bootstrapState,
                            chatState = chatState,
                            memoryState = memoryState,
                            scheduleLibraryState = scheduleLibraryState,
                            channelLibraryState = channelLibraryState,
                            toolLibraryState = toolLibraryState,
                            onChatConversationSelected = chatController::selectConversation,
                            onChatConversationDeleted = chatController::deleteConversation,
                            onChatComposerTextChanged = chatController::updateComposerText,
                            onChatSend = chatController::send,
                            onChatAttachImage = { pickerLauncher.launch() },
                            onChatRemoveImageAttachment = chatController::removeImageAttachment,
                            onChatRetryConnection = chatController::retryConnection,
                            onMemoryRefresh = libraries.memory::reload,
                            onMemoryAgentSelected = libraries.memory::selectAgent,
                            onSchedulesRefresh = libraries.schedules::reload,
                            onScheduleAgentSelected = libraries.schedules::selectAgent,
                            onChannelsRefresh = libraries.channels::refresh,
                            onToolsRefresh = libraries.tools::reload,
                            onToolsSearchQueryChanged = libraries.tools::updateSearchQuery,
                            onToolsTagToggled = libraries.tools::toggleTag,
                            onToolsClearTags = libraries.tools::clearTags,
                            onToolsLoadMore = libraries.tools::loadMore,
                            onConfigSaved = { applyConfig(it) },
                            onTokenCleared = { applyConfig(activeConfig.copy(accessToken = null)) },
                            blockApi = blockApi,
                            crons = cronPanel.crons,
                            onDeleteCron = cronPanel::delete,
                            canCreateCron = cronPanel.available &&
                                (scheduleLibraryState.selectedAgentId != null || selectedAgentId != null),
                            onCreateCron = { filteredAgentId, name, prompt, cron, recurring, tz ->
                                val targetAgent = filteredAgentId
                                    ?: scheduleLibraryState.selectedAgentId
                                    ?: selectedAgentId
                                if (targetAgent != null) {
                                    cronPanel.create(
                                        CronDraft(
                                            agentId = targetAgent,
                                            name = name,
                                            prompt = prompt,
                                            cron = cron,
                                            recurring = recurring,
                                            timezone = tz,
                                        ),
                                    )
                                }
                            },
                            focusedAgentId = selectedAgentId,
                            skills = skillsPanel.all,
                            installedSkillNames = skillsPanel.installedNames,
                            skillsLoading = skillsPanel.loading,
                            skillsError = skillsPanel.error,
                            canManageSkills = skillsPanel.available && selectedAgentId != null,
                            focusedAgentName = selectedAgentName,
                            onRefreshSkills = { chatScope.launch { skillsPanel.reload(selectedAgentId) } },
                            onInstallSkill = { name -> skillsPanel.install(selectedAgentId, name) },
                            onUninstallSkill = { name -> skillsPanel.uninstall(selectedAgentId, name) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (showBackgroundTasks && subagentRepository != null) {
                    RailDivider()
                    DesktopBackgroundTasksPanel(
                        subagents = activeSubagents,
                        onClose = { showBackgroundTasks = false },
                        onFetchTodos = subagentRepository?.let { repo ->
                            { toolCallId -> repo.todos(toolCallId).getOrDefault(emptyList()) }
                        },
                    )
                }
            }
            if (selectedDestination == DesktopDestination.Conversations &&
                !showBackgroundTasks &&
                subagentRepository != null
            ) {
                DesktopBackgroundTasksToggle(
                    runningCount = activeSubagents.count { it.status == SubagentStatus.RUNNING },
                    onClick = { showBackgroundTasks = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp),
                )
            }
            if (showModelPicker) {
                DesktopModelPickerSheet(
                    models = availableModels,
                    selectedValue = chatState.composerModelLabel,
                    onSelect = chatController::setConversationModel,
                    onDismiss = { showModelPicker = false },
                )
            }
            if (showCommandPalette) {
                DesktopCommandPalette(
                    items = paletteItems,
                    onSelect = { item ->
                        when (item.kind) {
                            PaletteItemKind.Conversation -> {
                                chatController.selectConversation(item.id)
                                selectedDestination = DesktopDestination.Conversations
                            }
                            PaletteItemKind.Agent -> openAgent(item.id)
                            PaletteItemKind.Destination ->
                                DesktopDestination.entries.firstOrNull { it.name == item.id }
                                    ?.let { selectedDestination = it }
                        }
                    },
                    onDismiss = { showCommandPalette = false },
                )
            }
            // Edit agent is now a full-page surface in the main content pane
            // (see DesktopEditAgentSurface), not a modal overlay.
            if (showNewAgentDialog) {
                NewAgentDialog(
                    modelOptions = modelOptions,
                    onDismiss = { showNewAgentDialog = false },
                    onCreate = { name, modelValue ->
                        showNewAgentDialog = false
                        val (model, embedding) = resolveNewAgentDefaults(
                            agentRepository = dataBindings.sessionGraphProvider.current.agentRepository,
                            templateAgentId = selectedAgentId,
                            modelValue = modelValue,
                        )
                        chatController.createAgent(name = name, model = model, embedding = embedding)
                        selectedDestination = DesktopDestination.Conversations
                    },
                )
            }
          }
        }
    }
}

/**
 * Modal for creating a new agent: name + optional model, created with base
 * tools and default memory blocks (model/embedding default to the active
 * agent's config so the new agent is valid for this backend).
 */
@Composable
private fun NewAgentDialog(
    modelOptions: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onCreate: (name: String, model: String?) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("New agent")) }
    var modelValue by remember { mutableStateOf<String?>(null) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val modelLabel = modelOptions.firstOrNull { it.second == modelValue }?.first ?: "Same as current"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(420.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "New agent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                JewelTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    Surface(
                        onClick = { modelMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(modelLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (modelMenuOpen) {
                        JewelPopupMenu(
                            onDismissRequest = { modelMenuOpen = false; true },
                            horizontalAlignment = Alignment.Start,
                        ) {
                            selectableItem(selected = modelValue == null, onClick = { modelMenuOpen = false; modelValue = null }) {
                                DesktopControlText("Same as current")
                            }
                            modelOptions.forEach { (label, value) ->
                                selectableItem(selected = modelValue == value, onClick = { modelMenuOpen = false; modelValue = value }) {
                                    DesktopControlText(label)
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    DesktopOutlinedButton(onClick = onDismiss) { DesktopButtonContent("Cancel") }
                    DesktopDefaultButton(onClick = { onCreate(name.text.trim(), modelValue) }) {
                        DesktopButtonContent("Create agent")
                    }
                }
            }
        }
    }
}

/**
 * Sort key for a conversation's relative-time label. Real ISO timestamps sort by
 * recency; the local "Queued" placeholder floats to the top (it's a just-sent
 * message), while any other unparseable label (e.g. "Remote" — a conversation
 * with no activity timestamp at all) sorts to the bottom rather than being
 * treated as newest, since the stores already return rows in recency order.
 */
private fun conversationRecency(label: String): java.time.Instant =
    runCatching { java.time.Instant.parse(label) }.getOrNull()
        ?: if (label == "Queued") java.time.Instant.MAX else java.time.Instant.MIN

/**
 * Format an ISO-8601 instant (e.g. lastMessageAt) as a compact relative label
 * (now / 5m / 2h / 4d / 3w / 2mo). Non-ISO values are returned unchanged.
 */
private fun formatRelativeTimestamp(raw: String): String {
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return raw
    val seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        seconds < 604_800 -> "${seconds / 86_400}d"
        seconds < 2_592_000 -> "${seconds / 604_800}w"
        else -> "${seconds / 2_592_000}mo"
    }
}

@Composable
private fun RailDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Pulsing teal ring used as the agent "thinking" indicator. */
@Composable
private fun ThinkingRing(
    diameter: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinkingAlpha",
    )
    Box(
        modifier = modifier
            .size(diameter)
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(cornerRadius)),
    )
}

/**
 * Far-left workspace/agent rail (Penpot "App Mockups v2", 56.dp wide, #0A0A0A):
 * a "+" new-session button, a stack of gradient agent orbs (one per agent), and
 * search/settings/identity actions pinned to the bottom.
 */
@Composable
private fun DesktopAgentRail(
    agents: List<Pair<String, String>>,
    avatarStyleByAgentId: Map<String, Int>,
    selectedAgentId: String?,
    thinkingAgentId: String?,
    onAgentSelected: (String) -> Unit,
    onNewSession: () -> Unit,
    onSearch: () -> Unit,
    onAvatarCompanion: () -> Unit = {},
    avatarCompanionActive: Boolean = false,
) {
    // Collapse agents that share a display name — e.g. the many ephemeral
    // "Letta Code" agents spawned per task — into a single stacked orb with a
    // count chip, so the rail doesn't grow unbounded with near-duplicate spawns.
    // Order follows first appearance in [agents].
    val groups = remember(agents) {
        agents.groupBy { it.second }
            .map { (name, members) -> AgentRailGroup(name = name, agentIds = members.map { it.first }) }
    }
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DesktopTooltip(text = "New session") {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onNewSession),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "New session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // The agent list scrolls so a long roster never pushes the bottom
        // actions (search/settings/account) off-screen.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEachIndexed { index, group ->
                val selected = selectedAgentId != null && selectedAgentId in group.agentIds
                val thinking = thinkingAgentId != null && thinkingAgentId in group.agentIds
                val count = group.agentIds.size
                // Clicking a stack opens its already-selected member if one is
                // selected, otherwise its first (most-recent) member.
                val targetAgentId = group.agentIds.firstOrNull { it == selectedAgentId } ?: group.agentIds.first()
                // Use the stack member's saved avatar style if any set one,
                // otherwise the position-derived colour.
                val orbStyle = group.agentIds.firstNotNullOfOrNull { avatarStyleByAgentId[it] } ?: index
                val tooltip = buildString {
                    append(group.name)
                    if (count > 1) append(" · $count agents")
                    if (thinking) append(" · thinking…")
                }
                DesktopTooltip(text = tooltip) {
                    Box(
                        modifier = Modifier.size(width = 46.dp, height = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(width = 3.dp, height = 28.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                            )
                        }
                        if (thinking) {
                            // Concentric with the 36dp/7dp orb (2dp gap → 9dp corner)
                            // and sized to fit the 40dp slot so it doesn't crowd
                            // neighbouring orbs.
                            ThinkingRing(diameter = 40.dp, cornerRadius = 9.dp)
                        }
                        AgentOrb(
                            index = orbStyle,
                            size = 36.dp,
                            onClick = { onAgentSelected(targetAgentId) },
                        ) {
                            Text(
                                text = group.name.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                        if (count > 1) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .defaultMinSize(minWidth = 19.dp, minHeight = 19.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (count > 99) "99+" else count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }

        RailActionIcon(
            icon = Icons.Outlined.Face,
            description = if (avatarCompanionActive) "Stop avatar companion" else "Avatar companion",
            onClick = onAvatarCompanion,
            tint = if (avatarCompanionActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        RailActionIcon(Icons.Outlined.Search, "Search", onSearch)
    }
}

private const val AVATAR_COMPANION_VRM_PATH_KEY = "avatar.companion.vrm_path"

/** A rail entry: one or more agents that share a display name, stacked together. */
private data class AgentRailGroup(
    val name: String,
    val agentIds: List<String>,
)

@Composable
private fun RailActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified,
) {
    DesktopTooltip(text = description) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint.takeIf { it != Color.Unspecified }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Agent sidebar (231.dp, #0D0D0D): the active agent header, per-agent
 * navigation (Memory/Schedules/Channels/Skills/New chat), then the pinned
 * conversation list and a Documents section.
 */
@Composable
private fun DesktopAgentSidebar(
    agentName: String,
    agentOrbIndex: Int,
    conversations: List<DesktopConversationSummary>,
    selectedConversationId: String?,
    thinkingConversationId: String?,
    deletingConversationIds: Set<String> = emptySet(),
    archiveFilter: ConversationArchiveFilter,
    onArchiveFilterChange: (ConversationArchiveFilter) -> Unit,
    onArchiveConversation: (id: String, archived: Boolean) -> Unit,
    selectedDestination: DesktopDestination,
    mode: WorkPlayMode,
    onModeChange: (WorkPlayMode) -> Unit,
    onDestinationSelected: (DesktopDestination) -> Unit,
    onConversationSelected: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onEditAgent: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(231.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Work | Play lens switcher (Penpot "App Mockups v2": top of sidebar).
        WorkPlaySwitcher(
            mode = mode,
            onModeChange = onModeChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        // Agent header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 2.dp, bottom = 16.dp),
        ) {
            // Tapping the agent (orb + name) opens its Edit Agent settings; the
            // ⋮ menu keeps the other actions.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEditAgent),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AgentOrb(index = agentOrbIndex, size = 30.dp, cornerRadius = 6.dp)
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Agent menu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { menuOpen = true },
                )
                if (menuOpen) {
                    JewelPopupMenu(
                        onDismissRequest = {
                            menuOpen = false
                            true
                        },
                        horizontalAlignment = Alignment.End,
                    ) {
                        selectableItem(selected = false, onClick = { menuOpen = false; onNewChat() }) {
                            DesktopControlText("New chat")
                        }
                        selectableItem(selected = false, onClick = { menuOpen = false; onEditAgent() }) {
                            DesktopControlText("Edit agent")
                        }
                        selectableItem(
                            selected = false,
                            onClick = { menuOpen = false; onDestinationSelected(DesktopDestination.Memory) },
                        ) {
                            DesktopControlText("Memory")
                        }
                        selectableItem(
                            selected = false,
                            onClick = { menuOpen = false; onDestinationSelected(DesktopDestination.Settings) },
                        ) {
                            DesktopControlText("Settings")
                        }
                    }
                }
            }
        }

        WorkPlayLens.navDestinations(mode).forEach { lensDestination ->
            val target = lensNavTarget(mode, lensDestination)
            DesktopNavRow(
                label = WorkPlayLens.destinationLabel(mode, lensDestination),
                icon = target.second,
                selected = selectedDestination == target.first,
                onClick = { onDestinationSelected(target.first) },
            )
        }
        DesktopNavRow(
            label = WorkPlayLens.newConversationLabel(mode),
            icon = Icons.Outlined.Edit,
            selected = false,
            onClick = onNewChat,
        )

        // Pinned conversations / scenes.
        SidebarSection(WorkPlayLens.conversationsHeader(mode))
        // Active / Archived / All status filter.
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConversationArchiveFilter.entries.forEach { filter ->
                DesktopChipTab(text = filter.label, active = archiveFilter == filter) {
                    onArchiveFilterChange(filter)
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = conversations, key = { it.id }) { conversation ->
                SidebarConversationRow(
                    title = conversation.title,
                    timeLabel = formatRelativeTimestamp(conversation.updatedAtLabel),
                    selected = selectedDestination == DesktopDestination.Conversations &&
                        conversation.id == selectedConversationId,
                    thinking = conversation.id == thinkingConversationId,
                    deleting = conversation.id in deletingConversationIds,
                    archived = conversation.archived,
                    onClick = { onConversationSelected(conversation.id) },
                    onArchiveToggle = { onArchiveConversation(conversation.id, !conversation.archived) },
                    onDelete = { onDeleteConversation(conversation.id) },
                )
            }
            item {
                SidebarSection("Documents")
            }
            if (conversations.isEmpty()) {
                item {
                    Text(
                        text = "No chats",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
        }

        DesktopNavRow(
            label = "Settings",
            icon = Icons.Outlined.Settings,
            selected = selectedDestination == DesktopDestination.Settings,
            onClick = { onDestinationSelected(DesktopDestination.Settings) },
        )
    }
}

@Composable
private fun SidebarConversationRow(
    title: String,
    timeLabel: String,
    selected: Boolean,
    thinking: Boolean,
    deleting: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val container = if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent
    // While thinking, the conversation icon pulses in the primary (teal) color.
    val pulseAlpha = if (thinking) {
        val transition = rememberInfiniteTransition(label = "convThinking")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "convThinkingAlpha",
        ).value
    } else {
        1f
    }
    val iconColor = when {
        thinking -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Right-click anywhere on the row for the archive/restore + delete actions.
    ContextMenuArea(
        items = {
            if (deleting) {
                emptyList()
            } else {
                listOf(
                    ContextMenuItem(if (archived) "Restore chat" else "Archive chat", onArchiveToggle),
                    ContextMenuItem("Delete chat") { confirmDelete = true },
                )
            }
        },
    ) {
        Surface(
            onClick = onClick,
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = container,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // Drives both the click ripple (now clipped to the rounded shape) and the
            // `hovered` state below, so no separate .hoverable is needed.
            interactionSource = interactionSource,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    deleting -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // On hover the leading icon becomes a one-click archive/restore button.
                    hovered -> Icon(
                        imageVector = if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                        contentDescription = if (archived) "Restore chat" else "Archive chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(15.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onArchiveToggle,
                            ),
                    )
                    else -> Icon(
                        imageVector = if (thinking) Icons.Outlined.Autorenew else Icons.Outlined.ChatBubbleOutline,
                        contentDescription = if (thinking) "thinking" else null,
                        tint = iconColor,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (deleting) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (deleting) "Deleting…" else timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }

    if (confirmDelete) {
        DesktopConfirmDialog(
            title = "Delete chat?",
            message = "\"$title\" will be permanently removed. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Destructive-action confirmation as a real, separate desktop window (it "pops
 * out" of the app rather than dimming the page like a mobile sheet).
 */
@Composable
private fun DesktopConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dialogState = rememberDialogState(size = DpSize(420.dp, 210.dp))
    DialogWindow(
        onCloseRequest = onDismiss,
        state = dialogState,
        title = title,
        undecorated = true,
        resizable = false,
    ) {
        val windowScope = this
        DesktopMaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Custom dark title bar — the native OS chrome is hidden.
                    with(windowScope) {
                        WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = 46.dp, height = 38.dp)
                                        .clickable(onClick = onDismiss),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Close",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        ) {
                            DesktopOutlinedButton(onClick = onDismiss) { DesktopButtonContent("Cancel") }
                            DesktopDefaultButton(onClick = onConfirm) { DesktopButtonContent(confirmLabel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarSection(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 2.dp),
    )
}

/** Maps a lens nav item to its concrete desktop destination + icon for the mode. */
private fun lensNavTarget(
    mode: WorkPlayMode,
    destination: LensDestination,
): Pair<DesktopDestination, ImageVector> = when (destination) {
    LensDestination.Memory -> DesktopDestination.Memory to Icons.Outlined.Psychology
    LensDestination.Schedules -> DesktopDestination.Schedules to Icons.Outlined.Schedule
    LensDestination.Channels -> DesktopDestination.Channels to Icons.Outlined.Hub
    LensDestination.Skills -> DesktopDestination.Agents to
        if (mode == WorkPlayMode.Play) Icons.Outlined.Group else Icons.Outlined.Build
    LensDestination.Conversations -> DesktopDestination.Conversations to Icons.Outlined.ChatBubbleOutline
}

/** Segmented Work | Play toggle (Penpot "App Mockups v2", top of the sidebar). */
@Composable
private fun WorkPlaySwitcher(
    mode: WorkPlayMode,
    onModeChange: (WorkPlayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            WorkPlayMode.entries.forEach { option ->
                val selected = option == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
                        )
                        .clickable { onModeChange(option) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = WorkPlayLens.modeLabel(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopNavRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    subdued: Boolean = false,
    tooltip: String = label,
) {
    val content = when {
        selected -> MaterialTheme.colorScheme.onSurface
        subdued -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DesktopTooltip(text = tooltip) {
        JewelSimpleListItem(
            selected = selected,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            height = 34.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JewelIcon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = content,
                )
                JewelText(
                    text = label,
                    color = content,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val DesktopDestination.icon: ImageVector
    get() = when (this) {
        DesktopDestination.Overview -> Icons.Outlined.Dashboard
        DesktopDestination.Agents -> Icons.Outlined.SmartToy
        DesktopDestination.Memory -> Icons.Outlined.Memory
        DesktopDestination.Schedules -> Icons.Outlined.Schedule
        DesktopDestination.Channels -> Icons.Outlined.Hub
        DesktopDestination.Conversations -> Icons.Outlined.Forum
        DesktopDestination.Settings -> Icons.Outlined.Settings
    }

@Composable
private fun DestinationContent(
    destination: DesktopDestination,
    state: DesktopBootstrapState,
    chatState: DesktopChatSurfaceState,
    memoryState: DesktopMemorySurfaceState,
    scheduleLibraryState: DesktopScheduleLibraryState,
    channelLibraryState: DesktopChannelLibraryState,
    toolLibraryState: DesktopToolLibraryState,
    onChatConversationSelected: (String) -> Unit,
    onChatConversationDeleted: (String) -> Unit,
    onChatComposerTextChanged: (String) -> Unit,
    onChatSend: () -> Unit,
    onChatAttachImage: () -> Unit,
    onChatRemoveImageAttachment: (Int) -> Unit,
    onChatRetryConnection: () -> Unit,
    onMemoryRefresh: () -> Unit,
    onMemoryAgentSelected: (String) -> Unit,
    onSchedulesRefresh: () -> Unit,
    onScheduleAgentSelected: (String) -> Unit,
    onChannelsRefresh: () -> Unit,
    onToolsRefresh: () -> Unit,
    onToolsSearchQueryChanged: (String) -> Unit,
    onToolsTagToggled: (String) -> Unit,
    onToolsClearTags: () -> Unit,
    onToolsLoadMore: () -> Unit,
    onConfigSaved: (LettaConfig) -> Unit,
    onTokenCleared: () -> Unit,
    blockApi: DesktopBlockApi?,
    crons: List<CronTask>,
    onDeleteCron: (String) -> Unit,
    canCreateCron: Boolean,
    onCreateCron: (agentId: String?, name: String, prompt: String, cron: String, recurring: Boolean, timezone: String) -> Unit,
    focusedAgentId: String?,
    skills: List<Skill>,
    installedSkillNames: Set<String>,
    skillsLoading: Boolean,
    skillsError: String?,
    canManageSkills: Boolean,
    focusedAgentName: String?,
    onRefreshSkills: () -> Unit,
    onInstallSkill: (String) -> Unit,
    onUninstallSkill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (destination == DesktopDestination.Memory) {
        DesktopMemorySurface(
            state = memoryState,
            onRefresh = onMemoryRefresh,
            onAgentSelected = onMemoryAgentSelected,
            modifier = modifier,
            blockApi = blockApi,
            onBlockChanged = onMemoryRefresh,
        )
        return
    }
    if (destination == DesktopDestination.Schedules) {
        DesktopScheduleSurface(
            state = scheduleLibraryState,
            onRefresh = onSchedulesRefresh,
            onAgentSelected = onScheduleAgentSelected,
            modifier = modifier,
            crons = crons,
            focusedAgentId = focusedAgentId,
            onDeleteCron = onDeleteCron,
            canCreate = canCreateCron,
            onCreateCron = onCreateCron,
        )
        return
    }
    if (destination == DesktopDestination.Channels) {
        DesktopChannelLibrarySurface(
            state = channelLibraryState,
            onRefresh = onChannelsRefresh,
            modifier = modifier,
        )
        return
    }
    if (destination == DesktopDestination.Agents) {
        DesktopSkillsSurface(
            skills = skills,
            installedSkillNames = installedSkillNames,
            skillsLoading = skillsLoading,
            skillsError = skillsError,
            canManageSkills = canManageSkills,
            focusedAgentName = focusedAgentName,
            onRefreshSkills = onRefreshSkills,
            onInstallSkill = onInstallSkill,
            onUninstallSkill = onUninstallSkill,
            toolState = toolLibraryState,
            onToolsRefresh = onToolsRefresh,
            onToolsSearchQueryChanged = onToolsSearchQueryChanged,
            onToolsTagToggled = onToolsTagToggled,
            onToolsClearTags = onToolsClearTags,
            onToolsLoadMore = onToolsLoadMore,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = destination.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (destination) {
            DesktopDestination.Overview -> {
                item { BackendCard(state.config) }
                item { StartupReadinessCard(state.featureReadiness) }
            }
            DesktopDestination.Agents -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Memory -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Schedules -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Channels -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Conversations -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Settings -> {
                item {
                    BackendSettingsCard(
                        config = state.config,
                        onConfigSaved = onConfigSaved,
                        onTokenCleared = onTokenCleared,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackendCard(config: LettaConfig) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column {
                    Text(
                        text = "Default backend",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = config.serverUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text = config.mode.label,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    borderColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                )
                StatusPill(
                    text = "Shared model layer",
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    borderColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                )
                StatusPill(
                    text = "Windows JVM",
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    borderColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                )
            }
        }
    }
}

@Composable
private fun BackendSettingsCard(
    config: LettaConfig,
    onConfigSaved: (LettaConfig) -> Unit,
    onTokenCleared: () -> Unit,
) {
    var serverUrl by remember(config.id, config.serverUrl) { mutableStateOf(TextFieldValue(config.serverUrl)) }
    var tokenInput by remember(config.id) { mutableStateOf(TextFieldValue("")) }
    var mode by remember(config.id) { mutableStateOf(config.mode) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Backend",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            DesktopSettingsFieldLabel("Server URL")
            JewelTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                placeholder = { JewelText("https://app.letta.com") },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopSettingsFieldLabel("Mode")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LettaConfig.Mode.entries.forEach { option ->
                        DesktopChipTab(
                            text = option.label,
                            active = mode == option,
                            onClick = { mode = option },
                        )
                    }
                }
            }
            DesktopSettingsFieldLabel("Access token")
            JewelTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                placeholder = {
                    JewelText(if (config.accessToken == null) "Optional" else "Saved token hidden")
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopDefaultButton(
                    onClick = {
                        val normalizedUrl = serverUrl.text.trim()
                        onConfigSaved(
                            LettaConfig(
                                id = desktopConfigIdFor(normalizedUrl),
                                mode = mode,
                                serverUrl = normalizedUrl,
                                accessToken = tokenInput.text.trim().takeIf { it.isNotBlank() }
                                    ?: config.accessToken,
                            ),
                        )
                        tokenInput = TextFieldValue("")
                    },
                ) {
                    DesktopButtonContent("Save")
                }
                if (config.accessToken != null) {
                    DesktopOutlinedButton(
                        onClick = {
                            tokenInput = TextFieldValue("")
                            onTokenCleared()
                        },
                    ) {
                        DesktopButtonContent("Clear token")
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSettingsFieldLabel(text: String) {
    JewelText(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

private val LettaConfig.Mode.label: String
    get() = when (this) {
        LettaConfig.Mode.CLOUD -> "Cloud"
        LettaConfig.Mode.SELF_HOSTED -> "Self-hosted"
        LettaConfig.Mode.LOCAL -> "Local runtime"
    }

@Composable
private fun StartupReadinessCard(featureReadiness: List<DesktopFeatureReadiness>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Startup readiness",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            featureReadiness.forEach { feature ->
                ReadinessRow(feature)
            }
        }
    }
}

@Composable
private fun ReadinessRow(feature: DesktopFeatureReadiness) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .background(feature.state.color(), MaterialTheme.shapes.small),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = feature.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(
                    text = feature.state.label,
                    containerColor = feature.state.color().copy(alpha = 0.12f),
                    contentColor = feature.state.color(),
                    borderColor = Color.Transparent,
                )
            }
            Text(
                text = feature.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DesktopFeatureState.color(): Color = when (this) {
    DesktopFeatureState.Ready -> MaterialTheme.colorScheme.primary
    DesktopFeatureState.InProgress -> MaterialTheme.colorScheme.tertiary
    DesktopFeatureState.AndroidOnly -> MaterialTheme.colorScheme.secondary
}

private val DesktopFeatureState.label: String
    get() = when (this) {
        DesktopFeatureState.Ready -> "Ready"
        DesktopFeatureState.InProgress -> "In progress"
        DesktopFeatureState.AndroidOnly -> "Android only"
    }

@Composable
private fun PortabilityCard(
    title: String,
    body: String,
    state: DesktopFeatureState,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusPill(
                text = state.label,
                containerColor = state.color().copy(alpha = 0.12f),
                contentColor = state.color(),
                borderColor = Color.Transparent,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

