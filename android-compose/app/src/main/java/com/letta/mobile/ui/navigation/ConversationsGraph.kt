package com.letta.mobile.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.chat.route.toViewModelKey
import com.letta.mobile.feature.chat.screen.AgentScaffold
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.screens.agentlist.AgentListScreen
import com.letta.mobile.ui.screens.agentlist.AgentListScreenNavigation
import com.letta.mobile.ui.screens.conversations.ConversationsDetail
import com.letta.mobile.ui.screens.conversations.ConversationsDetailPane
import com.letta.mobile.ui.screens.conversations.ConversationsScreen
import com.letta.mobile.ui.screens.conversations.ConversationsScreenNavigation

@OptIn(ExperimentalSharedTransitionApi::class)
fun NavGraphBuilder.conversationsGraph(
    navController: NavHostController,
    activeBackendLabel: String?,
    openBackendSwitcher: () -> Unit,
) {
    conversationsRootDestination(navController, activeBackendLabel, openBackendSwitcher)
    agentListDestination(navController)
    shareToAgentDestination(navController)
}

/**
 * letta-mobile-ckmub.2.4 (hard cut S16): the Chat tab's root, the shared conversations page. A narrow
 * window opens a chat as its own route; a wide one opens it beside the list ([conversationsDetailPane]).
 */
private fun NavGraphBuilder.conversationsRootDestination(
    navController: NavHostController,
    activeBackendLabel: String?,
    openBackendSwitcher: () -> Unit,
) {
    composable<ConversationsRoute>(
        enterTransition = drillInPopEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
            ConversationsScreen(
                navigation = ConversationsScreenNavigation(
                    onOpenChat = { agentId, conversationId, agentName ->
                        navController.openResidentChatOrNavigate(chatRoute(agentId, conversationId, agentName))
                    },
                    onNavigateToSettings = { navController.navigate(ConfigRoute()) },
                    onNavigateToAgentList = { navController.navigate(AgentListRoute) },
                    onCreateFirstAgent = { navController.navigateTo(ShellDestination.AgentEditor()) },
                    activeBackendLabel = activeBackendLabel,
                    onNavigateToBackendSwitcher = openBackendSwitcher,
                ),
                detailPane = conversationsDetailPane(navController),
            )
        }
    }
}

/** A chat route; a null conversation is a new chat, keyed fresh so it never reuses another's state. */
private fun chatRoute(agentId: String, conversationId: String?, agentName: String?) = AgentChatRoute(
    agentId = agentId,
    agentName = agentName,
    conversationId = conversationId,
    freshRouteKey = if (conversationId == null) System.currentTimeMillis() else null,
)

/**
 * The chat beside the wide list: its own small nav host, so switching chats replaces the chat (and
 * its view model) instead of stacking them; everything else the chat opens goes to [outer].
 */
private fun conversationsDetailPane(outer: NavController) = ConversationsDetailPane { detail, onClose, onOpen ->
    if (detail == null) {
        LettaEmptyHint("Select a conversation")
    } else {
        ConversationsDetailHost(outer, detail, onClose, onOpen)
    }
}

@Composable
private fun ConversationsDetailHost(
    outer: NavController,
    detail: ConversationsDetail,
    onClose: () -> Unit,
    onOpen: (ConversationsDetail) -> Unit,
) {
    val detailNav = rememberNavController()
    // The start is fixed for the host's life (a new start would rebuild its graph); later picks navigate.
    val start = remember { chatRoute(detail.agentId, detail.conversationId, detail.agentName) }
    LaunchedEffect(detail) {
        val current = detailNav.currentBackStackEntry?.toRoute<AgentChatRoute>()
        if (current != null && !current.shows(detail)) {
            detailNav.navigate(chatRoute(detail.agentId, detail.conversationId, detail.agentName)) { popUpTo(0) { inclusive = true } }
        }
    }
    NavHost(navController = detailNav, startDestination = start) {
        composable<AgentChatRoute> { entry ->
            val route = entry.toRoute<AgentChatRoute>()
            AgentScaffold(
                onNavigateBack = onClose,
                onNavigateToSettings = { agentId -> outer.navigateTo(ShellDestination.AgentEditor(agentId)) },
                onNavigateToArchival = { agentId -> outer.navigate(ArchivalRoute(agentId)) },
                onNavigateToTools = { outer.navigateTo(ShellDestination.Skills(section = ShellSkillsSection.Tools)) },
                onNavigateToSkills = { agentId -> outer.navigateTo(ShellDestination.Skills(agentId)) },
                onNavigateToMemory = { agentId -> outer.navigateTo(ShellDestination.Memory(agentId)) },
                onNavigateToAdmin = { outer.navigateTo(ShellDestination.Home) },
                onNavigateToProviders = { outer.navigateTo(ShellDestination.Providers) },
                onNavigateToProjects = { outer.navigate(HomeRoute) },
                // The list is already beside the chat.
                onNavigateToConversationList = onClose,
                onSwitchConversation = { agentId, conversationId, agentName ->
                    onOpen(ConversationsDetail(agentId, conversationId?.takeIf { it.isNotBlank() }, agentName))
                },
                viewModelKey = route.toViewModelKey(),
            )
        }
    }
}


/** Whether this chat route already shows [detail]'s conversation. */
private fun AgentChatRoute.shows(detail: ConversationsDetail): Boolean =
    agentId == detail.agentId && conversationId == detail.conversationId

/** What one agent-list destination opens with: text shared into the app, if any. */
private data class AgentListEntry(val sharedText: String? = null)

private fun NavGraphBuilder.agentListDestination(navController: NavHostController) =
    agentListComposable<AgentListRoute>(navController) { AgentListEntry() }

/** The share sheet's target: the same agent list, opened to pick who the shared text goes to. */
private fun NavGraphBuilder.shareToAgentDestination(navController: NavHostController) =
    agentListComposable<ShareToAgentRoute>(navController) { AgentListEntry(sharedText = it.toRoute<ShareToAgentRoute>().sharedText) }

/** One agent-list destination of route [T]; [entryOf] reads what it opens with from its back stack entry. */
private inline fun <reified T : Any> NavGraphBuilder.agentListComposable(
    navController: NavHostController,
    crossinline entryOf: (NavBackStackEntry) -> AgentListEntry,
) {
    composable<T>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) { backStackEntry ->
        val entry = entryOf(backStackEntry)
        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
            AgentListScreen(navigation = agentListNavigation(navController, entry))
        }
    }
}

private fun agentListNavigation(navController: NavHostController, entry: AgentListEntry) = AgentListScreenNavigation(
    onNavigateBack = { navController.popBackStack() },
    onNavigateToAgent = { agentId, agentName, conversationId ->
        val route = AgentChatRoute(
            agentId = agentId,
            agentName = agentName,
            conversationId = conversationId,
            freshRouteKey = conversationId?.let { System.currentTimeMillis() },
            initialMessage = entry.sharedText,
        )
        // A share hands its text to the chat once: the picker leaves the back stack.
        navController.navigate(route) { if (entry.sharedText != null) popUpTo<ShareToAgentRoute> { inclusive = true } }
    },
    onNavigateToEditAgent = { agentId -> navController.navigateTo(ShellDestination.AgentEditor(agentId)) },
    onNavigateToNewAgent = { navController.navigateTo(ShellDestination.AgentEditor()) },
    shareContentPreview = entry.sharedText,
)