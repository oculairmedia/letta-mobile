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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Build
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
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
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
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.onboarding.OnboardingTaskKind
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.desktop.channels.DesktopChannelLibraryController
import com.letta.mobile.desktop.channels.DesktopChannelLibraryState
import com.letta.mobile.desktop.channels.DesktopChannelLibrarySurface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.chat.AgentSphere
import com.letta.mobile.desktop.chat.ChatDetailPane
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
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
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
import com.letta.mobile.desktop.tools.DesktopToolLibrarySurface
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

@Composable
fun LettaDesktopApp(
    onActiveTitleChange: (String) -> Unit = {},
) {
    var selectedDestination by rememberSaveable { mutableStateOf(DesktopDestination.Conversations) }
    var showNewAgentDialog by remember { mutableStateOf(false) }
    var editAgentId by remember { mutableStateOf<String?>(null) }
    val secureSettingsStore = remember { DesktopFileSecureSettingsStore() }
    val configStore = remember(secureSettingsStore) { DesktopLettaConfigStore(secureSettingsStore) }
    var activeConfig by remember { mutableStateOf(configStore.load()) }
    val dataBindings = remember(configStore) {
        createDefaultDesktopDataBindings(
            secureSettingsStore = secureSettingsStore,
            configProvider = { activeConfig },
        )
    }
    var bootstrapState by remember(dataBindings) {
        mutableStateOf(defaultDesktopBootstrapState(dataBindings, activeConfig))
    }
    val chatScope = rememberCoroutineScope()
    val chatController = remember(bootstrapState, chatScope, dataBindings.sessionGraphProvider) {
        DesktopChatController(
            bootstrapState = bootstrapState,
            scope = chatScope,
            agentNamesByIdProvider = { agentIds ->
                val agentRepository = dataBindings.sessionGraphProvider.current.agentRepository
                runCatching {
                    agentRepository.refreshAgentsIfStale(maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS)
                }
                val resolved = mutableMapOf<String, String>()
                // Seed from the cached list (fast path).
                agentRepository.agents.value.forEach { agent ->
                    agent.name.takeIf { it.isNotBlank() }?.let { resolved[agent.id.value] = it }
                }
                // For any conversation agent still unresolved, fetch it directly
                // (robust against list pagination / partial caches).
                agentIds.filter { it !in resolved }.forEach { id ->
                    val name = agentRepository.getCachedAgent(id)?.name
                        ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.name
                    name?.takeIf { it.isNotBlank() }?.let { resolved[id] = it }
                }
                resolved
            },
            agentModelByIdProvider = { agentIds ->
                val agentRepository = dataBindings.sessionGraphProvider.current.agentRepository
                val resolved = mutableMapOf<String, String>()
                agentRepository.agents.value.forEach { agent ->
                    agent.model?.takeIf { it.isNotBlank() }?.let { resolved[agent.id.value] = it }
                }
                agentIds.filter { it !in resolved }.forEach { id ->
                    val model = agentRepository.getCachedAgent(id)?.model
                        ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.model
                    model?.takeIf { it.isNotBlank() }?.let { resolved[id] = it }
                }
                resolved
            },
        )
    }
    val chatState by chatController.state.collectAsState()
    val availableModels by chatController.availableModels.collectAsState()
    val deletingConversationIds by chatController.deletingConversationIds.collectAsState()
    val modelOptions = remember(availableModels) {
        availableModels.map { model ->
            val label = model.displayNameOverride?.takeIf { it.isNotBlank() }
                ?: model.name
            val value = model.handle?.takeIf { it.isNotBlank() } ?: model.name
            label to value
        }
    }
    val blockApi = remember(activeConfig) {
        activeConfig.takeIf { it.serverUrl.isNotBlank() }?.let { DesktopBlockApi(it) }
    }
    val cronApi = remember(activeConfig) {
        activeConfig.takeIf { it.serverUrl.isNotBlank() }?.let { CronApi(it, createDesktopLettaHttpClient()) }
    }
    var allCrons by remember(cronApi) { mutableStateOf<List<CronTask>>(emptyList()) }
    val skillApi = remember(activeConfig) {
        activeConfig.takeIf { it.serverUrl.isNotBlank() }?.let { SkillApi(it, createDesktopLettaHttpClient()) }
    }
    var allSkills by remember(skillApi) { mutableStateOf<List<Skill>>(emptyList()) }
    var installedSkillNames by remember(skillApi) { mutableStateOf<Set<String>>(emptySet()) }
    var skillsLoading by remember(skillApi) { mutableStateOf(false) }
    var skillsError by remember(skillApi) { mutableStateOf<String?>(null) }
    val slashCommandApi = remember(activeConfig) {
        activeConfig.takeIf { it.serverUrl.isNotBlank() }?.let { SlashCommandApi(it, createDesktopLettaHttpClient()) }
    }
    var agentSlashCommands by remember(slashCommandApi) { mutableStateOf<List<AgentSlashCommand>>(emptyList()) }
    // Active-subagent registry (Background tasks). Desktop streams chat over SSE,
    // but the subagent registry only exists on the shim's mobile WS protocol, so
    // we open a lean WS side-channel and feed the shared SubagentRepository.
    val subagentTransport = remember(activeConfig) {
        activeConfig.takeIf { it.serverUrl.isNotBlank() && !it.accessToken.isNullOrBlank() }
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
    val activeSubagents by produceState(emptyList<SubagentEntry>(), subagentRepository) {
        val repo = subagentRepository
        if (repo == null) {
            value = emptyList()
        } else {
            repo.activeSubagentsFlow().collect { value = it }
        }
    }
    var showBackgroundTasks by remember { mutableStateOf(false) }
    // Work | Play presentation lens over the same agents/memory/conversations.
    var workPlayMode by remember { mutableStateOf(WorkPlayMode.Work) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }
    val memoryController = remember(bootstrapState.sessionGraphId, chatScope) {
        DesktopMemoryController(
            sessionGraphProvider = dataBindings.sessionGraphProvider,
            scope = chatScope,
        )
    }
    val memoryState by memoryController.state.collectAsState()
    val scheduleLibraryController = remember(bootstrapState.sessionGraphId, chatScope) {
        DesktopScheduleLibraryController(
            sessionGraphProvider = dataBindings.sessionGraphProvider,
            scope = chatScope,
        )
    }
    val scheduleLibraryState by scheduleLibraryController.state.collectAsState()
    val channelLibraryController = remember(bootstrapState.sessionGraphId, chatScope) {
        DesktopChannelLibraryController(
            sessionGraphProvider = dataBindings.sessionGraphProvider,
            scope = chatScope,
        )
    }
    val channelLibraryState by channelLibraryController.state.collectAsState()
    val toolLibraryController = remember(bootstrapState.sessionGraphId, chatScope) {
        DesktopToolLibraryController(
            sessionGraphProvider = dataBindings.sessionGraphProvider,
            scope = chatScope,
        )
    }
    val toolLibraryState by toolLibraryController.state.collectAsState()
    // Cmd/Ctrl-K opens the command palette (Penpot shows the ⌘K hint). A global
    // AWT key dispatcher fires regardless of which Compose field has focus.
    DisposableEffect(Unit) {
        val focusManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = java.awt.KeyEventDispatcher { event ->
            if (event.id == java.awt.event.KeyEvent.KEY_PRESSED &&
                event.keyCode == java.awt.event.KeyEvent.VK_K &&
                (event.isControlDown || event.isMetaDown)
            ) {
                showCommandPalette = true
                true
            } else {
                false
            }
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }
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

    LaunchedEffect(chatController) {
        chatController.start()
    }
    DisposableEffect(chatController) {
        onDispose { chatController.close() }
    }
    LaunchedEffect(memoryController) {
        memoryController.start()
    }
    LaunchedEffect(scheduleLibraryController) {
        scheduleLibraryController.start()
    }
    LaunchedEffect(channelLibraryController) {
        channelLibraryController.start()
    }
    LaunchedEffect(toolLibraryController) {
        toolLibraryController.start()
    }
    LaunchedEffect(selectedDestination, chatState.selectedConversation?.agentId, memoryController) {
        if (selectedDestination == DesktopDestination.Memory) {
            chatState.selectedConversation?.agentId?.let(memoryController::selectAgent)
        }
    }
    LaunchedEffect(selectedDestination, chatState.selectedConversation?.agentId, scheduleLibraryController) {
        if (selectedDestination == DesktopDestination.Schedules) {
            chatState.selectedConversation?.agentId?.let(scheduleLibraryController::selectAgent)
        }
    }
    LaunchedEffect(selectedDestination, cronApi) {
        if (selectedDestination == DesktopDestination.Schedules && cronApi != null) {
            allCrons = runCatching { cronApi.listCrons() }.getOrDefault(emptyList())
        }
    }
    DisposableEffect(memoryController) {
        onDispose { memoryController.close() }
    }
    DisposableEffect(scheduleLibraryController) {
        onDispose { scheduleLibraryController.close() }
    }
    DisposableEffect(channelLibraryController) {
        onDispose { channelLibraryController.close() }
    }
    DisposableEffect(toolLibraryController) {
        onDispose { toolLibraryController.close() }
    }

    val activeTitle = when (selectedDestination) {
        DesktopDestination.Conversations ->
            chatState.selectedConversation?.title ?: "Letta Desktop"
        else -> selectedDestination.label
    }
    LaunchedEffect(activeTitle) { onActiveTitleChange(activeTitle) }

    // Distinct agents (by id, since many agents share a display name) that have
    // conversations — these are the rail orbs. Same-named agents are stacked in
    // the rail, and the sidebar lists the whole stack's conversations together.
    // The most-recent updatedAtLabel per agent id is also tracked so the stack
    // grouping can pick the freshest member when no member is already selected.
    val railAgents = remember(chatState.conversations) {
        chatState.conversations
            .filter { !it.agentId.isNullOrBlank() }
            .groupBy { it.agentId!! }
            .map { (agentId, summaries) ->
                val latest = summaries.maxByOrNull { parseRailTimestamp(it.updatedAtLabel) ?: java.time.Instant.MIN }
                Triple(agentId, latest?.agentName ?: summaries.first().agentName, latest?.updatedAtLabel ?: "")
            }
    }
    val selectedAgentId = chatState.selectedConversation?.agentId
        ?: railAgents.firstOrNull()?.first
    val selectedAgentOrbIndex = railAgents.indexOfFirst { it.first == selectedAgentId }.coerceAtLeast(0)
    val selectedAgentName = railAgents.firstOrNull { it.first == selectedAgentId }?.second
        ?: chatState.selectedConversation?.agentName ?: "Letta"
    // List every conversation across the selected stack (all agents sharing the
    // display name), newest first — so the "Letta Code" stack shows all its
    // spawns' conversations in one time-ordered list rather than just one agent's.
    val agentConversations = remember(chatState.conversations, selectedAgentName) {
        chatState.conversations
            .filter { it.agentName == selectedAgentName }
            .sortedByDescending {
                // Parseable timestamps sort newest-first. Genuine local pending
                // entries (e.g. "Queued" before the first server sync) still
                // surface at the top because they typically haven't been
                // timestamped yet; remote rows with unparseable labels fall
                // LAST so they don't pin above genuinely newer chats.
                runCatching { java.time.Instant.parse(it.updatedAtLabel) }.getOrNull()
                    ?: if (it.updatedAtLabel.isBlank()) java.time.Instant.MAX else java.time.Instant.MIN
            }
    }
    // @mention candidates: other agents + the focused agent's memory blocks.
    // (Files need a client-side index — tracked as a follow-up.)
    val mentionables = remember(railAgents, memoryState) {
        buildList {
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
    }
    // Cmd/Ctrl-K command palette over conversations, agents, and destinations.
    val paletteItems = remember(chatState.conversations, railAgents, workPlayMode) {
        buildList {
            chatState.conversations.forEach { conversation ->
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

    // Load the skills registry + the focused agent's installed skills when the
    // Skills page is open (or the focused agent changes).
    suspend fun reloadSkills() {
        val api = skillApi ?: return
        skillsLoading = true
        skillsError = null
        runCatching {
            allSkills = api.listSkills()
            installedSkillNames = selectedAgentId?.let { api.listAgentSkills(it).map { s -> s.name }.toSet() }
                ?: emptySet()
        }.onFailure { skillsError = it.message ?: "Could not load skills." }
        skillsLoading = false
    }
    LaunchedEffect(selectedDestination, skillApi, selectedAgentId) {
        if (selectedDestination == DesktopDestination.Agents && skillApi != null) {
            reloadSkills()
        }
    }
    // Load the focused agent's server slash commands for the composer palette.
    LaunchedEffect(slashCommandApi, selectedAgentId) {
        val api = slashCommandApi
        val agent = selectedAgentId
        agentSlashCommands = if (api != null && agent != null) {
            runCatching { api.listAgentSlashCommands(agent) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
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
                    agents = railAgents.map { Triple(it.first, it.second, it.third) },
                    selectedAgentId = selectedAgentId,
                    thinkingAgentId = thinkingAgentId,
                    onAgentSelected = { agentId ->
                        chatState.conversations
                            .firstOrNull { it.agentId == agentId }
                            ?.let { chatController.selectConversation(it.id) }
                        selectedDestination = DesktopDestination.Conversations
                    },
                    onNewSession = { showNewAgentDialog = true },
                    onSearch = { showCommandPalette = true },
                    onSettings = { selectedDestination = DesktopDestination.Settings },
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
                    selectedDestination = selectedDestination,
                    mode = workPlayMode,
                    onModeChange = { workPlayMode = it },
                    onDestinationSelected = { selectedDestination = it },
                    onConversationSelected = {
                        chatController.selectConversation(it)
                        selectedDestination = DesktopDestination.Conversations
                    },
                    onDeleteConversation = chatController::deleteConversation,
                    onNewChat = {
                        selectedDestination = DesktopDestination.Conversations
                        chatController.createConversation()
                    },
                    onEditAgent = { editAgentId = selectedAgentId },
                )
                RailDivider()
                // Main content pane.
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedDestination == DesktopDestination.Conversations) {
                        val composerCommands = buildList {
                            add(ComposerCommand("new", "Start a new chat") { chatController.createConversation() })
                            add(ComposerCommand("agent", "Create a new agent") { showNewAgentDialog = true })
                            add(ComposerCommand("edit", "Edit this agent") { editAgentId = selectedAgentId })
                            add(ComposerCommand("memory", "Open memory") { selectedDestination = DesktopDestination.Memory })
                            add(ComposerCommand("schedules", "Open schedules") { selectedDestination = DesktopDestination.Schedules })
                            add(ComposerCommand("skills", "Open skills & tools") { selectedDestination = DesktopDestination.Agents })
                            add(ComposerCommand("channels", "Open channels") { selectedDestination = DesktopDestination.Channels })
                            add(ComposerCommand("settings", "Open settings") { selectedDestination = DesktopDestination.Settings })
                            // Server-backed slash commands (goal mode + installed skills).
                            // Selecting one fills the composer so the user can add
                            // args and send; the server interprets the slash prefix.
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
                            onMemoryRefresh = memoryController::reload,
                            onMemoryAgentSelected = memoryController::selectAgent,
                            onSchedulesRefresh = scheduleLibraryController::reload,
                            onScheduleAgentSelected = scheduleLibraryController::selectAgent,
                            onChannelsRefresh = channelLibraryController::refresh,
                            onToolsRefresh = toolLibraryController::reload,
                            onToolsSearchQueryChanged = toolLibraryController::updateSearchQuery,
                            onToolsTagToggled = toolLibraryController::toggleTag,
                            onToolsClearTags = toolLibraryController::clearTags,
                            onToolsLoadMore = toolLibraryController::loadMore,
                            onConfigSaved = { nextConfig ->
                                configStore.save(nextConfig)
                                activeConfig = configStore.load()
                                dataBindings.sessionGraphProvider.rebuild()
                                bootstrapState = defaultDesktopBootstrapState(dataBindings, activeConfig)
                            },
                            onTokenCleared = {
                                val nextConfig = activeConfig.copy(accessToken = null)
                                configStore.save(nextConfig)
                                activeConfig = configStore.load()
                                dataBindings.sessionGraphProvider.rebuild()
                                bootstrapState = defaultDesktopBootstrapState(dataBindings, activeConfig)
                            },
                            blockApi = blockApi,
                            crons = allCrons,
                            onDeleteCron = { id ->
                                chatScope.launch {
                                    cronApi?.let {
                                        runCatching { it.deleteCron(id) }
                                        allCrons = runCatching { it.listCrons() }.getOrDefault(emptyList())
                                    }
                                }
                            },
                            canCreateCron = cronApi != null &&
                                (scheduleLibraryState.selectedAgentId != null || selectedAgentId != null),
                            onCreateCron = { filteredAgentId, name, prompt, cron, recurring, tz ->
                                val targetAgent = filteredAgentId
                                    ?: scheduleLibraryState.selectedAgentId
                                    ?: selectedAgentId
                                if (cronApi != null && targetAgent != null) {
                                    chatScope.launch {
                                        runCatching {
                                            cronApi.createCron(
                                                agentId = targetAgent,
                                                name = name,
                                                description = name,
                                                prompt = prompt,
                                                cron = cron,
                                                timezone = tz,
                                                recurring = recurring,
                                            )
                                        }
                                        allCrons = runCatching { cronApi.listCrons() }.getOrDefault(emptyList())
                                    }
                                }
                            },
                            focusedAgentId = selectedAgentId,
                            skills = allSkills,
                            installedSkillNames = installedSkillNames,
                            skillsLoading = skillsLoading,
                            skillsError = skillsError,
                            canManageSkills = skillApi != null && selectedAgentId != null,
                            focusedAgentName = selectedAgentName,
                            onRefreshSkills = { chatScope.launch { reloadSkills() } },
                            onInstallSkill = { name ->
                                val agent = selectedAgentId
                                if (skillApi != null && agent != null) {
                                    chatScope.launch {
                                        runCatching { skillApi.installSkill(agent, name) }
                                            .onFailure { skillsError = it.message ?: "Could not install skill." }
                                        reloadSkills()
                                    }
                                }
                            },
                            onUninstallSkill = { name ->
                                val agent = selectedAgentId
                                if (skillApi != null && agent != null) {
                                    chatScope.launch {
                                        runCatching { skillApi.uninstallSkill(agent, name) }
                                            .onFailure { skillsError = it.message ?: "Could not remove skill." }
                                        reloadSkills()
                                    }
                                }
                            },
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
                            PaletteItemKind.Agent -> {
                                chatState.conversations.firstOrNull { it.agentId == item.id }
                                    ?.let { chatController.selectConversation(it.id) }
                                selectedDestination = DesktopDestination.Conversations
                            }
                            PaletteItemKind.Destination ->
                                DesktopDestination.entries.firstOrNull { it.name == item.id }
                                    ?.let { selectedDestination = it }
                        }
                    },
                    onDismiss = { showCommandPalette = false },
                )
            }
            val editingAgentId = editAgentId
            if (editingAgentId != null) {
                EditAgentDialog(
                    agentId = editingAgentId,
                    modelOptions = modelOptions,
                    agentRepository = dataBindings.sessionGraphProvider.current.agentRepository,
                    scope = chatScope,
                    onDismiss = { editAgentId = null },
                    onSaved = {
                        editAgentId = null
                        chatController.retryConnection()
                    },
                )
            }
            if (showNewAgentDialog) {
                NewAgentDialog(
                    modelOptions = modelOptions,
                    onDismiss = { showNewAgentDialog = false },
                    onCreate = { name, modelValue ->
                        showNewAgentDialog = false
                        val template = selectedAgentId?.let {
                            dataBindings.sessionGraphProvider.current.agentRepository.getCachedAgent(it)
                        }
                        val model = modelValue ?: template?.model
                        chatController.createAgent(
                            name = name,
                            model = model,
                            embedding = template?.embedding,
                        )
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
 * Modal for editing an existing agent: name, model, and system prompt, applied
 * via the real agent repository (PATCH /v1/agents/{id}).
 */
@Composable
private fun EditAgentDialog(
    agentId: String,
    modelOptions: List<Pair<String, String>>,
    agentRepository: IAgentRepository,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember(agentId) { mutableStateOf(TextFieldValue("")) }
    var modelValue by remember(agentId) { mutableStateOf<String?>(null) }
    var system by remember(agentId) { mutableStateOf("") }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var loading by remember(agentId) { mutableStateOf(true) }
    var busy by remember(agentId) { mutableStateOf(false) }
    var error by remember(agentId) { mutableStateOf<String?>(null) }

    LaunchedEffect(agentId) {
        val agent = agentRepository.getCachedAgent(agentId)
            ?: runCatching { agentRepository.getAgent(agentId).first() }.getOrNull()
        if (agent != null) {
            name = TextFieldValue(agent.name)
            modelValue = agent.model
            system = agent.system.orEmpty()
        } else {
            error = "Could not load agent"
        }
        loading = false
    }
    val modelLabel = modelOptions.firstOrNull { it.second == modelValue }?.first
        ?: modelValue ?: "Default"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(480.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Edit agent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (loading) {
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Name", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    JewelTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth())

                    Text("Model", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(modelLabel, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (modelMenuOpen) {
                            JewelPopupMenu(onDismissRequest = { modelMenuOpen = false; true }, horizontalAlignment = Alignment.Start) {
                                modelOptions.forEach { (label, value) ->
                                    selectableItem(selected = modelValue == value, onClick = { modelMenuOpen = false; modelValue = value }) {
                                        DesktopControlText(label)
                                    }
                                }
                            }
                        }
                    }

                    Text("System prompt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DesktopTextArea(
                        value = system,
                        onValueChange = { system = it },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        placeholder = "System prompt…",
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    DesktopOutlinedButton(onClick = onDismiss, enabled = !busy) { DesktopButtonContent("Cancel") }
                    DesktopDefaultButton(
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                runCatching {
                                    agentRepository.updateAgent(
                                        AgentId(agentId),
                                        AgentUpdateParams(
                                            name = name.text.trim().takeIf { it.isNotBlank() },
                                            model = modelValue,
                                            system = system.takeIf { it.isNotBlank() },
                                        ),
                                    )
                                }
                                    .onSuccess { onSaved() }
                                    .onFailure { error = it.message ?: "Save failed"; busy = false }
                            }
                        },
                        enabled = !busy && !loading,
                    ) { DesktopButtonContent(if (busy) "Saving…" else "Save") }
                }
            }
        }
    }
}

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

/**
 * Parse a conversation's `updatedAtLabel` (ISO-8601) into a timestamp.
 * Returns null for non-parseable values so callers can fall back to
 * insertion order rather than pinning unparseable entries to MAX.
 */
internal fun parseRailTimestamp(raw: String): java.time.Instant? =
    runCatching { java.time.Instant.parse(raw) }.getOrNull()

/**
 * Group agents by display name into same-name stacks. Within each stack,
 * members are ordered newest-first by their last-updated timestamp; members
 * whose timestamp fails to parse sort last so recent activity always
 * surfaces first. Exposed (internal) for unit tests.
 */
internal fun groupRailAgentsByName(
    agents: List<Triple<String, String, String>>,
): List<AgentRailGroup> =
    agents
        .groupBy { it.second }
        .map { (name, members) ->
            val sorted = members.sortedWith(
                compareByDescending<Triple<String, String, String>> { parseRailTimestamp(it.third) }
                    .thenBy { parseRailTimestamp(it.third) == null }
                    .thenByDescending { it.first }
            )
            AgentRailGroup(name = name, agentIds = sorted.map { it.first })
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
    agents: List<Triple<String, String, String>>,
    selectedAgentId: String?,
    thinkingAgentId: String?,
    onAgentSelected: (String) -> Unit,
    onNewSession: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    // Collapse agents that share a display name — e.g. the many ephemeral
    // "Letta Code" agents spawned per task — into a single stacked orb with a
    // count chip, so the rail doesn't grow unbounded with near-duplicate spawns.
    // Within a stack, the fallback member for clicks is the freshest
    // (most-recent updatedAtLabel) agent, not the first-seen one. parseRailTimestamp
    // returns null for non-parseable labels so genuine pending/undated entries
    // fall back to insertion order.
    val groups = remember(agents) { groupRailAgentsByName(agents) }
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
                            ThinkingRing(diameter = 44.dp, cornerRadius = 10.dp)
                        }
                        AgentOrb(
                            index = index,
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
                                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                                    .padding(horizontal = 3.dp),
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

        RailActionIcon(Icons.Outlined.Search, "Search", onSearch)
        RailActionIcon(Icons.Outlined.Settings, "Settings", onSettings)
        RailActionIcon(Icons.Outlined.AccountCircle, "Account", onSettings)
    }
}

/** A rail entry: one or more agents that share a display name, stacked together. */
internal data class AgentRailGroup(
    val name: String,
    val agentIds: List<String>,
)

@Composable
private fun RailActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    onClick = { onConversationSelected(conversation.id) },
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
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
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
            if (deleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
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
            when {
                deleting -> Text(
                    text = "Deleting…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                // Hover (or an open menu) swaps the timestamp for an overflow menu.
                hovered || menuOpen -> Box {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Conversation menu",
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
                            selectableItem(
                                selected = false,
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            ) {
                                DesktopControlText("Delete chat")
                            }
                        }
                    }
                }
                else -> Text(
                    text = timeLabel,
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

