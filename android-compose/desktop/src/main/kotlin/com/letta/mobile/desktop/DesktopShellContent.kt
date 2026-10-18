package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.chat.runtime.ConversationListGroups
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.shell.shortcuts.ShortcutActions
import com.letta.mobile.ui.shell.shortcuts.ShortcutChrome
import com.letta.mobile.ui.shell.shortcuts.ShortcutLists
import com.letta.mobile.ui.shell.shortcuts.ShortcutStepping
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutId
import com.letta.mobile.ui.shell.shortcuts.handleShortcut
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.data.plugin.view.ViewPlatform
import com.letta.mobile.desktop.phone.LocalDesktopPhone
import com.letta.mobile.desktop.plugin.view.rememberJcefPluginViewHost
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.security.DesktopIrohIdentity
import com.letta.mobile.pluginview.ProvidePluginViews
import com.letta.mobile.ui.chat.subagents.BackgroundTasksSidePane
import com.letta.mobile.ui.chat.subagents.rosterOpener
import com.letta.mobile.ui.host.OverlayWhile
import com.letta.mobile.ui.notify.LocalAttentionPrefs
import com.letta.mobile.ui.shell.UndoDeleteOverlay
import com.letta.mobile.ui.shell.workspace.openSearchHit
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceHost
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceSlots
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.ShellWindowChrome
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceEvents
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceLayout
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigation
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigationActions
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayData
import com.letta.mobile.ui.theme.LocalReducedMotion

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

/**
 * letta-mobile-ckmub.17.4: the window's surface is the shared [LettaShellWorkspace]; this only binds
 * the desktop's state and verbs into it. The header goes to the title bar ([ShellWindowChrome.Hoisted]);
 * the phone preview folds the rail and panel into the workspace's drawer.
 */
@Composable
internal fun DesktopShellWindowContent(context: DesktopShellContext, frame: DesktopShellFrame, header: DesktopShellHeader) {
    val layout = context.core.layout
    val phone = LocalDesktopPhone.current
    CompositionLocalProvider(
        LocalDesktopPermissionModeSettings provides context.core.bootstrap.dataBindings.permissionModeSettings,
        LocalAttentionPrefs provides context.core.attentionPrefs,
        LocalReducedMotion provides layout.reducedMotion,
    ) {
        ShellPluginViews(context.core.bootstrap.dataBindings.pluginViews) {
            LettaShellWorkspace(
                state = LettaShellWorkspaceState(
                    header = header.state,
                    navigation = desktopWorkspaceNavigation(context, frame),
                    layout = ShellWorkspaceLayout(
                        sidebarVisible = layout.controller.state.isSidebarVisible,
                        reducedMotion = layout.reducedMotion,
                        forceCompact = phone != null,
                    ),
                ),
                host = LettaShellWorkspaceHost(
                    actions = desktopWorkspaceActions(context, frame),
                    slots = desktopWorkspaceSlots(context, frame),
                    chrome = ShellWindowChrome.Hoisted(header.onHoisted),
                    drawer = phone?.drawer,
                    events = ShellWorkspaceEvents(
                        // A phone's width says nothing about the layout the person saved.
                        onWidthChanged = { width ->
                            if (phone == null) layout.controller.dispatch(ShellLayoutEvent.WindowWidthChanged(width))
                        },
                        onShortcut = rememberDesktopShortcutHandler(context, frame),
                    ),
                ),
            )
        }
    }
}

@Composable
private fun rememberDesktopShortcutHandler(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
): (ShellShortcutId) -> Unit {
    val layout = context.core.layout.controller
    val management = context.core.chatController.conversationManagement
    val pinned by management.pinnedConversationIds.collectAsState()
    val settled by management.marks.settled.collectAsState()
    val conversationIds = remember(frame.lists.agentConversations, pinned, settled) {
        desktopConversationOrder(frame.lists.agentConversations, ConversationListGroups(pinned, settled))
    }
    val currentConversation by rememberUpdatedState(frame.chatState.selectedConversationId)
    val agentIds by rememberUpdatedState(frame.focus.railAgents.map { it.first })
    val currentAgent by rememberUpdatedState(frame.focus.selectedAgentId)
    val router = context.router
    val navigator = context.navigator
    val actions = ShortcutActions(
        overlays = context.overlays,
        navigate = { destination -> navigator.navigate(destination) },
        chrome = ShortcutChrome(toggleSidebar = { layout.dispatch(ShellLayoutEvent.ToggleSidebar) }),
        lists = ShortcutLists(
            conversations = ShortcutStepping(
                ids = { conversationIds },
                current = { currentConversation },
                open = { router.openConversation(ConversationId(it)) },
            ),
            agents = ShortcutStepping(
                ids = { agentIds },
                current = { currentAgent },
                open = { router.openAgent(AgentId(it)) },
            ),
        ),
    )
    val latest = rememberUpdatedState(actions)
    return remember { { id: ShellShortcutId -> handleShortcut(id, latest.value) } }
}

