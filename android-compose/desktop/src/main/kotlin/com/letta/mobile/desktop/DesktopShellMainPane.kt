package com.letta.mobile.desktop

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.data.subagents.visibleRunningCount
import com.letta.mobile.desktop.canvas.DesktopCanvasOwner
import com.letta.mobile.desktop.chat.ComposerCommand
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
import com.letta.mobile.ui.chat.subagents.BackgroundTasksToggle
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.ui.shell.pages.home.HomePageNavigation
import com.letta.mobile.ui.shell.sidebar.ShellRenameChatDialog

/** What the shared chat page takes from the shell: the composer's commands and context reading, and the canvas. */
private data class DesktopShellChatHost(
    val composerCommands: List<ComposerCommand>,
    val contextUsage: ContextWindowUsageState,
    val openConversationCanvas: () -> Unit,
    val agentNamesById: Map<String, String>,
)

/** The shared KMP chat page's own inputs: its session port, and the shell host. */
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
        actions = desktopMainContentActions(context, frame),
        modifier = Modifier.weight(1f).fillMaxHeight(),
    )
    DesktopRenameChatOverlay(context, frame)
}

/** letta-mobile-zxk2n.5: `/rename` for the open chat, written through the shared rename path. */
@Composable
private fun DesktopRenameChatOverlay(context: DesktopShellContext, frame: DesktopShellFrame) {
    if (!context.overlays.renameChat) return
    val selectedId = frame.chatState.selectedConversationId
    val conversation = frame.chatState.conversations.firstOrNull { it.id == selectedId }
    if (conversation == null) {
        context.overlays.renameChat = false
        return
    }
    ShellRenameChatDialog(
        title = conversation.displayTitle(),
        onRename = { title ->
            context.core.chatController.conversationManagement.rename(
                ConversationSummaryUpdate(ConversationId(conversation.id), ConversationSummary(title)),
            )
        },
        onDismiss = { context.overlays.renameChat = false },
    )
}

@Composable
private fun rememberDesktopShellChatHost(context: DesktopShellContext, frame: DesktopShellFrame): DesktopShellChatHost {
    val core = context.core
    val navigator = context.navigator
    val focus = frame.focus
    val selectedConversationId = frame.chatState.selectedConversationId
    val agentSlashCommands by context.panels.agentSlashCommands
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
            client = DesktopClientCommandRoutes(
                onSearch = { context.overlays.commandPalette = true },
                onOpenModelPicker = { context.overlays.modelPicker = true },
                onRenameChat = { context.overlays.renameChat = true },
                onNewChat = { context.openNewChatForFocusedAgent(focus) },
            ),
        ),
    )
    val rosterAgents = focus.rosterAgents
    val contextUsage = rememberShellContextUsage(context, frame)
    return DesktopShellChatHost(
        composerCommands = composerCommands,
        contextUsage = contextUsage,
        openConversationCanvas = showDockedCanvas,
        agentNamesById = remember(rosterAgents) { rosterAgents.associate { it.id.value to it.name } },
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
        pageDestination = navigator.currentRoute(frame.chatState.selectedConversationId, frame.focus.selectedAgentId).page.toShellDestination(),
        modelOptions = frame.lists.modelOptions,
        agentRepository = core.bootstrap.dataBindings.sessionGraphProvider.current.agentRepository,
        blockApi = panels.httpApis.blockApi,
        secureSettingsStore = core.bootstrap.secureSettingsStore,
        chatScope = core.chatScope,
        selectedConversationId = frame.chatState.selectedConversationId,
        destinationInputs = destinationContentInputs(context, frame),
        subagentRepository = panels.subagents.repository,
        activeCanvasSession = core.canvasShell.activeSession,
        dockedCanvasId = context.sharedChat.dockedCanvas.dockedCanvasId,
        chatPage = desktopSharedChatPage(context, frame, host),
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
        pairing = com.letta.mobile.desktop.qr.desktopInstallAndPairBindings(context, focus),
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

private fun desktopMainContentActions(context: DesktopShellContext, frame: DesktopShellFrame): DesktopMainContentActions {
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
        destinationActions = desktopDestinationActions(context, frame),
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
            onOpenTool = { navigator.navigate(ShellDestination.Skills(section = ShellSkillsSection.Tools)) },
            onOpenBlock = { navigator.navigate(DesktopDestination.Memory) },
            onA2uiAction = context.onA2uiAction,
        ),
    )
}

/** letta-mobile-ckmub.3.11: the shared KMP chat page, desktop's only chat. */
private fun desktopSharedChatPage(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    host: DesktopShellChatHost,
): @Composable (Modifier) -> Unit {
    val page = DesktopShellSharedPage(context.sharedChat.port, host)
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
            // The companion mascot brings the agent pane back and leaves any
            // editor; its pencil opens the editor.
            openAgentPane = { context.openAgentPane() },
            editAgent = { navigator.editAgentId = focus.selectedAgentId },
            agentNamesById = page.host.agentNamesById,
            recentInteractions = rememberDesktopRecentInteractions(context, frame),
        ),
        modifier = modifier,
    )
}

/**
 * The background-tasks toggle in the shared page's canvas header: only while the tasks pane is
 * closed and there is a repository to list.
 */
private fun backgroundTasksHeaderToggle(context: DesktopShellContext, frame: DesktopShellFrame): (@Composable () -> Unit)? {
    val navigator = context.navigator
    if (navigator.showBackgroundTasks || context.panels.subagents.repository == null) return null
    val activeSubagents = frame.lists.activeSubagents
    return {
        BackgroundTasksToggle(
            runningCount = activeSubagents.visibleRunningCount(),
            onClick = { navigator.showBackgroundTasks = true },
            inBar = true,
        )
    }
}
