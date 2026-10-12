package com.letta.mobile.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.model.AgentId
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
import com.letta.mobile.ui.notify.LocalAttentionPrefs
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellLibraryRail
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.railLightDismiss
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanel

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
                CompositionLocalProvider(
                    LocalDesktopPermissionModeSettings provides context.core.bootstrap.dataBindings.permissionModeSettings,
                    LocalAttentionPrefs provides context.core.attentionPrefs,
                ) {
                    ProvideDesktopPluginViews(DesktopPluginBindings.None) {
                        DesktopShellLayoutBody(context, frame)
                    }
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

/** The far-left workspace/agent rail and the agent sidebar beside it, both the shared shell's. */
@Composable
private fun DesktopShellNavigationPanes(context: DesktopShellContext, frame: DesktopShellFrame, sidebarVisible: Boolean) {
    DesktopShellAgentRail(context, frame)
    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    // Agent sidebar: agent header + nav + conversations. Fully
    // removed (not shrunk to an icon rail - AC #3) below the
    // breakpoint or when the user explicitly collapses it.
    DesktopCollapsibleSidebar(
        visible = sidebarVisible,
        reducedMotion = context.core.layout.reducedMotion,
    ) {
        DesktopShellAgentSidebar(context, frame)
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DesktopShellAgentRail(context: DesktopShellContext, frame: DesktopShellFrame) {
    val navigator = context.navigator
    val focus = frame.focus
    val recents = rememberRecentRailAgents(frame.chatState.conversations, focus.railAgents, context.core.railPrefs, focus.selectedAgentId)
    ShellLibraryRail(
        state = ShellLibraryRailState(
            agents = recents.agents,
            hiddenAgentCount = recents.hiddenCount,
            focus = ShellRailFocus(
                selectedAgentId = focus.selectedAgentId,
                activityByAgentId = focus.railActivityByAgentId,
                thinkingAgentId = frame.activity.thinkingAgentId,
                avatarStyleByAgentId = focus.avatarStyleByAgentId,
                identityByAgentId = focus.identityByAgentId,
            ),
            expanded = navigator.railExpanded,
            homeSelected = navigator.selectedDestination == DesktopDestination.Home,
        ),
        // The desktop keeps no agent pins, so the orb menu (right-click) offers Open and Agent settings.
        actions = ShellAgentRailActions(
            onHome = { navigator.navigate(DesktopDestination.Home) },
            onAgentSelected = { agentId ->
                // Search-driven library: picking an agent is the "done" gesture, so the expanded panel closes.
                navigator.railExpanded = false
                context.router.openAgent(AgentId(agentId))
            },
            // Contacts-style picker over the persistent-agent roster; agent creation lives inside it.
            onNewSession = { context.overlays.newConversation = true },
            // The same roster picker lists every agent the recents cut left off the rail.
            onShowAllAgents = { context.overlays.newConversation = true },
            onAgentSettings = { navigator.editAgentId = it },
        ),
    )
}

/** The shared agent panel, bound to the window (see [desktopSidebarPanelState] and [desktopSidebarActions]). */
@Composable
private fun DesktopShellAgentSidebar(context: DesktopShellContext, frame: DesktopShellFrame) {
    val chatController = context.core.chatController
    val management = chatController.conversationManagement
    val canvasShell = context.core.canvasShell
    val inputs = DesktopSidebarInputs(
        marks = DesktopSidebarMarks(
            deletingIds = chatController.deletingConversationIds.collectAsState().value,
            pinnedIds = management.pinnedConversationIds.collectAsState().value,
            parentIds = management.conversationParents.collectAsState().value.mapValues { it.value.conversationId },
            settledIds = management.marks.settled.collectAsState().value,
            markers = management.marks.markers.collectAsState().value,
        ),
        canvases = canvasShell.library.documents.collectAsState().value,
        archivedCanvasIds = canvasShell.library.archived.collectAsState().value,
        activeCanvasId = canvasShell.activeSession?.canvasId,
        chatOptions = desktopChatOptionsState(
            display = management.marks.displayOptions.collectAsState().value,
            fontScale = LocalDesktopChatFontScale.current,
            autoPin = management.marks.autoPinNewChats.collectAsState().value,
            hasChat = frame.chatState.selectedConversationId != null,
        ),
    )
    val deleteBehavior by chatController.deleteBehavior.collectAsState()
    val setFontScale by rememberUpdatedState(LocalDesktopChatFontScaleSetter.current)
    ShellAgentPanel(
        state = desktopSidebarPanelState(context.navigator, frame, inputs),
        actions = desktopSidebarActions(context, frame.focus, deleteBehavior) { setFontScale(it) },
        // letta-mobile-3io8k: model, context meter and Compact under the agent's name.
        agentCard = { DesktopAgentContextCard(context, frame) },
        modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLowest),
    )
}

private val SIDEBAR_WIDTH = 231.dp

@Composable
private fun DesktopShellBackgroundTasks(context: DesktopShellContext, frame: DesktopShellFrame) {
    val navigator = context.navigator
    val repository = context.panels.subagents.repository
    if (navigator.showBackgroundTasks && repository != null) {
        DesktopBackgroundTasksSidePane(
            subagents = frame.lists.activeSubagents,
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
