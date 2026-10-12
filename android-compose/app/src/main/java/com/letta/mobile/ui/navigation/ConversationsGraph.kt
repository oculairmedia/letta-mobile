package com.letta.mobile.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.letta.mobile.feature.editagent.EditAgentRoute
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
                    onNavigateToAgentList = { navController.navigate(AgentListRoute()) },
                    onCreateFirstAgent = { navController.navigate(AgentListRoute(openCreate = true)) },
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
        if (current != null && (current.agentId != detail.agentId || current.conversationId != detail.conversationId)) {
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

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.agentListDestination(navController: NavHostController) {
    composable<AgentListRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<AgentListRoute>()
        AgentListSharedTransitionHost {
            AgentListScreen(
                navigation = AgentListScreenNavigation(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAgent = { agentId, agentName, conversationId ->
                        navController.navigate(
                            AgentChatRoute(
                                agentId = agentId,
                                agentName = agentName,
                                conversationId = conversationId,
                                freshRouteKey = conversationId?.let { System.currentTimeMillis() },
                            )
                        )
                    },
                    onNavigateToSettings = { navController.navigate(ConfigRoute()) },
                    onNavigateToEditAgent = { agentId -> navController.navigate(EditAgentRoute(agentId)) },
                    openCreateOnStart = route.openCreate,
                ),
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.shareToAgentDestination(navController: NavHostController) {
    composable<ShareToAgentRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<ShareToAgentRoute>()
        AgentListSharedTransitionHost {
            AgentListScreen(
                navigation = AgentListScreenNavigation(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAgent = { agentId, agentName, conversationId ->
                        navController.navigate(
                            AgentChatRoute(
                                agentId = agentId,
                                agentName = agentName,
                                conversationId = conversationId,
                                freshRouteKey = conversationId?.let { System.currentTimeMillis() },
                                initialMessage = route.sharedText,
                            ),
                        ) {
                            popUpTo<ShareToAgentRoute> { inclusive = true }
                        }
                    },
                    onNavigateToSettings = { navController.navigate(ConfigRoute()) },
                    onNavigateToEditAgent = { agentId -> navController.navigate(EditAgentRoute(agentId)) },
                    shareContentPreview = route.sharedText,
                ),
            )
        }
    }
}

/** Provides [LocalAnimatedVisibilityScope] to the two [AgentListScreen] destinations. */
@Composable
private fun AnimatedContentScope.AgentListSharedTransitionHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
        content()
    }
}
