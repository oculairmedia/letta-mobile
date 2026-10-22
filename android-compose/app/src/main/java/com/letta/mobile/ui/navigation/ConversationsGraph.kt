package com.letta.mobile.ui.navigation

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.ui.screens.plugins.AndroidPluginPackagePicker
import com.letta.mobile.ui.settings.BackendConnectionNoticeActions
import com.letta.mobile.ui.shell.pages.plugins.PluginsPageBinding
import com.letta.mobile.ui.shell.pages.plugins.shellPluginsPage
import com.letta.mobile.ui.shell.workspace.ShellChatWaiting
import com.letta.mobile.ui.shell.workspace.ShellChatWaitingPage
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.chat.screen.WorkspaceAgentChat
import com.letta.mobile.feature.chat.screen.WorkspaceChatHost
import com.letta.mobile.di.pluginViewsOf
import com.letta.mobile.ui.chat.subagents.BackgroundTasksSidePane
import com.letta.mobile.ui.chat.tools.ProvidePluginToolPresenters
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.rememberShellPageDeps
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayActions
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayData
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlays
import com.letta.mobile.ui.shell.workspace.openSearchHit

/** The page beside the workspace's navigation: the shared page for its destination, over the session graph. */
@Composable
internal fun AndroidShellPage(shell: WorkspaceShell, modifier: Modifier) {
    val graph by shell.drawerModel.sources.sessions.currentGraph.collectAsStateWithLifecycle()
    val deps = rememberShellPageDeps(graph, shell.controllers, androidPageHost(shell))
    ShellPage(destination = shell.workspace.destination, deps = deps, modifier = modifier)
}

/** Android's chat page (letta-mobile-ckmub.2): the focused agent's chat; with no agent yet, the fallback page. */
internal fun androidChatPage(shell: WorkspaceShell): ShellPageContent<ShellDestination.Chat>? {
    val agent = shell.workspace.focusedAgentId ?: return null
    return ShellPageContent { _, modifier -> AndroidChatPage(shell, agent, modifier) }
}

/**
 * The chat the shell's verbs chose ([AndroidShellChatPort.opening]), else the workspace's selection
 * (a reload, a link): its route picks the chat's view model, so a new chat keeps its page once it exists.
 */
@Composable
private fun AndroidChatPage(shell: WorkspaceShell, agent: AgentId, modifier: Modifier) {
    val port = shell.port
    val conversation = shell.workspace.selectedConversationId
    val adopted = remember(agent, conversation) { port.adopt(agent, conversation) }
    val opening = port.openingFor(agent, conversation) ?: adopted
    val route = remember(opening) {
        AgentChatRoute(
            agentId = opening.agent.value,
            conversationId = opening.conversation?.value,
            freshRouteKey = opening.fresh,
            initialMessage = opening.firstMessage,
        )
    }
    // The enabled plugins' tool and command presenters draw their tools' steps (letta-mobile-2ccpv).
    val context = LocalContext.current
    val plugins = remember(context) { pluginViewsOf(context) }
    ProvidePluginToolPresenters(plugins) {
        Box(modifier) { WorkspaceAgentChat(route, chatHost(shell, opening)) }
    }
}

private fun chatHost(shell: WorkspaceShell, opening: AndroidShellChatPort.Opening) = WorkspaceChatHost(
    // A library board opens full screen (Android's board route); everything else is a shell page.
    open = { destination ->
        val board = (destination as? ShellDestination.Canvas)?.canvasId
        if (board != null) shell.exits.openCanvasById(board) else shell.navigator.navigate(destination)
    },
    openConversation = { agentId, conversationId ->
        if (conversationId == null) {
            shell.navigator.openNewChat(AgentId(agentId))
        } else {
            shell.port.open(AgentId(agentId), ConversationId(conversationId))
            shell.workspace.showChat()
        }
    },
    openAgentPane = { showNavigation(shell) },
    openCanvas = { agentId, conversationId, shareRecipient -> shell.exits.openCanvas(agentId, conversationId, shareRecipient) },
    onConversationShown = { _, conversationId -> shell.port.conversationShown(opening, ConversationId(conversationId)) },
    draft = shell.port.draft,
    openSearch = { shell.workspace.overlays.commandPalette = true },
    drawer = shell.drawerModel,
    panel = shell.chatPanel,
)

