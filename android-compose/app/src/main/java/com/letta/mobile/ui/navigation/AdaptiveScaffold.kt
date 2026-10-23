package com.letta.mobile.ui.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.agents.RecentAgents
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.feature.chat.screen.SharedNavDrawerViewModel
import com.letta.mobile.feature.chat.screen.WorkspaceChatPanel
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.shell.ShellDrawerConversations
import com.letta.mobile.ui.shell.ShellDrawerLibrary
import com.letta.mobile.ui.shell.pages.ShellHomeBackendFleetEffect
import com.letta.mobile.ui.shell.pages.ShellLibraryControllers
import com.letta.mobile.ui.shell.pages.rememberShellLibraryControllers
import com.letta.mobile.ui.shell.rememberShellWorkspaceState
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutBinding
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutId
import com.letta.mobile.ui.shell.shortcuts.ShortcutActions
import com.letta.mobile.ui.shell.shortcuts.ShortcutLists
import com.letta.mobile.ui.shell.shortcuts.ShortcutPlatform
import com.letta.mobile.ui.shell.shortcuts.ShortcutStepping
import com.letta.mobile.ui.shell.shortcuts.WorkspaceShortcuts
import com.letta.mobile.ui.shell.shortcuts.handleShortcut
import com.letta.mobile.ui.shell.workspace.fleetSearchSources
import com.letta.mobile.ui.shell.workspace.fleetTabFor
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceHost
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceSlots
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.ShellLayoutController
import com.letta.mobile.ui.shell.workspace.ShellWindowChrome
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceDrawer
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceEvents
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderBinding
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceLayout
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigation
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigationActions
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceSearch
import com.letta.mobile.ui.shell.workspace.rememberShellLayoutController
import com.letta.mobile.ui.shell.workspace.rememberShellWorkspaceHeader
import com.letta.mobile.ui.shell.workspace.workspaceNavigation
import com.letta.mobile.ui.shell.workspace.workspaceNavigationActions
import com.letta.mobile.ui.theme.rememberReducedMotionEnabled
import kotlin.time.Clock

/** Where the workspace leaves for Android's own screens: the camera, diagnostics and the full-screen board. */
internal class AndroidShellExits(
    val openCanvas: (agentId: String?, conversationId: String?, shareRecipient: String?) -> Unit,
    val openCanvasById: (canvasId: String) -> Unit,
    val settings: com.letta.mobile.ui.screens.config.SettingsDestinations,
)

/** One composition's Android shell: the workspace, its navigation, the chat it drives and the drawer binding. */
internal class WorkspaceShell(
    /** The active backend config; null before one is configured. */
    val config: LettaConfig?,
    val workspace: ShellWorkspaceState,
    val port: AndroidShellChatPort,
    val drawerModel: SharedNavDrawerViewModel,
    val layout: ShellLayoutController,
    val drawer: ShellWorkspaceDrawer,
    val exits: AndroidShellExits,
    /** The open chat's model card and Chat options, which its page puts here while it shows. */
    val chatPanel: MutableState<WorkspaceChatPanel?>,
    val controllers: ShellLibraryControllers,
) {
    /** The shell's verbs over the workspace and the chat they drive. */
    val navigator = ShellWorkspaceNavigator(workspace, port)
}

/**
 * letta-mobile-ckmub.2 (hard cut): Android mounts the shared [LettaShellWorkspace] as desktop and web do.
 * This only binds Android's state into it: the roster and conversations from the drawer binding, the
 * session graph's pages ([androidPageHost]), Android's chat page ([androidChatPage]) and the back gesture.
 */
