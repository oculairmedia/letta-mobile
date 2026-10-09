package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.data.canvas.CanvasLibrary
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.desktop.canvas.toCanvasArchiveFilter
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksSidePane
import com.letta.mobile.desktop.phone.DesktopShellRow
import com.letta.mobile.desktop.phone.LocalDesktopPhone
import com.letta.mobile.desktop.phone.ReportShellWidth
import com.letta.mobile.desktop.phone.agentPaneVisible
import com.letta.mobile.desktop.phone.sidebarVisible
import com.letta.mobile.desktop.plugin.view.DesktopPluginBindings
import com.letta.mobile.desktop.plugin.view.ProvideDesktopPluginViews
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
                // Plugin elements render live where a plugin view is bound (letta-mobile-s416w.14);
                // until the host's plugin catalog reaches the client, none is, and every element is its card.
                ProvideDesktopPluginViews(DesktopPluginBindings.None) {
                    DesktopShellLayoutBody(context, frame)
                }
                DesktopShellOverlays(context, frame)
                val chatController = context.core.chatController
                val undoable by chatController.deletionUndo.pending.collectAsState()
                DesktopUndoDeleteSnackbar(
                    offer = undoable,
                    onUndo = chatController::undoDeleteConversation,
                    onExpire = { chatController.deletionUndo.clear(it.conversationId) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun DesktopShellLayoutBody(context: DesktopShellContext, frame: DesktopShellFrame) {
    val layout = context.core.layout
    // The phone preview folds the rail and sidebar into a drawer; null in the desktop app.
    val phone = LocalDesktopPhone.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Responsive shell: below the breakpoint (or when the user
        // explicitly collapses it) the capability/history sidebar is
        // fully removed - not shrunk to an icon rail - and the chat pane
        // takes the full width. The breakpoint/collapse decision itself
        // lives in sharedLogic's ShellLayoutReducer; this just reports
        // the measured width into it. The 56dp agent rail always stays as
        // the navigation affordance.
        val measuredWidthDp = maxWidth.value
        val desktopSidebarVisible = layout.controller.state.isSidebarVisible &&
            !ShellLayoutReducer.defaultCollapsedForWidth(measuredWidthDp)
        val isSidebarVisible = phone.sidebarVisible(desktopSidebarVisible)
        // One rule for where the mascot stands (wbin4.4), driven from the shell's own state.
        DriveMascotStage(frame.focus.selectedAgentId, agentPaneVisible = phone.agentPaneVisible(isSidebarVisible))
        ReportShellWidth(phone, measuredWidthDp) { layout.controller.dispatch(ShellLayoutEvent.WindowWidthChanged(it)) }
        // Every seated mascot draws here, over the shell, and travels between seats (wbin4.4).
        MascotTransportLayer(reducedMotion = layout.reducedMotion) {
            DesktopShellRow(
                phone,
                layout.reducedMotion,
                navigationPanes = { DesktopShellNavigationPanes(context, frame, isSidebarVisible) },
            ) {
                DesktopShellMainPane(context, frame)
                DesktopShellBackgroundTasks(context, frame)
            }
        }
    }
}

/** The far-left workspace/agent rail and the agent sidebar beside it. */
@Composable
private fun DesktopShellNavigationPanes(context: DesktopShellContext, frame: DesktopShellFrame, sidebarVisible: Boolean) {
    DesktopShellAgentRail(context, frame)
    RailDivider()
    // Agent sidebar: agent header + nav + conversations. Fully
    // removed (not shrunk to an icon rail - AC #3) below the
    // breakpoint or when the user explicitly collapses it.
    DesktopCollapsibleSidebar(
        visible = sidebarVisible,
        reducedMotion = context.core.layout.reducedMotion,
    ) {
        DesktopShellAgentSidebar(context, frame)
        RailDivider()
    }
}

@Composable
private fun DesktopShellAgentRail(context: DesktopShellContext, frame: DesktopShellFrame) {
    val navigator = context.navigator
    val focus = frame.focus
    val recents = rememberRecentRailAgents(
        frame.chatState.conversations,
        focus.railAgents,
        context.core.railPrefs,
        focus.selectedAgentId,
    )
    DesktopAgentRail(
        state = DesktopAgentRailState(
            agents = recents.agents,
            hiddenAgentCount = recents.hiddenCount,
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
            // The same roster picker lists every agent the recents cut left off the rail.
            onShowAllAgents = { context.overlays.newConversation = true },
            onToggleExpanded = { navigator.railExpanded = !navigator.railExpanded },
            onAgentSettings = { navigator.editAgentId = it },
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
    val deleteBehavior by chatController.deleteBehavior.collectAsState()
    val pinnedConversationIds by chatController.conversationManagement.pinnedConversationIds.collectAsState()
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
            pinnedConversationIds = pinnedConversationIds,
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
            deleteBehavior = deleteBehavior,
            onRenameConversation = { id, title ->
                chatController.conversationManagement.rename(ConversationSummaryUpdate(ConversationId(id), ConversationSummary(title)))
            },
            onPinConversation = { id, pinned -> chatController.conversationManagement.setPinned(ConversationId(id), pinned) },
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
