package com.letta.mobile.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.editagent.EditAgentRoute
import com.letta.mobile.ui.screens.agentlist.AgentListScreen
import com.letta.mobile.ui.screens.conversations.ConversationsScreen
import com.letta.mobile.ui.screens.conversations.TwoPaneConversationsLayout
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth

@OptIn(ExperimentalSharedTransitionApi::class)
fun NavGraphBuilder.conversationsGraph(
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
            val windowSizeClass = LocalWindowSizeClass.current
            if (windowSizeClass.isExpandedWidth) {
                TwoPaneConversationsLayout(
                    outerNavController = navController,
                    onNavigateToSettings = { navController.navigate(ConfigRoute()) },
                    onNavigateToAgentList = { navController.navigate(AgentListRoute()) },
                    onNavigateToTemplates = { navController.navigate(TemplatesRoute) },
                    onNavigateToArchives = { navController.navigate(ArchivesRoute) },
                    onNavigateToFolders = { navController.navigate(FoldersRoute) },
                    onNavigateToGroups = { navController.navigate(GroupsRoute) },
                    onNavigateToProviders = { navController.navigate(ProvidersRoute) },
                    onNavigateToBlocks = { navController.navigate(BlocksRoute) },
                    onNavigateToIdentities = { navController.navigate(IdentitiesRoute) },
                    onNavigateToSchedules = { navController.navigate(SchedulesRoute()) },
                    onNavigateToRuns = { navController.navigate(RunsRoute) },
                    onNavigateToJobs = { navController.navigate(JobsRoute) },
                    onNavigateToMessageBatches = { navController.navigate(MessageBatchesRoute) },
                    onNavigateToMcp = { navController.navigate(McpRoute) },
                    onNavigateToAbout = { navController.navigate(AboutRoute) },
                    onNavigateToBotSettings = { },
                    onNavigateToProjects = { navController.navigate(HomeRoute) },
                    activeBackendLabel = activeBackendLabel,
                    onNavigateToBackendSwitcher = openBackendSwitcher,
                    onCreateFirstAgent = { navController.navigate(AgentListRoute(openCreate = true)) },
                )
            } else {
                ConversationsScreen(
                    onNavigateToChat = { agentId, conversationId, agentName ->
                        navController.navigate(
                            AgentChatRoute(
                                agentId = agentId,
                                agentName = agentName,
                                conversationId = conversationId,
                            )
                        )
                    },
                    onNavigateToSettings = { navController.navigate(ConfigRoute()) },
                    onNavigateToAgentList = { navController.navigate(AgentListRoute()) },
                    onNavigateToTemplates = { navController.navigate(TemplatesRoute) },
                    onNavigateToArchives = { navController.navigate(ArchivesRoute) },
                    onNavigateToFolders = { navController.navigate(FoldersRoute) },
                    onNavigateToGroups = { navController.navigate(GroupsRoute) },
                    onNavigateToProviders = { navController.navigate(ProvidersRoute) },
                    onNavigateToBlocks = { navController.navigate(BlocksRoute) },
                    onNavigateToIdentities = { navController.navigate(IdentitiesRoute) },
                    onNavigateToSchedules = { navController.navigate(SchedulesRoute()) },
                    onNavigateToRuns = { navController.navigate(RunsRoute) },
                    onNavigateToJobs = { navController.navigate(JobsRoute) },
                    onNavigateToMessageBatches = { navController.navigate(MessageBatchesRoute) },
                    onNavigateToMcp = { navController.navigate(McpRoute) },
                    onNavigateToAbout = { navController.navigate(AboutRoute) },
                    onNavigateToBotSettings = { },
                    onNavigateToProjects = { navController.navigate(HomeRoute) },
                    activeBackendLabel = activeBackendLabel,
                    onNavigateToBackendSwitcher = openBackendSwitcher,
                    onCreateFirstAgent = { navController.navigate(AgentListRoute(openCreate = true)) },
                )
            }
        }
    }

    composable<AgentListRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<AgentListRoute>()
        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
            AgentListScreen(
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
                onNavigateToEditAgent = { agentId ->
                    navController.navigate(EditAgentRoute(agentId))
                },
                openCreateOnStart = route.openCreate,
            )
        }
    }

    composable<ShareToAgentRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<ShareToAgentRoute>()
        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
            AgentListScreen(
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
                onNavigateToEditAgent = { agentId ->
                    navController.navigate(EditAgentRoute(agentId))
                },
                shareContentPreview = route.sharedText,
            )
        }
    }
}
