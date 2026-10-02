package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.canvas.CanvasLibrary
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.desktop.canvas.toCanvasArchiveFilter
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksSidePane
import com.letta.mobile.desktop.security.DesktopIrohIdentity
import com.letta.mobile.ui.mascot.MascotTransportLayer

/** The window's surface: the rail, the agent sidebar and the main pane, with the overlays over them. */
@Composable
internal fun DesktopShellWindowContent(context: DesktopShellContext, frame: DesktopShellFrame) {
    val navigator = context.navigator
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .railLightDismiss(navigator.railExpanded) { navigator.railExpanded = false },
            ) {
                DesktopShellLayoutBody(context, frame)
                DesktopShellOverlays(context, frame)
            }
        }
    }
}

@Composable
private fun DesktopShellLayoutBody(context: DesktopShellContext, frame: DesktopShellFrame) {
    val layout = context.core.layout
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Responsive shell: below the breakpoint (or when the user
        // explicitly collapses it) the capability/history sidebar is
        // fully removed - not shrunk to an icon rail - and the chat pane
        // takes the full width. The breakpoint/collapse decision itself
        // lives in sharedLogic's ShellLayoutReducer; this just reports
        // the measured width into it. The 56dp agent rail always stays as
        // the navigation affordance.
        val measuredWidthDp = maxWidth.value
        val isSidebarVisible = layout.controller.state.isSidebarVisible &&
            !ShellLayoutReducer.defaultCollapsedForWidth(measuredWidthDp)
        // One rule for where the mascot stands (wbin4.4), driven from the shell's own state.
        DriveMascotStage(frame.focus.selectedAgentId, agentPaneVisible = isSidebarVisible)
        LaunchedEffect(measuredWidthDp) {
            layout.controller.dispatch(ShellLayoutEvent.WindowWidthChanged(measuredWidthDp))
        }
        // Every seated mascot draws here, over the shell, and travels between seats (wbin4.4).
        MascotTransportLayer(reducedMotion = layout.reducedMotion) {
            Row(Modifier.fillMaxSize()) {
                // Far-left workspace/agent rail.
                DesktopShellAgentRail(context, frame)
                RailDivider()
                // Agent sidebar: agent header + nav + conversations. Fully
                // removed (not shrunk to an icon rail - AC #3) below the
                // breakpoint or when the user explicitly collapses it.
                DesktopCollapsibleSidebar(
                    visible = isSidebarVisible,
                    reducedMotion = layout.reducedMotion,
                ) {
                    DesktopShellAgentSidebar(context, frame)
                    RailDivider()
                }
                DesktopShellMainPane(context, frame)
                DesktopShellBackgroundTasks(context, frame)
            }
        }
    }
}

@Composable
private fun DesktopShellAgentRail(context: DesktopShellContext, frame: DesktopShellFrame) {
    val navigator = context.navigator
    val focus = frame.focus
    DesktopAgentRail(
        state = DesktopAgentRailState(
            agents = rememberRecentRailAgents(
                frame.chatState.conversations,
                focus.railAgents,
                context.core.railPrefs,
                focus.selectedAgentId,
            ),
            focus = DesktopAgentRailFocus(
                selectedAgentId = focus.selectedAgentId,
                activityByAgentId = focus.railActivityByAgentId,
                thinkingAgentId = frame.activity.thinkingAgentId,
                avatarStyleByAgentId = focus.avatarStyleByAgentId,
                identityByAgentId = focus.identityByAgentId,
            ),
            expanded = navigator.railExpanded,
            homeSelected = navigator.selectedDestination == DesktopDestination.Home,
        ),
        actions = DesktopAgentRailActions(
            onHome = { navigator.navigate(DesktopDestination.Home) },
            onAgentSelected = { agentId ->
                // Search-driven library: picking an agent is the
                // "done" gesture, so the expanded panel closes.
                navigator.railExpanded = false
                context.router.openAgent(AgentId(agentId))
            },
            // Contacts-style picker over the persistent-agent
            // roster; agent creation lives inside it.
            onNewSession = { context.overlays.newConversation = true },
            onToggleExpanded = { navigator.railExpanded = !navigator.railExpanded },
        ),
    )
}