@Composable
internal fun AndroidShellWorkspace(start: ShellDestination, config: LettaConfig?, exits: AndroidShellExits, launch: AndroidShellLaunch) {
    val drawerModel: SharedNavDrawerViewModel = hiltViewModel()
    val agents by drawerModel.agents.collectAsStateWithLifecycle()
    val fleet by drawerModel.fleet.collectAsStateWithLifecycle()
    val workspace = rememberShellWorkspaceState(start)
    val latestFleet by rememberUpdatedState(fleet)
    val latestAgents by rememberUpdatedState(agents)
    val port = remember(workspace) { AndroidShellChatPort(workspace, { latestFleet }, { latestAgents.map { it.id } }) }
    val sources = drawerModel.sources
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shell = WorkspaceShell(
        config = config,
        workspace = workspace,
        port = port,
        drawerModel = drawerModel,
        layout = rememberShellLayoutController(config, sources.library.settings),
        drawer = remember { ShellWorkspaceDrawer() },
        exits = exits,
        chatPanel = remember { mutableStateOf(null) },
        controllers = rememberShellLibraryControllers(sources.sessions, sources.library, scope),
    )
    LaunchedEffect(agents) { workspace.rosterChanged(agents.map { it.id }) }
    // Each backend's roster and conversations load as the workspace comes up (a new backend reloads them).
    LaunchedEffect(config?.id) { drawerModel.refresh(workspace.focusedAgentId, roster = true) }
    LaunchedEffect(shell.drawer.open, workspace.focusedAgentId) { if (shell.drawer.open) drawerModel.refresh(workspace.focusedAgentId) }
    AndroidShellLaunchEffect(shell, launch)
    ShellHomeBackendFleetEffect(shell.controllers.home, fleet, agents)
    ShellFailures(drawerModel)
    // Back closes the drawer, then returns to Home; on Home it leaves the app.
    BackHandler(enabled = shell.drawer.open || workspace.page != ShellDestination.Home) {
        if (shell.drawer.open) shell.drawer.open = false else workspace.navigate(ShellDestination.Home)
    }
    val header = rememberShellWorkspaceHeader(
        ShellWorkspaceHeaderBinding(
            navigator = shell.navigator,
            layout = shell.layout,
            search = ShellWorkspaceSearch(shell.controllers.home, fleetSearchSources(agents, fleet, workspace.focusedAgentId)),
            tabFor = fleetTabFor(fleet),
        ),
    )
    LettaShellWorkspace(
        state = LettaShellWorkspaceState(
            header = header,
            navigation = rememberAndroidNavigation(shell, agents),
            layout = ShellWorkspaceLayout(
                sidebarVisible = shell.layout.state.isSidebarVisible,
                reducedMotion = rememberReducedMotionEnabled(),
            ),
        ),
        host = LettaShellWorkspaceHost(
            actions = androidNavigationActions(shell),
            slots = androidSlots(shell, agents),
            chrome = ShellWindowChrome.Inline,
            drawer = shell.drawer,
            events = ShellWorkspaceEvents(
                onWidthChanged = { shell.layout.dispatch(ShellLayoutEvent.WindowWidthChanged(it)) },
                shortcuts = rememberAndroidShortcuts(shell, agents),
            ),
        ),
    )
}

/** The page, the open chat's card in the panel, the background tasks beside the page, and the overlays. */
private fun androidSlots(shell: WorkspaceShell, agents: List<Agent>) = LettaShellWorkspaceSlots(
    main = { modifier -> AndroidShellPage(shell, modifier) },
    agentCard = { shell.chatPanel.value?.agentCard?.invoke() },
    sidePane = { AndroidBackgroundTasks(shell) },
    overlays = { AndroidShellOverlays(shell, agents) },
)

