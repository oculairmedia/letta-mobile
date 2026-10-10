package com.letta.mobile.desktop

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.desktop.canvas.DesktopCanvasOwner
import com.letta.mobile.desktop.chat.ChatDetailPaneActions
import com.letta.mobile.desktop.chat.ChatDetailPaneState
import com.letta.mobile.desktop.chat.ComposerCommand
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksToggle
import com.letta.mobile.desktop.chat.DesktopChatComposerHostInputs
import com.letta.mobile.desktop.chat.DesktopChatSessionPort
import com.letta.mobile.desktop.chat.DesktopContextFocus
import com.letta.mobile.desktop.chat.DesktopContextWindowSources
import com.letta.mobile.desktop.chat.DesktopSharedChatPage
import com.letta.mobile.desktop.chat.DesktopSharedChatPageNavigation
import com.letta.mobile.desktop.chat.DesktopSharedChatPageState
import com.letta.mobile.desktop.chat.rememberFocusedContextUsage
import com.letta.mobile.desktop.home.DesktopHome
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.ui.shell.pages.home.HomePageNavigation

/** What the chat surfaces in the main pane share: the composer's commands and context reading, and the canvas. */
private data class DesktopShellChatHost(
    val composerCommands: List<ComposerCommand>,
    val contextUsage: ContextWindowUsageState,
    val openConversationCanvas: () -> Unit,
    val agentNamesById: Map<String, String>,
    val canSubmitApprovals: Boolean,
)

/** The shared KMP chat page's own inputs: its session port, and the host it shares with the old page. */
private data class DesktopShellSharedPage(
    val port: DesktopChatSessionPort,
    val host: DesktopShellChatHost,
)

/** The conversation (or the chosen destination), with the agent editor or a canvas beside it. */
@Composable
internal fun RowScope.DesktopShellMainPane(context: DesktopShellContext, frame: DesktopShellFrame) {
    val host = rememberDesktopShellChatHost(context, frame)
    DesktopMainContentPane(
        inputs = desktopMainContentInputs(context, frame, host),
        actions = desktopMainContentActions(context, frame, host),
        modifier = Modifier.weight(1f).fillMaxHeight(),
    )
}

@Composable
private fun rememberDesktopShellChatHost(context: DesktopShellContext, frame: DesktopShellFrame): DesktopShellChatHost {
    val core = context.core
    val navigator = context.navigator
    val focus = frame.focus
    val selectedConversationId = frame.chatState.selectedConversationId
    val agentSlashCommands by context.panels.agentSlashCommands
    val canSubmitApprovals by core.chatController.canSubmitApprovals.collectAsState()
    val showDockedCanvas = context.showDockedCanvasAction()
    val composerCommands = rememberDesktopComposerCommands(
        DesktopComposerCommandsParams(
            chatController = core.chatController,
            agentSlashCommands = agentSlashCommands,
            selectedConversationId = selectedConversationId,
            selectedAgentId = focus.selectedAgentId,
            selectedAgentName = focus.selectedAgentName,
            selectedDestination = navigator.selectedDestination,
            canvasStore = core.canvasShell.store,
            chatScope = core.chatScope,
            onNavigate = { navigator.selectedDestination = it },
            onCreateAgent = { context.overlays.newAgent = true },
            onEditAgent = { navigator.editAgentId = it },
            onCanvasSessionChange = { core.canvasShell.activeSession = it },
            showDockedCanvas = showDockedCanvas,
        ),
    )
    val rosterAgents = focus.rosterAgents
    val contextUsage = rememberShellContextUsage(context, frame)
    return DesktopShellChatHost(
        composerCommands = composerCommands,
        contextUsage = contextUsage,
        openConversationCanvas = showDockedCanvas ?: { core.canvasShell.openForConversation(frame.conversationCanvasOwner()) },
        agentNamesById = remember(rosterAgents) { rosterAgents.associate { it.id.value to it.name } },
        canSubmitApprovals = canSubmitApprovals,
    )
}

/** letta-mobile-r2zo8: the composer chip's reading for the focused conversation. */
@Composable
private fun rememberShellContextUsage(context: DesktopShellContext, frame: DesktopShellFrame): ContextWindowUsageState {
    val core = context.core
    val sessionGraph = core.sessionGraph.value
    val models by sessionGraph.modelRepository.llmModels.collectAsState()
    val modelSelections by core.chatController.conversationModelSelections.collectAsState()
    return rememberFocusedContextUsage(
        focus = DesktopContextFocus(
            agentId = frame.focus.selectedAgentId,
            conversationId = frame.chatState.selectedConversationId,
            settled = !frame.activity.isThinkingSelected && !frame.activity.isStreamingReplySelected,
        ),
        readings = sessionGraph.contextTokenReadings,
        window = DesktopContextWindowSources(frame.focus.rosterAgents, models, modelSelections),
    )
}

