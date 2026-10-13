package com.letta.mobile.ui.screens.dashboard

import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.ui.shell.pages.home.HomePageNavigation

internal data class HomeNavigationCallbacks(
    val onNavigateToAgents: () -> Unit,
    val onNavigateToConversations: () -> Unit,
    val onNavigateToTools: () -> Unit,
    /** Blocks are the agent's memory files: the shortcut and a block search hit open Memory. */
    val onNavigateToMemory: () -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onNavigateToChat: (agentId: String, agentName: String?, initialMessage: String?) -> Unit,
    val onNavigateToTemplates: () -> Unit = {},
    /** Providers, and the models the picker shows (the Models shortcut opens it too). */
    val onNavigateToProviders: () -> Unit = {},
    val onNavigateToSchedules: () -> Unit = {},
    val onNavigateToRuns: () -> Unit = {},
    val onNavigateToAbout: () -> Unit = {},
    val onNavigateToTelemetry: () -> Unit = {},
    val onNavigateToSystemAccess: () -> Unit = {},
    val onNavigateToBotSettings: () -> Unit = {},
    val onNavigateToProjects: () -> Unit = {},
) {
    fun shortcutNavigator(shortcut: DashboardShortcut, favorite: HomeAgentRef?): () -> Unit = when (shortcut) {
        DashboardShortcut.CONVERSATIONS -> onNavigateToConversations
        DashboardShortcut.AGENTS -> onNavigateToAgents
        DashboardShortcut.TOOLS -> onNavigateToTools
        DashboardShortcut.BLOCKS -> onNavigateToMemory
        DashboardShortcut.TEMPLATES -> onNavigateToTemplates
        DashboardShortcut.PROVIDERS, DashboardShortcut.MODELS -> onNavigateToProviders
        DashboardShortcut.SCHEDULES -> onNavigateToSchedules
        DashboardShortcut.RUNS -> onNavigateToRuns
        DashboardShortcut.BOT_SETTINGS -> onNavigateToBotSettings
        DashboardShortcut.PROJECTS -> onNavigateToProjects
        DashboardShortcut.FAVORITE_AGENT -> favorite?.let { { onNavigateToChat(it.id, it.name, null) } } ?: onNavigateToAgents
        DashboardShortcut.SETTINGS -> onNavigateToSettings
        DashboardShortcut.TELEMETRY -> onNavigateToTelemetry
        DashboardShortcut.SYSTEM_ACCESS -> onNavigateToSystemAccess
        DashboardShortcut.ABOUT -> onNavigateToAbout
    }
}

/** The drawer's shortcut and the shared page's shortcut share their persistence name. */
internal fun DashboardShortcut.toHomeShortcut(): HomeShortcut = HomeShortcut.valueOf(name)

internal fun HomeShortcut.toDashboardShortcut(): DashboardShortcut? = DashboardShortcut.entries.firstOrNull { it.name == name }

/** The shared page's shortcuts Android can open: the drawer's own (letta-mobile-ckmub.2.5). */
internal val AndroidHomeShortcuts: Set<HomeShortcut> = DashboardShortcut.entries.mapTo(mutableSetOf()) { it.toHomeShortcut() }

/** Opens a conversation (or, without one, a chat) with the agent at [agentId]. */
internal data class HomeChatRoutes(
    val onNavigateToChatMessage: (agentId: String, conversationId: String, messageId: String) -> Unit,
    val onNavigateToConversation: (agentId: String, conversationId: String) -> Unit,
    val onNavigateToEditAgent: (agentId: String) -> Unit,
)

/**
 * Android's navigation for the shared Home page. The composer talks to the favorite agent (the
 * dashboard's quick chat), else to the agent of the newest conversation, else opens the agent list.
 */
internal fun HomeNavigationCallbacks.homePageNavigation(state: HomePageState, routes: HomeChatRoutes): HomePageNavigation =
    HomePageNavigation(
        onSubmitPrompt = { text ->
            val target = state.favorite ?: state.fleet.recent.firstNotNullOfOrNull { recent ->
                recent.agentId?.let { HomeAgentRef(it, recent.agentName) }
            }
            if (target != null) onNavigateToChat(target.id, target.name, text) else onNavigateToAgents()
        },
        onOpenConversation = { recent ->
            val agentId = recent.agentId
            if (agentId != null) routes.onNavigateToConversation(agentId, recent.conversationId) else onNavigateToConversations()
        },
        onOpenAgent = { agentId ->
            val name = state.fleet.agents.firstOrNull { it.agentId == agentId }?.name
            onNavigateToChat(agentId, name, null)
        },
        onOpenShortcut = { shortcut -> shortcut.toDashboardShortcut()?.let { shortcutNavigator(it, state.favorite)() } },
        onConfigureAgent = routes.onNavigateToEditAgent,
        onOpenMessage = { message ->
            val agentId = message.agentId
            val conversationId = message.conversationId
            val messageId = message.messageId
            when {
                agentId == null -> Unit
                conversationId != null && messageId != null -> routes.onNavigateToChatMessage(agentId, conversationId, messageId)
                else -> onNavigateToChat(agentId, null, null)
            }
        },
        onOpenTool = { onNavigateToTools() },
        onOpenBlock = { onNavigateToMemory() },
    )
