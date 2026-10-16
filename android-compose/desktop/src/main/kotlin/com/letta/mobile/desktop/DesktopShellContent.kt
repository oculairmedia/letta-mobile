package com.letta.mobile.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.data.plugin.view.ViewPlatform
import com.letta.mobile.desktop.phone.DesktopShellRow
import com.letta.mobile.desktop.phone.LocalDesktopPhone
import com.letta.mobile.desktop.phone.ReportShellWidth
import com.letta.mobile.desktop.phone.agentPaneVisible
import com.letta.mobile.desktop.phone.sidebarVisible
import com.letta.mobile.desktop.plugin.view.rememberJcefPluginViewHost
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.security.DesktopIrohIdentity
import com.letta.mobile.pluginview.ProvidePluginViews
import com.letta.mobile.ui.chat.subagents.BackgroundTasksSidePane
import com.letta.mobile.ui.host.InspectorFrame
import com.letta.mobile.ui.host.OverlayHostRoot
import com.letta.mobile.ui.host.OverlayWhile
import com.letta.mobile.ui.host.ProvideInspectorHost
import com.letta.mobile.ui.host.rememberInspectorHost
import com.letta.mobile.ui.host.rememberOverlayHost
import com.letta.mobile.ui.mascot.MascotTransportLayer
import com.letta.mobile.ui.notify.LocalAttentionPrefs
import com.letta.mobile.ui.shell.UndoDeleteOverlay
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellLibraryRail
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.railLightDismiss
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanel

/** The window's surface: the rail, the agent sidebar and the main pane, with the overlays over them. */
/**
 * Plugin elements render live through the shared plugin-view renderer on the JCEF host
 * (letta-mobile-cdgpr.3), as on Android. The host's enabled plugins and view transport come from
 * the shared model (letta-mobile-cdgpr.8). With live views off on this device, every element is its
 * card. The browser is only started when a live view first shows.
 */
@Composable
private fun ShellPluginViews(pluginViews: PluginViewsModel?, content: @Composable () -> Unit) {
    ProvidePluginViews(pluginViews, rememberJcefPluginViewHost(), canvasId = "", platform = ViewPlatform.DESKTOP, content = content)
}

@Composable
internal fun DesktopShellWindowContent(context: DesktopShellContext, frame: DesktopShellFrame) {
    val navigator = context.navigator
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The window's one overlay queue (snackbars, undo, banners), drawn over the whole shell.
        OverlayHostRoot(rememberOverlayHost(), Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .railLightDismiss(navigator.railExpanded) { navigator.railExpanded = false },
            ) {
                CompositionLocalProvider(
                    LocalDesktopPermissionModeSettings provides context.core.bootstrap.dataBindings.permissionModeSettings,
                    LocalAttentionPrefs provides context.core.attentionPrefs,
                ) {
                    ShellPluginViews(context.core.bootstrap.dataBindings.pluginViews) {
                        DesktopShellLayoutBody(context, frame)
                    }
                }
                DesktopShellOverlays(context, frame)
                // F03: the bundled runtime's crash notice, over every page while it is down.
                val runtimeBanner = DesktopLocalRuntimeHost.appServer.health.collectAsState().value.runtimeBanner()
                OverlayWhile(runtimeBanner, key = runtimeBanner?.let { "${it.message}|${it.action?.label}" })
                val chat = context.core.chatController
                val offer = chat.deletionUndo.pending.collectAsState().value
                UndoDeleteOverlay(offer?.undoKey(), offer?.behavior, { offer?.let(chat::undoDeleteConversation) }) {
                    offer?.let { chat.deletionUndo.clear(it.conversationId) }
                }
            }
        }
    }
}

@Composable
private fun DesktopShellLayoutBody(context: DesktopShellContext, frame: DesktopShellFrame) {
    val layout = context.core.layout
    // The phone preview folds the rail and sidebar into a drawer; null in the desktop app.
    val phone = LocalDesktopPhone.current
    // The sidebar's card and the main pane's panels open their detail in the frame around the main pane.
    val inspectors = rememberInspectorHost()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Below the breakpoint (or when collapsed) the sidebar is removed, not shrunk, and the chat takes
        // the width; ShellLayoutReducer decides, this reports the width. The 56dp agent rail always stays.
        val measuredWidthDp = maxWidth.value
        val desktopSidebarVisible = layout.controller.state.isSidebarVisible &&
            !ShellLayoutReducer.defaultCollapsedForWidth(measuredWidthDp)
        val isSidebarVisible = phone.sidebarVisible(desktopSidebarVisible)
        // One rule for where the mascot stands (wbin4.4), driven from the shell's own state.
        DriveMascotStage(frame.focus.selectedAgentId, agentPaneVisible = phone.agentPaneVisible(isSidebarVisible))
        ReportShellWidth(phone, measuredWidthDp) { layout.controller.dispatch(ShellLayoutEvent.WindowWidthChanged(it)) }
        // Every seated mascot draws here, over the shell, and travels between seats (wbin4.4).
        MascotTransportLayer(reducedMotion = layout.reducedMotion) {
            ProvideInspectorHost(inspectors) {
                DesktopShellRow(
                    phone,
                    layout.reducedMotion,
                    navigationPanes = { DesktopShellNavigationPanes(context, frame, isSidebarVisible) },
                ) {
                    InspectorFrame(Modifier.weight(1f).fillMaxHeight(), host = inspectors) {
                        Row(Modifier.fillMaxSize()) { DesktopShellMainPane(context, frame) }
                    }
                    DesktopShellBackgroundTasks(context, frame)
                }
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
            // Contacts-style picker over the persistent-agent roster: agent creation lives inside it, and
            // it lists every agent the recents cut left off the rail.
            onNewSession = { context.overlays.newConversation = true },
            onAgentSettings = { navigator.editAgentId = AgentId(it) },
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
    if (!navigator.showBackgroundTasks || context.panels.subagents.repository == null) return
    // The roster opens in the window's inspector (docked beside the main pane when wide).
    BackgroundTasksSidePane(frame.lists.activeSubagents, onClose = { navigator.showBackgroundTasks = false })
}

@Composable
private fun DesktopShellOverlays(context: DesktopShellContext, frame: DesktopShellFrame) {
    val core = context.core
    val focus = frame.focus
    DesktopAppOverlays(
        visibility = context.overlays,
        data = DesktopOverlayData(
            composerModelLabel = frame.chatState.composerModelLabel,
            searchSources = frame.lists.search,
            railAgents = focus.railAgents,
            rosterAgents = focus.rosterAgents,
            avatarStyleByAgentId = focus.avatarStyleByAgentId,
            isDragActive = context.imageIntake.isDragActive.value,
        ),
        actions = createDesktopOverlayActions(
            CreateDesktopOverlayActionsParams(
                chatController = core.chatController,
                onSelectDestination = { context.navigator.selectedDestination = it },
                onOpenAgent = { context.router.openAgent(AgentId(it)) },
                onNewCanvas = { core.canvasShell.createNew(focus.selectedAgentId) },
                onOpenSearchHit = { context.router.openSearchHit(it) },
                dataBindings = core.bootstrap.dataBindings,
                onNewAgent = context.navigator::openNewAgent,
                onIrohIdentityReset = {
                    DesktopIrohIdentity.reset()
                    core.bootstrap.applyConfig(core.bootstrap.activeConfig)
                },
            ),
        ),
    )
}