private fun DesktopShellFrame.conversationCanvasOwner(): DesktopCanvasOwner {
    return DesktopCanvasOwner(chatState.selectedConversationId, focus.selectedAgentId, focus.selectedAgentName)
}

@Composable
private fun desktopMainContentInputs(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    host: DesktopShellChatHost,
): DesktopMainContentInputs {
    val core = context.core
    val panels = context.panels
    val navigator = context.navigator
    return DesktopMainContentInputs(
        editingAgentId = navigator.editAgentId,
        selectedDestination = navigator.selectedDestination,
        modelOptions = frame.lists.modelOptions,
        agentRepository = core.bootstrap.dataBindings.sessionGraphProvider.current.agentRepository,
        blockApi = panels.httpApis.blockApi,
        secureSettingsStore = core.bootstrap.secureSettingsStore,
        chatScope = core.chatScope,
        chatDetailState = chatDetailPaneState(context, frame, host),
        destinationInputs = destinationContentInputs(context, frame),
        showBackgroundTasks = navigator.showBackgroundTasks,
        subagentRepository = panels.subagents.repository,
        activeSubagents = frame.lists.activeSubagents,
        activeCanvasSession = core.canvasShell.activeSession,
        dockedCanvasId = context.sharedChat.dockedCanvas?.dockedCanvasId,
        sharedChatPage = desktopSharedChatPage(context, frame, host),
    )
}

@Composable
private fun chatDetailPaneState(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    host: DesktopShellChatHost,
): ChatDetailPaneState {
    val chatController = context.core.chatController
    val canonicalPresentation by chatController.canonicalPresentation.collectAsState()
    val canonicalStatus by chatController.canonicalStatus.collectAsState()
    val submittingApprovals by chatController.submittingApprovals.collectAsState()
    // letta-mobile folder-settings #2: the selected conversation's working directory.
    val workingDirectory by chatController.selectedConversationWorkingDirectory.collectAsState()
    val workingDirectoryLoading by chatController.workingDirectoryLoading.collectAsState()
    val focus = frame.focus
    return ChatDetailPaneState(
        surface = frame.chatState,
        canonicalPresentation = canonicalPresentation,
        canonicalStatus = canonicalStatus,
        contextUsage = host.contextUsage,
        isThinking = frame.activity.isThinkingSelected,
        isStreamingReply = frame.activity.isStreamingReplySelected,
        modelOptions = frame.lists.modelOptions,
        commands = host.composerCommands,
        mentionables = frame.lists.mentionables,
        composerPlaceholder = WorkPlayLens.composerPlaceholder(
            context.navigator.workPlayMode,
            focus.selectedAgentName,
        ),
        submittingApprovalRequestIds = submittingApprovals,
        agentNamesById = host.agentNamesById,
        agentIdentitiesById = focus.identityByAgentId,
        workingDirectory = workingDirectory,
        workingDirectorySupported = chatController.supportsWorkingDirectory,
        workingDirectoryLoading = workingDirectoryLoading,
    )
}

@Composable
private fun destinationContentInputs(context: DesktopShellContext, frame: DesktopShellFrame): DestinationContentInputs {
    val core = context.core
    val panels = context.panels
    val skillsPanel = panels.skillsPanel
    val libraries = frame.libraries
    val focus = frame.focus
    val nucleusState by core.nucleusController.state.collectAsState()
    return DestinationContentInputs(
        railRecencyDays = core.railPrefs.recencyDays,
        state = core.bootstrap.bootstrapState,
        home = rememberDesktopHomeInputs(context, frame),
        chat = frame.chatState,
        memoryState = libraries.memory,
        schedule = DestinationScheduleInputs(
            scheduleLibraryState = libraries.schedules,
            crons = panels.cronPanel.crons,
            focusedAgentId = focus.selectedAgentId,
            canCreateCron = canCreateCron(context, libraries.schedules, focus),
        ),
        channels = libraries.channels,
        toolLibraryState = libraries.tools,
        skills = DestinationSkillsInputs(
            skills = skillsPanel.all,
            installedSkillNames = skillsPanel.installedNames,
            skillsLoading = skillsPanel.loading,
            skillsError = skillsPanel.error,
            canManageSkills = skillsPanel.available && focus.selectedAgentId != null,
            focusedAgentName = focus.selectedAgentName,
        ),
        nucleus = nucleusState,
        localRuntimeProvider = core.localConfig.providerState,
        localBackendDirectory = core.localConfig.directoryState,
        localRuntime = core.localConfig.runtime,
    )
}