/** The rail over the roster (most recent first) and the focused agent's panel: conversations, marks, canvases, Chat options. */
@Composable
private fun rememberAndroidNavigation(shell: WorkspaceShell, agents: List<Agent>): ShellWorkspaceNavigation {
    val model = shell.drawerModel
    val focused = shell.workspace.focusedAgentId
    val conversations by remember(focused) { focused?.let(model::conversationsOf) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val drawer = ShellDrawerConversations(
        conversations = conversations,
        archiveFilter = model.archiveFilter.collectAsStateWithLifecycle().value,
        pinnedIds = model.pinnedConversationIds.collectAsStateWithLifecycle().value,
        parentIds = model.conversationParents.collectAsStateWithLifecycle().value,
        settledIds = model.settledConversationIds.collectAsStateWithLifecycle().value,
        markers = model.conversationMarkers.collectAsStateWithLifecycle().value,
    )
    val library = ShellDrawerLibrary(
        identities = LocalMascotRegistry.current.identities.toMap(),
        chatOptions = shell.chatPanel.value?.chatOptions,
        canvases = model.canvases.collectAsStateWithLifecycle().value,
        pinnedAgentIds = model.pinnedAgentIds.collectAsStateWithLifecycle().value,
        agentLastActiveAt = RecentAgents.merge(model.agentActivity.collectAsStateWithLifecycle().value, RecentAgents.lastActiveAt(agents)),
    )
    // Relative times are taken when the drawer opens; they do not tick while it is open.
    val now = remember(shell.drawer.open) { Clock.System.now() }
    return shell.workspace.workspaceNavigation(agents.map { it.id.value to it.name }, now, drawer, library)
}

/** The shared verbs, with the rows' conversation actions and the agent pins written through Android's stores. */
private fun androidNavigationActions(shell: WorkspaceShell): ShellWorkspaceNavigationActions {
    val base = shell.navigator.workspaceNavigationActions()
    val model = shell.drawerModel
    // The panel lists a focused agent's conversations only, so its rows always have one.
    val agent = { checkNotNull(shell.workspace.focusedAgentId) }
    return base.copy(
        rail = base.rail.copy(onAgentPinnedChange = { id, pinned -> model.setAgentPinned(AgentId(id), pinned) }),
        panel = base.panel.copy(
            onArchiveFilterChange = model::setArchiveFilter,
            onArchiveConversation = { id, archived -> model.setConversationArchived(ConversationId(id), agent(), archived) },
            onDeleteConversation = { id -> model.deleteConversation(ConversationId(id), agent()) },
            deleteBehavior = model.deleteBehavior,
            onRenameConversation = { id, title ->
                model.renameConversation(ConversationId(id), agent(), ConversationSummary(title))
            },
            onPinConversation = { id, pinned -> model.setConversationPinned(ConversationId(id), pinned) },
            onOpenCanvas = { canvasId -> shell.exits.openCanvasById(canvasId.value) },
            conversationMarks = model.conversationMarkActions,
            chatOptions = shell.chatPanel.value?.chatOptionsActions ?: base.panel.chatOptions,
        ),
    )
}

/** The shared keymap on Android's hardware keyboards: the workspace's verbs, without the rename dialog. */
@Composable
private fun rememberAndroidShortcuts(shell: WorkspaceShell, agents: List<Agent>): ShellShortcutBinding {
    val latestAgents by rememberUpdatedState(agents)
    return remember(shell.workspace, shell.port) {
        val actions = ShortcutActions(
            overlays = shell.workspace.overlays,
            navigate = shell.workspace::navigate,
            toggleSidebar = { shell.layout.dispatch(ShellLayoutEvent.ToggleSidebar) },
            lists = ShortcutLists(
                conversations = ShortcutStepping(
                    ids = { shell.port.conversations().map { it.id.value } },
                    current = { shell.workspace.selectedConversationId?.value },
                    open = { shell.navigator.openConversation(ConversationId(it)) },
                ),
                agents = ShortcutStepping(
                    ids = { latestAgents.map { it.id.value } },
                    current = { shell.workspace.focusedAgentId?.value },
                    open = { shell.navigator.openAgent(AgentId(it)) },
                ),
            ),
        )
        ShellShortcutBinding(ShortcutPlatform.Android, WorkspaceShortcuts - ShellShortcutId.Rename) { id ->
            handleShortcut(id, actions)
        }
    }
}

/** A row action that failed says so briefly. */
@Composable
private fun ShellFailures(model: SharedNavDrawerViewModel) {
    val context = LocalContext.current
    LaunchedEffect(model) { model.failures.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
}