private fun desktopWorkspaceSlots(context: DesktopShellContext, frame: DesktopShellFrame) = LettaShellWorkspaceSlots(
    main = { modifier -> Row(modifier) { DesktopShellMainPane(context, frame) } },
    // letta-mobile-3io8k: model, context meter and Compact under the agent's name.
    agentCard = { DesktopAgentContextCard(context, frame) },
    sidePane = { DesktopShellBackgroundTasks(context, frame) },
    overlays = { DesktopShellOverlays(context, frame) },
)

/** The rail (the recents cut, expanding into the library) and the agent panel, bound to the window. */
@Composable
private fun desktopWorkspaceNavigation(context: DesktopShellContext, frame: DesktopShellFrame): ShellWorkspaceNavigation {
    val navigator = context.navigator
    val focus = frame.focus
    val recents = rememberRecentRailAgents(frame.chatState.conversations, focus.railAgents, context.core.railPrefs, focus.selectedAgentId)
    return ShellWorkspaceNavigation(
        rail = ShellLibraryRailState(
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
        panel = desktopSidebarPanelState(navigator, frame, rememberDesktopSidebarInputs(context, frame)),
    )
}

@Composable
private fun rememberDesktopSidebarInputs(context: DesktopShellContext, frame: DesktopShellFrame): DesktopSidebarInputs {
    val chatController = context.core.chatController
    val management = chatController.conversationManagement
    val canvasShell = context.core.canvasShell
    return DesktopSidebarInputs(
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
}

/** The rail's and the panel's verbs. The desktop keeps no agent pins, so the orb menu offers Open and Agent settings. */
@Composable
private fun desktopWorkspaceActions(context: DesktopShellContext, frame: DesktopShellFrame): ShellWorkspaceNavigationActions {
    val navigator = context.navigator
    val deleteBehavior by context.core.chatController.deleteBehavior.collectAsState()
    val setFontScale by rememberUpdatedState(LocalDesktopChatFontScaleSetter.current)
    return ShellWorkspaceNavigationActions(
        rail = ShellAgentRailActions(
            onHome = { navigator.navigate(DesktopDestination.Home) },
            onAgentSelected = { agentId ->
                // Search-driven library: picking an agent is the "done" gesture, so the expanded panel closes.
                navigator.railExpanded = false
                context.router.openAgent(AgentId(agentId))
            },
            // Contacts-style picker over the persistent-agent roster: agent creation lives inside it.
            onNewSession = { context.overlays.newConversation = true },
            onAgentSettings = { navigator.editAgentId = AgentId(it) },
        ),
        panel = desktopSidebarActions(context, frame.focus, deleteBehavior) { setFontScale(it) },
        onRailDismiss = { navigator.railExpanded = false },
    )
}

@Composable
private fun DesktopShellBackgroundTasks(context: DesktopShellContext, frame: DesktopShellFrame) {
    val navigator = context.navigator
    if (!navigator.showBackgroundTasks || context.panels.subagents.repository == null) return
    // The roster opens in the window's inspector (docked beside the main pane when wide).
    BackgroundTasksSidePane(
        subagents = frame.lists.activeSubagents,
        onClose = { navigator.showBackgroundTasks = false },
        onOpenConversation = frame.subagentViews?.holder?.rosterOpener(navigator::showChat),
    )
}

@Composable
private fun DesktopShellOverlays(context: DesktopShellContext, frame: DesktopShellFrame) {
    val core = context.core
    val focus = frame.focus
    DesktopAppOverlays(
        visibility = context.overlays,
        data = DesktopOverlayData(
            composerModelLabel = frame.chatState.composerModelLabel,
            workspace = ShellWorkspaceOverlayData(
                searchSources = frame.lists.search,
                railAgents = focus.railAgents,
                rosterAgents = focus.rosterAgents,
                avatarStyleByAgentId = focus.avatarStyleByAgentId,
                isDragActive = context.imageIntake.isDragActive.value,
            ),
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
    // F03: the bundled runtime's crash notice, over every page while it is down.
    val runtimeBanner = DesktopLocalRuntimeHost.appServer.health.collectAsState().value.runtimeBanner()
    OverlayWhile(runtimeBanner, key = runtimeBanner?.let { "${it.message}|${it.action?.label}" })
    val chat = core.chatController
    val offer = chat.deletionUndo.pending.collectAsState().value
    UndoDeleteOverlay(offer?.undoKey(), offer?.behavior, { offer?.let(chat::undoDeleteConversation) }) {
        offer?.let { chat.deletionUndo.clear(it.conversationId) }
    }
}
