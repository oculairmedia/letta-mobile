package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.onboarding.OnboardingTaskKind
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.desktop.chat.ChatDetailPane
import com.letta.mobile.desktop.chat.ChatDetailPaneActions
import com.letta.mobile.desktop.chat.ChatDetailPaneState
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksPanel
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksToggle
import com.letta.mobile.desktop.chat.DesktopCommandPalette
import com.letta.mobile.desktop.chat.DesktopModelPickerSheet
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import com.letta.mobile.desktop.agent.DesktopEditAgentSurface
import com.letta.mobile.desktop.agent.agentAvatarStyleKey
import com.letta.mobile.data.commands.AgentSlashCommand
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.awt.Window
import dev.nucleusframework.application.NucleusApplicationScope

@Composable
internal fun LettaDesktopApp(
    nucleusApplicationScope: NucleusApplicationScope,
    window: Window,
    deepLinks: StateFlow<DesktopDeepLinkRequest?>,
    onActiveTitleChange: (String) -> Unit = {},
) {
    var selectedDestination by rememberSaveable { mutableStateOf(DesktopDestination.Conversations) }
    var showNewAgentDialog by remember { mutableStateOf(false) }
    // Avatar styles chosen via the editor this session, applied immediately to the
    // orbs regardless of whether the backend round-trips agent metadata.
    var avatarOverrides by remember { mutableStateOf(emptyMap<String, Int>()) }
    var editAgentId by remember { mutableStateOf<String?>(null) }
    val bootstrap = rememberDesktopConfigBootstrap()
    val secureSettingsStore = bootstrap.secureSettingsStore
    val dataBindings = bootstrap.dataBindings
    val activeConfig = bootstrap.activeConfig
    val bootstrapState = bootstrap.bootstrapState
    val applyConfig = bootstrap.applyConfig
    val chatScope = rememberCoroutineScope()
    val nucleusController = remember(chatScope) { DesktopNucleusController(chatScope) }
    val nucleusState by nucleusController.state.collectAsState()
    val irohTransport = rememberIrohTransport(activeConfig, chatScope)
    val irohMode = irohTransport != null
    val irohAgentDirectory = remember(irohTransport) {
        irohTransport?.let { IrohAdminRpcAgentDirectory(it) }
    }
    SideEffect {
        bootstrap.irohAgentDirectorySlot.value = irohAgentDirectory
    }
    val chatController = rememberDesktopChatController(
        DesktopChatControllerBindings(
            runtime = DesktopChatRuntime(
                bootstrapState = bootstrapState,
                chatScope = chatScope,
                dataBindings = dataBindings,
            ),
            irohTransport = irohTransport,
            irohAgentDirectory = irohAgentDirectory,
            secureSettingsStore = secureSettingsStore,
        ),
    )
    val chatState by chatController.state.collectAsState()
    val availableModels by chatController.availableModels.collectAsState()
    val deletingConversationIds by chatController.deletingConversationIds.collectAsState()
    val modelOptions = remember(availableModels) { buildModelOptions(availableModels) }
    val httpApis = rememberDesktopHttpApis(activeConfig, irohMode, irohAgentDirectory)
    val blockApi = httpApis.blockApi
    val cronPanel = remember(httpApis.cronApi) { DesktopCronPanelState(httpApis.cronApi, chatScope) }
    val skillsPanel = remember(httpApis.skillApi) { DesktopSkillsPanelState(httpApis.skillApi, chatScope) }
    var agentSlashCommands by remember(httpApis.slashCommandApi) { mutableStateOf<List<AgentSlashCommand>>(emptyList()) }
    val subagents = rememberSubagentRegistry(activeConfig, irohMode, chatScope, subagentParentScope(chatState.selectedConversation?.agentId, chatState.selectedConversationId))
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
    DesktopImageIngressEffect(
        DesktopImageIngressConfig(
            enabled = selectedDestination == DesktopDestination.Conversations,
            scope = chatScope,
            loader = imageAttachmentLoader,
            onImage = chatController::attachImage,
            onError = chatController::showComposerError,
        ),
    )

    val avatar = rememberAvatarCompanion(chatScope, secureSettingsStore)

    DesktopControllerLifecycles(
        DesktopControllerLifecycleParams(
            chatController = chatController,
            libraries = libraries,
            selection = DesktopDestinationSelection(
                selectedDestination = selectedDestination,
                selectedConversationAgentId = chatState.selectedConversation?.agentId?.let(::DesktopAgentId),
            ),
            cronPanel = cronPanel,
        ),
    )

    val activeTitle = desktopActiveTitle(selectedDestination, chatState.selectedConversation?.title)
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

    DesktopDeepLinkRouting(
        deepLinks = deepLinks,
        chatState = chatState,
        actions = DesktopDeepLinkRoutingActions(
            // Deep links must win over the full-page agent editor, matching
            // the sidebar navigation paths that clear edit mode before routing.
            onDestinationSelected = {
                editAgentId = null
                selectedDestination = it
            },
            onSelectConversation = {
                editAgentId = null
                chatController.selectConversation(it)
            },
            onOpenAgent = ::openAgent,
        ),
    )
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
            FilterStackConversationsParams(
                conversations = chatState.conversations,
                activeSubagents = activeSubagents,
                selectedAgentName = selectedAgentName,
                selectedConversationId = selectedConversationId,
                archiveFilter = archiveFilter,
            ),
        )
    }
    val mentionables = remember(railAgents, memoryState) {
        buildMentionables(BuildMentionablesParams(railAgents, memoryState))
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

    // Background work can belong to a conversation the user has switched away
    // from; label taskbar/media/notification integration with the agent that
    // is actually working, not the current selection.
    val workingAgentName = workingAgentName(
        WorkingAgentNameParams(
            thinkingAgentId = thinkingAgentId,
            thinkingConversationId = thinkingConversationId,
            railAgents = railAgents,
            conversations = chatState.conversations,
            fallback = selectedAgentName,
        ),
    )
    DesktopNucleusEffects(
        bindings = DesktopNucleusEffectBindings(nucleusApplicationScope, window, nucleusController),
        state = desktopNucleusEffectState(
            DesktopNucleusRuntimeState(
                thinkingConversationId = thinkingConversationId,
                isStreamingReply = replyPresence.isStreaming,
                agentName = workingAgentName,
                errorMessage = chatState.errorMessage,
            ),
        ),
        actions = DesktopNucleusEffectActions(
            onOpenCommandPalette = { showCommandPalette = true },
            // Clear the full-page agent editor like the sidebar and deep-link
            // paths do, or the editor branch keeps rendering over Settings.
            onOpenSettings = {
                editAgentId = null
                selectedDestination = DesktopDestination.Settings
            },
        ),
    )

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
            skillsPanel.reload(selectedAgentId?.let(::DesktopAgentId))
        }
    }
    // Load the focused agent's server slash commands for the composer palette.
    LaunchedEffect(httpApis.slashCommandApi, selectedAgentId) {
        agentSlashCommands = loadAgentSlashCommands(
            httpApis.slashCommandApi,
            selectedAgentId?.let(::DesktopAgentId),
        )
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
                    state = DesktopAgentRailState(
                        agents = railAgents,
                        focus = DesktopAgentRailFocus(
                            selectedAgentId = selectedAgentId,
                            thinkingAgentId = thinkingAgentId,
                            avatarStyleByAgentId = avatarStyleByAgentId,
                        ),
                        avatarCompanionActive = avatar.isActive,
                    ),
                    actions = DesktopAgentRailActions(
                        onAgentSelected = { agentId -> openAgent(agentId) },
                        onNewSession = { showNewAgentDialog = true },
                        onSearch = { showCommandPalette = true },
                        onAvatarCompanion = avatar.toggle,
                    ),
                )
                RailDivider()
                // Agent sidebar: agent header + nav + conversations.
                DesktopAgentSidebar(
                    state = DesktopAgentSidebarState(
                        agentName = selectedAgentName,
                        agentOrbIndex = selectedAgentOrbIndex,
                        conversations = agentConversations,
                        selectedConversationId = chatState.selectedConversationId,
                        thinkingConversationId = thinkingConversationId,
                        deletingConversationIds = deletingConversationIds,
                        archiveFilter = archiveFilter,
                        selectedDestination = selectedDestination,
                        mode = workPlayMode,
                    ),
                    actions = DesktopAgentSidebarActions(
                        onArchiveFilterChange = chatController::setArchiveFilter,
                        onArchiveConversation = chatController::setConversationArchived,
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
                    ),
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
                            BuildComposerCommandsParams(
                                chatController = chatController,
                                agentSlashCommands = agentSlashCommands,
                                onCreateAgent = { showNewAgentDialog = true },
                                onEditAgent = { editAgentId = selectedAgentId },
                                onNavigate = { selectedDestination = it },
                            ),
                        )
                        ChatDetailPane(
                            state = ChatDetailPaneState(
                                surface = chatState,
                                isThinking = isThinkingSelected,
                                isStreamingReply = isStreamingReplySelected,
                                modelOptions = modelOptions,
                                commands = composerCommands,
                                mentionables = mentionables,
                                composerPlaceholder = WorkPlayLens.composerPlaceholder(
                                    workPlayMode,
                                    selectedAgentName,
                                ),
                            ),
                            actions = ChatDetailPaneActions(
                                onComposerTextChanged = chatController::updateComposerText,
                                onSend = chatController::send,
                                onAttachImage = { pickerLauncher.launch() },
                                onRemoveImageAttachment = chatController::removeImageAttachment,
                                onRetryConnection = chatController::retryConnection,
                                onModelSelected = chatController::setConversationModel,
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
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        DestinationContent(
                            destination = selectedDestination,
                            inputs = DestinationContentInputs(
                                state = bootstrapState,
                                memoryState = memoryState,
                                schedule = DestinationScheduleInputs(
                                    scheduleLibraryState = scheduleLibraryState,
                                    crons = cronPanel.crons,
                                    focusedAgentId = selectedAgentId,
                                    // HTTP backends create via /v1/crons; iroh:// uses native
                                    // schedule.create over admin_rpc (CronApi has no HTTP base).
                                    canCreateCron = (cronPanel.available || irohMode) &&
                                        (scheduleLibraryState.selectedAgentId != null ||
                                            selectedAgentId != null),
                                ),
                                channelLibraryState = channelLibraryState,
                                toolLibraryState = toolLibraryState,
                                blockApi = blockApi,
                                skills = DestinationSkillsInputs(
                                    skills = skillsPanel.all,
                                    installedSkillNames = skillsPanel.installedNames,
                                    skillsLoading = skillsPanel.loading,
                                    skillsError = skillsPanel.error,
                                    canManageSkills = skillsPanel.available && selectedAgentId != null,
                                    focusedAgentName = selectedAgentName,
                                ),
                                nucleus = nucleusState,
                            ),
                            actions = DestinationContentActions(
                                memory = DestinationMemoryActions(
                                    onRefresh = libraries.memory::reload,
                                    onAgentSelected = libraries.memory::selectAgent,
                                ),
                                schedules = DestinationScheduleActions(
                                    onRefresh = libraries.schedules::reload,
                                    onAgentSelected = libraries.schedules::selectAgent,
                                    onDeleteCron = { id ->
                                        if (scheduleLibraryState.schedules.any { it.id == id }) {
                                            libraries.schedules.deleteSchedule(id)
                                        } else {
                                            cronPanel.delete(DesktopCronTaskId(id))
                                        }
                                    },
                                    onCreateCron = { filteredAgentId, name, prompt, cron, recurring, tz ->
                                        val targetAgent = filteredAgentId
                                            ?: scheduleLibraryState.selectedAgentId
                                            ?: selectedAgentId
                                        if (targetAgent == null) {
                                            // No agent focused — create UI should already be disabled.
                                        } else if (cronPanel.available) {
                                            cronPanel.create(
                                                CronDraft(
                                                    agentId = DesktopAgentId(targetAgent),
                                                    name = name,
                                                    prompt = prompt,
                                                    cron = cron,
                                                    recurring = recurring,
                                                    timezone = tz,
                                                ),
                                            )
                                        } else {
                                            libraries.schedules.createRecurringSchedule(
                                                agentId = targetAgent,
                                                name = name,
                                                prompt = prompt,
                                                cronExpression = cron,
                                            )
                                        }
                                    },
                                ),
                                onChannelsRefresh = libraries.channels::refresh,
                                tools = DestinationToolsActions(
                                    onRefresh = libraries.tools::reload,
                                    onSearchQueryChanged = libraries.tools::updateSearchQuery,
                                    onTagToggled = libraries.tools::toggleTag,
                                    onClearTags = libraries.tools::clearTags,
                                    onLoadMore = libraries.tools::loadMore,
                                ),
                                skills = DestinationSkillsActions(
                                    onRefresh = {
                                        chatScope.launch {
                                            skillsPanel.reload(selectedAgentId?.let(::DesktopAgentId))
                                        }
                                    },
                                    onInstall = { name ->
                                        skillsPanel.install(
                                            selectedAgentId?.let(::DesktopAgentId),
                                            DesktopSkillName(name),
                                        )
                                    },
                                    onUninstall = { name ->
                                        skillsPanel.uninstall(
                                            selectedAgentId?.let(::DesktopAgentId),
                                            DesktopSkillName(name),
                                        )
                                    },
                                ),
                                onConfigSaved = { applyConfig(it) },
                                onTokenCleared = {
                                    applyConfig(activeConfig.copy(accessToken = null))
                                },
                                onIrohIdentityReset = {
                                    com.letta.mobile.desktop.security.DesktopIrohIdentity.reset()
                                    // Rebuild the session graph so the next dial
                                    // mints and uses the new identity.
                                    applyConfig(activeConfig)
                                },
                                nucleus = destinationNucleusActions(nucleusController, window),
                            ),
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
                    NewAgentDialogParams(
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
                    ),
                )
            }
          }
        }
    }
}