/** A schedule can be created when the backend takes them (cron API or Iroh) and an agent is chosen. */
private fun canCreateCron(
    context: DesktopShellContext,
    scheduleState: DesktopScheduleLibraryState,
    focus: DesktopAgentFocus,
): Boolean {
    val backendSchedules = context.panels.cronPanel.available || context.core.iroh.transport != null
    val agentChosen = scheduleState.selectedAgentId != null || focus.selectedAgentId != null
    return backendSchedules && agentChosen
}

private fun desktopMainContentActions(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    host: DesktopShellChatHost,
): DesktopMainContentActions {
    val chatController = context.core.chatController
    val canvasShell = context.core.canvasShell
    val navigator = context.navigator
    return DesktopMainContentActions(
        onEditAgentClose = { navigator.editAgentId = null },
        onEditAgentSaved = { identity, nameChanged ->
            navigator.recordSavedIdentity(identity)
            if (nameChanged) chatController.retryConnection()
        },
        onCloseCanvas = canvasShell::close,
        onShareCanvasToChat = { bytes, mimeType ->
            handleDesktopShareCanvasToChat(bytes, mimeType, chatController) {
                navigator.selectedDestination = DesktopDestination.Conversations
                canvasShell.close()
            }
        },
        chatDetailActions = desktopChatDetailActions(context, frame, host),
        destinationActions = desktopDestinationActions(context, frame),
        onShowBackgroundTasks = { navigator.showBackgroundTasks = true },
    )
}

private fun desktopChatDetailActions(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    host: DesktopShellChatHost,
): ChatDetailPaneActions {
    val navigator = context.navigator
    val selectedAgentId = frame.focus.selectedAgentId
    return createDesktopChatDetailPaneActions(
        CreateDesktopChatDetailPaneActionsParams(
            chatController = context.core.chatController,
            canSubmitApprovals = host.canSubmitApprovals,
            onA2uiAction = context.onA2uiAction,
            onAttachImage = context.imageIntake.launchPicker,
            onOpenCanvas = { host.openConversationCanvas() },
            onOpenModelPicker = { context.overlays.modelPicker = true },
            onSetPersona = { navigator.editAgentId = selectedAgentId },
            onNavigateToChannels = { navigator.selectedDestination = DesktopDestination.Channels },
            onNavigateToAgents = { navigator.selectedDestination = DesktopDestination.Agents },
            onOpenAgent = { context.router.openAgent(AgentId(it)) },
            onEditAgent = { navigator.editAgentId = selectedAgentId },
            onOpenAgentPane = { context.openAgentPane() },
        ),
    )
}

private fun desktopDestinationActions(context: DesktopShellContext, frame: DesktopShellFrame): DestinationContentActions {
    val core = context.core
    val panels = context.panels
    val controllers = panels.libraries
    val bootstrap = core.bootstrap
    val selectedAgentId = frame.focus.selectedAgentId
    return DestinationContentActions(
        onRailRecencyDaysChange = core.railPrefs::updateRecencyDays,
        onRetryConnection = core.chatController::retryConnection,
        home = desktopHomeCallbacks(context, frame),
        memory = controllers.memory,
        memfs = controllers.memfs,
        schedules = destinationScheduleActions(
            ScheduleWiringDeps(
                schedules = controllers.schedules,
                cronPanel = panels.cronPanel,
                scheduleLibraryState = frame.libraries.schedules,
                selectedAgentId = selectedAgentId,
            ),
        ),
        channels = controllers.channels,
        tools = DestinationToolsActions(
            onRefresh = controllers.tools::reload,
            onSearchQueryChanged = controllers.tools::updateSearchQuery,
            onTagToggled = controllers.tools::toggleTag,
            onClearTags = controllers.tools::clearTags,
            onLoadMore = controllers.tools::loadMore,
        ),
        skills = destinationSkillsActions(
            skillsPanel = panels.skillsPanel,
            chatScope = core.chatScope,
            selectedAgentId = selectedAgentId,
        ),
        onConfigSaved = { bootstrap.applyConfig(it) },
        onTokenCleared = { bootstrap.applyConfig(bootstrap.activeConfig.copy(accessToken = null)) },
        onIrohIdentityReset = { context.overlays.irohResetConfirm = true },
        nucleus = destinationNucleusActions(core.nucleusController, context.shell.window),
        localRuntimeProvider = core.localConfig.providerActions,
        localBackendDirectory = core.localConfig.directoryActions,
    )
}