@Composable
private fun DesktopShellAgentSidebar(context: DesktopShellContext, frame: DesktopShellFrame) {
    val chatController = context.core.chatController
    val canvasShell = context.core.canvasShell
    val navigator = context.navigator
    val focus = frame.focus
    val canvasDocuments by canvasShell.library.documents.collectAsState()
    val archivedCanvasIds by canvasShell.library.archived.collectAsState()
    val deletingConversationIds by chatController.deletingConversationIds.collectAsState()
    val archiveFilter = frame.lists.archiveFilter
    DesktopAgentSidebar(
        state = DesktopAgentSidebarState(
            agentName = focus.selectedAgentName,
            agentOrbIndex = focus.selectedAgentOrbIndex,
            agentId = focus.selectedAgentId,
            agentIdentity = focus.selectedAgentId?.let { focus.identityByAgentId[it] },
            conversations = frame.lists.agentConversations,
            selectedConversationId = frame.chatState.selectedConversationId,
            thinkingConversationId = frame.activity.thinkingConversationId,
            deletingConversationIds = deletingConversationIds,
            archiveFilter = archiveFilter,
            selectedDestination = navigator.selectedDestination,
            mode = navigator.workPlayMode,
            // The sidebar's Active / Archived / All applies to canvases as to chats.
            canvases = CanvasLibrary.filter(canvasDocuments, archivedCanvasIds, archiveFilter.toCanvasArchiveFilter()),
            activeCanvasId = canvasShell.activeSession?.canvasId,
            archivedCanvasIds = archivedCanvasIds,
        ),
        actions = DesktopAgentSidebarActions(
            onArchiveFilterChange = chatController::setArchiveFilter,
            onArchiveConversation = chatController::setConversationArchived,
            onModeChange = { navigator.workPlayMode = it },
            onDestinationSelected = navigator::navigate,
            onConversationSelected = { context.router.openConversation(ConversationId(it)) },
            onDeleteConversation = chatController::deleteConversation,
            onNewChat = { context.openNewChatForFocusedAgent(focus) },
            onEditAgent = { navigator.editAgentId = focus.selectedAgentId },
            onOpenCanvas = { context.openCanvas(it) },
            onNewCanvas = { canvasShell.createNew(focus.selectedAgentId) },
            onArchiveCanvas = canvasShell.library::setArchived,
        ),
    )
}

@Composable
private fun DesktopShellBackgroundTasks(context: DesktopShellContext, frame: DesktopShellFrame) {
    val navigator = context.navigator
    val repository = context.panels.subagents.repository
    if (navigator.showBackgroundTasks && repository != null) {
        DesktopBackgroundTasksSidePane(
            subagents = frame.lists.activeSubagents,
            onFetchTodos = { toolCallId -> repository.todos(toolCallId).getOrDefault(emptyList()) },
            onClose = { navigator.showBackgroundTasks = false },
        )
    }
}

@Composable
private fun DesktopShellOverlays(context: DesktopShellContext, frame: DesktopShellFrame) {
    val core = context.core
    val focus = frame.focus
    val isDragActive by context.imageIntake.isDragActive
    DesktopAppOverlays(
        visibility = context.overlays,
        data = DesktopOverlayData(
            composerModelLabel = frame.chatState.composerModelLabel,
            modelOptions = frame.lists.modelOptions,
            paletteItems = frame.lists.paletteItems,
            railAgents = focus.railAgents,
            rosterAgents = focus.rosterAgents,
            avatarStyleByAgentId = focus.avatarStyleByAgentId,
            isDragActive = isDragActive,
        ),
        actions = createDesktopOverlayActions(
            CreateDesktopOverlayActionsParams(
                chatController = core.chatController,
                onSelectDestination = { context.navigator.selectedDestination = it },
                onOpenAgent = { context.router.openAgent(AgentId(it)) },
                onNewCanvas = { core.canvasShell.createNew(focus.selectedAgentId) },
                dataBindings = core.bootstrap.dataBindings,
                selectedAgentId = focus.selectedAgentId,
                onIrohIdentityReset = {
                    DesktopIrohIdentity.reset()
                    core.bootstrap.applyConfig(core.bootstrap.activeConfig)
                },
            ),
        ),
    )
}