private data class DesktopDeepLinkRoutingActions(
    val onDestinationSelected: (DesktopDestination) -> Unit,
    val onSelectConversation: (String) -> Unit,
    val onOpenAgent: (String) -> Unit,
)

/**
 * Routes deep links into the app, buffering targets that arrive before the
 * conversation list has loaded (cold-start protocol activation):
 * selectConversation ignores unknown ids and openAgent would treat the empty
 * list as "no existing chat", so both wait for the initial load to settle.
 */
@Composable
private fun DesktopDeepLinkRouting(
    deepLinks: StateFlow<DesktopDeepLinkRequest?>,
    chatState: DesktopChatSurfaceState,
    actions: DesktopDeepLinkRoutingActions,
) {
    var pendingConversationId by remember { mutableStateOf<String?>(null) }
    var pendingAgentId by remember { mutableStateOf<String?>(null) }
    DesktopDeepLinkEffect(
        deepLinks = deepLinks,
        onDestinationSelected = actions.onDestinationSelected,
        onConversationSelected = { pendingConversationId = it },
        onAgentSelected = { pendingAgentId = it },
    )
    LaunchedEffect(pendingConversationId, chatState.conversations) {
        val target = pendingConversationId ?: return@LaunchedEffect
        if (chatState.conversations.any { it.id == target }) {
            actions.onSelectConversation(target)
            pendingConversationId = null
        }
    }
    LaunchedEffect(pendingAgentId, chatState.isLoading, chatState.connectionState) {
        val target = pendingAgentId ?: return@LaunchedEffect
        if (initialConversationLoadSettled(chatState)) {
            actions.onOpenAgent(target)
            pendingAgentId = null
        }
    }
}

private fun initialConversationLoadSettled(chatState: DesktopChatSurfaceState): Boolean {
    if (chatState.isLoading) return false
    return chatState.connectionState != DesktopChatConnectionState.Loading
}

private data class WorkingAgentNameParams(
    val thinkingAgentId: String?,
    val thinkingConversationId: String?,
    val railAgents: List<Pair<String, String>>,
    val conversations: List<DesktopConversationSummary>,
    val fallback: String,
)

private fun workingAgentName(params: WorkingAgentNameParams): String {
    val byAgent = params.thinkingAgentId?.let { id ->
        params.railAgents.firstOrNull { it.first == id }?.second
    }
    if (byAgent != null) return byAgent
    val byConversation = params.thinkingConversationId?.let { tid ->
        params.conversations.firstOrNull { it.id == tid }?.agentName
    }
    return byConversation ?: params.fallback
}

private fun desktopActiveTitle(destination: DesktopDestination, conversationTitle: String?): String {
    if (destination != DesktopDestination.Conversations) return destination.label
    return conversationTitle ?: "Letta Desktop"
}

/**
 * Modal for creating a new agent: name + optional model, created with base
 * tools and default memory blocks (model/embedding default to the active
 * agent's config so the new agent is valid for this backend).
 */