private fun desktopHomeCallbacks(context: DesktopShellContext, frame: DesktopShellFrame): HomePageCallbacks {
    val navigator = context.navigator
    val router = context.router
    val focus = frame.focus
    val openConversation = { id: String -> router.openConversation(ConversationId(id)) }
    return HomePageCallbacks(
        actions = context.panels.libraries.home,
        navigation = HomePageNavigation(
            onSubmitPrompt = { text ->
                val rosterAgentId = focus.rosterAgents.firstOrNull()?.id?.value
                router.submitHomePrompt(DesktopHomePrompt(text, focus.selectedAgentId, rosterAgentId))
            },
            onOpenConversation = { openConversation(it.conversationId) },
            onOpenAgent = { router.openAgent(AgentId(it)) },
            onOpenShortcut = { shortcut -> DesktopHome.destinationFor(shortcut)?.let(navigator::navigate) },
            onConfigureAgent = { navigator.editAgentId = it },
            onOpenMessage = { message -> message.conversationId?.let(openConversation) },
            onOpenTool = { navigator.navigate(DesktopDestination.Agents) },
            onOpenBlock = { navigator.navigate(DesktopDestination.Memory) },
            onA2uiAction = context.onA2uiAction,
        ),
    )
}

/** letta-mobile-bglj6.1: the shared KMP chat page, built only while its preview flag is on. */
private fun desktopSharedChatPage(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    host: DesktopShellChatHost,
): (@Composable (Modifier) -> Unit)? {
    val port = context.sharedChat.port ?: return null
    val page = DesktopShellSharedPage(port, host)
    return { pageModifier -> DesktopShellSharedChatPage(page, context, frame, pageModifier) }
}

@Composable
private fun DesktopShellSharedChatPage(
    page: DesktopShellSharedPage,
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    modifier: Modifier,
) {
    val navigator = context.navigator
    val focus = frame.focus
    val canonicalPresentation by context.core.chatController.canonicalPresentation.collectAsState()
    DesktopSharedChatPage(
        state = DesktopSharedChatPageState(
            port = page.port,
            pagedTimeline = canonicalPresentation,
            hostInputs = DesktopChatComposerHostInputs(
                commands = page.host.composerCommands,
                mentionables = frame.lists.mentionables,
                contextUsage = page.host.contextUsage,
                placeholder = WorkPlayLens.composerPlaceholder(navigator.workPlayMode, focus.selectedAgentName),
            ),
            errorMessage = frame.chatState.errorMessage,
            canvasStore = context.core.canvasShell.store,
            canvasOwner = frame.conversationCanvasOwner(),
            dockGeometry = context.panels.chatDockGeometry,
            canvasHeaderTrailing = backgroundTasksHeaderToggle(context, frame),
            dockedCanvas = context.sharedChat.dockedCanvas,
        ),
        navigation = DesktopSharedChatPageNavigation(
            openCanvas = { page.host.openConversationCanvas() },
            openAgent = { context.router.openAgent(AgentId(it)) },
            openModelPicker = { context.overlays.modelPicker = true },
            // As on the old page: the companion mascot brings the agent pane
            // back and leaves any editor; its pencil opens the editor.
            openAgentPane = { context.openAgentPane() },
            editAgent = { navigator.editAgentId = focus.selectedAgentId },
            agentNamesById = page.host.agentNamesById,
            recentInteractions = rememberDesktopRecentInteractions(context, frame),
        ),
        modifier = modifier,
    )
}

/**
 * The background-tasks toggle in the shared page's canvas header (the old page draws it over the
 * conversation instead): only while the tasks pane is closed and there is a repository to list.
 */
private fun backgroundTasksHeaderToggle(context: DesktopShellContext, frame: DesktopShellFrame): (@Composable () -> Unit)? {
    val navigator = context.navigator
    if (navigator.showBackgroundTasks || context.panels.subagents.repository == null) return null
    val activeSubagents = frame.lists.activeSubagents
    return {
        DesktopBackgroundTasksToggle(
            runningCount = activeSubagents.count { it.status == SubagentStatus.RUNNING },
            onClick = { navigator.showBackgroundTasks = true },
            inBar = true,
        )
    }
}
