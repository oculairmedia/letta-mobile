package com.letta.mobile.desktop

import com.letta.mobile.data.session.llmModelsOrEmpty
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
import com.letta.mobile.data.subagents.visibleRunningCount
import com.letta.mobile.desktop.agent.agentEditorHost
import com.letta.mobile.ui.shell.workspace.ShellCanvasOwner
import com.letta.mobile.ui.chat.session.ChatHostCommand
import com.letta.mobile.ui.chat.session.ChatComposerHostInputs
import com.letta.mobile.desktop.chat.DesktopChatSubagents
import com.letta.mobile.desktop.chat.desktopSubagentTimelineRoute
import com.letta.mobile.desktop.chat.DesktopChatSessionPort
import com.letta.mobile.desktop.chat.DesktopContextFocus
import com.letta.mobile.desktop.chat.DesktopContextWindowSources
import com.letta.mobile.desktop.chat.DesktopSharedChatPage
import com.letta.mobile.desktop.chat.DesktopSharedChatPageNavigation
import com.letta.mobile.desktop.chat.DesktopSharedChatPageState
import com.letta.mobile.desktop.chat.rememberFocusedContextUsage
import com.letta.mobile.ui.chat.subagents.BackgroundTasksToggle
import com.letta.mobile.ui.chat.subagents.SubagentDualViewBinding
import com.letta.mobile.ui.chat.subagents.SubagentTodoLoader
import com.letta.mobile.ui.chat.session.TimelineSubagentViewSessions
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.ShellHostPages
import com.letta.mobile.ui.shell.pages.ShellPageFocus
import com.letta.mobile.ui.shell.pages.ShellPageHost
import com.letta.mobile.ui.shell.pages.rememberShellPageDeps
import com.letta.mobile.ui.shell.sidebar.ShellRenameChatDialog