/** Brings the agents back: the drawer on a phone, the sidebar on a wide window. */
internal fun showNavigation(shell: WorkspaceShell) {
    shell.drawer.open = true
    shell.layout.dispatch(ShellLayoutEvent.SetSidebarCollapsed(false))
}

/** The open conversation's background tasks, beside the page while the person keeps them open. */
@Composable
internal fun AndroidBackgroundTasks(shell: WorkspaceShell) {
    val workspace = shell.workspace
    if (!workspace.showBackgroundTasks) return
    val agent = workspace.focusedAgentId ?: return
    val conversation = workspace.selectedConversationId ?: return
    val graph by shell.drawerModel.sources.sessions.currentGraph.collectAsStateWithLifecycle()
    val subagents by remember(graph, agent, conversation) {
        graph.subagentRepository.activeSubagentsFlow(SubagentParentScope(agent.value, conversation.value))
    }.collectAsStateWithLifecycle(emptyList())
    BackgroundTasksSidePane(subagents = subagents, onClose = { workspace.showBackgroundTasks = false })
}

/** The workspace's overlays: the New conversation directory, the command palette and the shortcut help. */
@Composable
internal fun AndroidShellOverlays(shell: WorkspaceShell, agents: List<com.letta.mobile.data.model.Agent>) {
    val workspace = shell.workspace
    ShellWorkspaceOverlays(
        visibility = workspace.overlays,
        data = ShellWorkspaceOverlayData(
            searchSources = UnifiedSearchSources(agents = agents, agentId = workspace.focusedAgentId?.value),
            railAgents = agents.map { it.id.value to it.name },
            rosterAgents = agents,
        ),
        actions = ShellWorkspaceOverlayActions(
            // A share into the app waits for its agent here, and starts a new chat with it.
            onOpenAgent = { id -> if (!shell.port.deliverShare(AgentId(id))) shell.navigator.openAgent(AgentId(id)) },
            onNewAgent = workspace::openNewAgent,
            onOpenSearchHit = { hit -> shell.navigator.openSearchHit(hit, openCanvas = { shell.exits.openCanvasById(it.value) }) },
            onNewCanvas = { shell.exits.openCanvas(workspace.focusedAgentId?.value, null, null) },
        ),
    )
}

/** A chat with no agent to open yet: the connection, and the way on. */
internal fun androidWaitingPage(shell: WorkspaceShell, connection: BackendConnectionStatus) =
    ShellPageContent<ShellDestination> { _, modifier ->
    ShellChatWaitingPage(
        waiting = ShellChatWaiting(connection = connection, opening = connection == BackendConnectionStatus.Connecting),
        actions = BackendConnectionNoticeActions(
            onOpenSettings = { shell.workspace.navigate(ShellDestination.Settings()) },
            onShowAgents = { showNavigation(shell) },
            onRetry = { shell.drawerModel.sources.sessions.rebuild() },
        ),
        modifier = modifier,
    )
}

/** The shared Plugins page over the app's plugin views, with the system file picker. */
internal fun androidPluginsPage(model: PluginViewsModel, onBack: () -> Unit) =
    ShellPageContent<ShellDestination.Plugins> { destination, modifier ->
        val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
        val context = LocalContext.current
        val pick = remember(registry, context) { AndroidPluginPackagePicker(registry, context.contentResolver)::pick }
        LaunchedEffect(model) { model.refresh() }
        val binding = remember(model, pick) { PluginsPageBinding(model.catalog, pick, model.liveViews) }
        shellPluginsPage(binding, onBack).Content(destination, modifier)
    }