/** What the shared chat page takes from the shell: the composer's commands and context reading, and the canvas. */
private data class DesktopShellChatHost(
    val composerCommands: List<ChatHostCommand>,
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
        actions = desktopMainContentActions(context),
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
            onCreateAgent = navigator::openNewAgent,
            onEditAgent = { navigator.editAgentId = it?.let(::AgentId) },
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
    val models by remember(sessionGraph) { sessionGraph.llmModelsOrEmpty() }.collectAsState()
    val modelSelections by core.chatController.models.selections.collectAsState()
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

private fun DesktopShellFrame.conversationCanvasOwner(): ShellCanvasOwner {
    return ShellCanvasOwner(chatState.selectedConversationId, focus.selectedAgentId, focus.selectedAgentName)
}

@Composable
private fun desktopMainContentInputs(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    host: DesktopShellChatHost,
): DesktopMainContentInputs {
    val core = context.core
    val navigator = context.navigator
    return DesktopMainContentInputs(
        agentEditor = navigator.agentEditor,
        selectedDestination = navigator.selectedDestination,
        pageDestination = navigator.pageFor(frame),
        selectedConversationId = frame.chatState.selectedConversationId,
        pages = rememberShellPageDeps(core.sessionGraph.value, context.panels.libraries, desktopPageHost(context, frame, host)),
        activeCanvasSession = core.canvasShell.activeSession,
        dockedCanvasId = context.sharedChat.dockedCanvas.dockedCanvasId,
    )
}

/** letta-mobile-ckmub.17.5: what the desktop adds to the shared shell pages: its chat, Settings, plugins and editor extras. */
@Composable
private fun desktopPageHost(context: DesktopShellContext, frame: DesktopShellFrame, host: DesktopShellChatHost): ShellPageHost {
    val core = context.core
    val chat = desktopSharedChatPage(context, frame, host)
    val focus = frame.focus
    return ShellPageHost(
        navigator = context.router,
        focus = ShellPageFocus(focus.selectedAgentId?.let(::AgentId), focus.selectedAgentName),
        pages = ShellHostPages(
            chat = ShellPageContent { _, modifier -> chat(modifier) },
            settings = desktopSettingsPage(desktopSettingsInputs(context, frame), desktopSettingsActions(context)),
            plugins = desktopPluginsPage(core.bootstrap.dataBindings.pluginViews),
            slots = DesktopChannelsPageSlots,
        ),
        home = rememberDesktopHomeHost(context, frame),
        agentEditor = context.agentEditorHost(),
        modelControl = core.bootstrap.dataBindings.modelControl,
        settings = core.bootstrap.secureSettingsStore,
    )
}

@Composable
private fun desktopSettingsInputs(context: DesktopShellContext, frame: DesktopShellFrame): DesktopSettingsInputs {
    val core = context.core
    val nucleusState by core.nucleusController.state.collectAsState()
    return DesktopSettingsInputs(
        railRecencyDays = core.railPrefs.recencyDays,
        state = core.bootstrap.bootstrapState,
        nucleus = nucleusState,
        localRuntimeProvider = core.localConfig.providerState,
        localBackendDirectory = core.localConfig.directoryState,
        pairing = com.letta.mobile.desktop.qr.desktopInstallAndPairBindings(context, frame.focus),
        localRuntime = core.localConfig.runtime,
    )
}

private fun desktopMainContentActions(context: DesktopShellContext): DesktopMainContentActions {
    val chatController = context.core.chatController
    val canvasShell = context.core.canvasShell
    val navigator = context.navigator
    return DesktopMainContentActions(
        onEditAgentClose = { navigator.editAgentId = null },
        onCloseCanvas = canvasShell::close,
        onShareCanvasToChat = { bytes, mimeType ->
            handleDesktopShareCanvasToChat(bytes, mimeType, chatController) {
                navigator.selectedDestination = DesktopDestination.Conversations
                canvasShell.close()
            }
        },
    )
}

private fun desktopSettingsActions(context: DesktopShellContext): DesktopSettingsActions {
    val core = context.core
    val bootstrap = core.bootstrap
    return DesktopSettingsActions(
        onRailRecencyDaysChange = core.railPrefs::updateRecencyDays,
        onOpenProviders = { context.navigator.navigate(ShellDestination.Providers) },
        onOpenPlugins = { context.navigator.navigate(ShellDestination.Plugins) },
        pluginViews = bootstrap.dataBindings.pluginViews,
        onConfigSaved = { bootstrap.applyConfig(it) },
        onTokenCleared = { bootstrap.applyConfig(bootstrap.activeConfig.copy(accessToken = null)) },
        onIrohIdentityReset = { context.overlays.irohResetConfirm = true },
        nucleus = destinationNucleusActions(core.nucleusController, context.shell.window),
        localRuntimeProvider = core.localConfig.providerActions,
        localBackendDirectory = core.localConfig.directoryActions,
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
            hostInputs = ChatComposerHostInputs(
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
            subagents = rememberDesktopChatSubagents(context, frame),
        ),
        navigation = DesktopSharedChatPageNavigation(
            openCanvas = { page.host.openConversationCanvas() },
            openAgent = { context.router.openAgent(AgentId(it)) },
            openModelPicker = { context.overlays.modelPicker = true },
            // The companion mascot brings the agent pane back and leaves any
            // editor; its pencil opens the editor.
            openAgentPane = { context.openAgentPane() },
            editAgent = { navigator.editAgentId = focus.selectedAgentId?.let(::AgentId) },
            agentNamesById = page.host.agentNamesById,
            recentInteractions = rememberDesktopRecentInteractions(context, frame),
        ),
        modifier = modifier,
    )
}

/**
 * The conversation's subagents for the shared rings over the full-screen chat (letta-mobile-ckmub.3.19):
 * a ring's todos come from the registry; none without one.
 */
@Composable
private fun rememberDesktopChatSubagents(context: DesktopShellContext, frame: DesktopShellFrame): DesktopChatSubagents? {
    val repository = context.panels.subagents.repository
    val loader = remember(repository) { repository?.let { repo -> SubagentTodoLoader { target -> repo.todos(target.toolCallId) } } }
    val views = rememberDesktopSubagentViews(context)
    return loader?.let { DesktopChatSubagents(frame.lists.activeSubagents, it, views) }
}

/**
 * letta-mobile-ulcg6.1: the chat's subagent dual view, each child conversation read over the active
 * gateway the way a selected conversation is.
 */
@Composable
private fun rememberDesktopSubagentViews(context: DesktopShellContext): SubagentDualViewBinding {
    val controller = context.core.chatController
    val holder = context.panels.subagents.views
    return remember(controller, holder) {
        val sessions = TimelineSubagentViewSessions { view -> controller.activeGateway?.let { desktopSubagentTimelineRoute(it, view) } }
        SubagentDualViewBinding(holder, sessions)
    }
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
