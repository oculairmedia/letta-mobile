package com.letta.mobile.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.editagent.EditAgentRoute
import com.letta.mobile.ui.screens.agentlist.AgentListScreen
import com.letta.mobile.ui.screens.agentlist.AgentListScreenNavigation
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
    val drawerCallbacks = conversationsDrawerCallbacks(
        navController = navController,
        activeBackendLabel = activeBackendLabel,
        openBackendSwitcher = openBackendSwitcher,
    )
    conversationsRootDestination(navController, drawerCallbacks)
    agentListDestination(navController)
    shareToAgentDestination(navController)
}

/**
 * Shared drawer / bottom-nav callbacks used by both the compact
 * [ConversationsScreen] and the expanded [TwoPaneConversationsLayout]. Bundling
 * these keeps the parent [conversationsGraph] short and removes the
 * near-duplicated per-branch parameter list that previously drove
 * [conversationsGraph] over CodeScene's Large Method threshold.
 */
internal class ConversationsDrawerCallbacks(
    val activeBackendLabel: String?,
    val onNavigateToBackendSwitcher: () -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onNavigateToAgentList: () -> Unit,
    val onNavigateToTemplates: () -> Unit,
    val onNavigateToArchives: () -> Unit,
    val onNavigateToFolders: () -> Unit,
    val onNavigateToGroups: () -> Unit,
    val onNavigateToProviders: () -> Unit,
    val onNavigateToBlocks: () -> Unit,
    val onNavigateToIdentities: () -> Unit,
    val onNavigateToSchedules: () -> Unit,
    val onNavigateToRuns: () -> Unit,
    val onNavigateToJobs: () -> Unit,
    val onNavigateToMessageBatches: () -> Unit,
    val onNavigateToMcp: () -> Unit,
    val onNavigateToAbout: () -> Unit,
    val onNavigateToProjects: () -> Unit,
    val onCreateFirstAgent: () -> Unit,
)

private fun conversationsDrawerCallbacks(
    navController: NavHostController,
    activeBackendLabel: String?,
    openBackendSwitcher: () -> Unit,
): ConversationsDrawerCallbacks = ConversationsDrawerCallbacks(
    activeBackendLabel = activeBackendLabel,
    onNavigateToBackendSwitcher = openBackendSwitcher,
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
    onNavigateToProjects = { navController.navigate(HomeRoute) },
    onCreateFirstAgent = { navController.navigate(AgentListRoute(openCreate = true)) },
)

@OptIn(ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.conversationsRootDestination(
    navController: NavHostController,
    drawerCallbacks: ConversationsDrawerCallbacks,
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
                ExpandedConversationsPane(navController, drawerCallbacks)
            } else {
                CompactConversationsPane(navController, drawerCallbacks)
            }
        }
    }
}

@Composable
private fun ExpandedConversationsPane(
    navController: NavHostController,
    drawerCallbacks: ConversationsDrawerCallbacks,
) = with(drawerCallbacks) {
    TwoPaneConversationsLayout(
        outerNavController = navController,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToAgentList = onNavigateToAgentList,
        onNavigateToTemplates = onNavigateToTemplates,
        onNavigateToArchives = onNavigateToArchives,
        onNavigateToFolders = onNavigateToFolders,
        onNavigateToGroups = onNavigateToGroups,
        onNavigateToProviders = onNavigateToProviders,
        onNavigateToBlocks = onNavigateToBlocks,
        onNavigateToIdentities = onNavigateToIdentities,
        onNavigateToSchedules = onNavigateToSchedules,
        onNavigateToRuns = onNavigateToRuns,
        onNavigateToJobs = onNavigateToJobs,
        onNavigateToMessageBatches = onNavigateToMessageBatches,
        onNavigateToMcp = onNavigateToMcp,
        onNavigateToAbout = onNavigateToAbout,
        onNavigateToBotSettings = { },
        onNavigateToProjects = onNavigateToProjects,
        activeBackendLabel = activeBackendLabel,
        onNavigateToBackendSwitcher = onNavigateToBackendSwitcher,
        onCreateFirstAgent = onCreateFirstAgent,
    )
}

@Composable
private fun CompactConversationsPane(
    navController: NavHostController,
    drawerCallbacks: ConversationsDrawerCallbacks,
) = with(drawerCallbacks) {
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
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToAgentList = onNavigateToAgentList,
        onNavigateToTemplates = onNavigateToTemplates,
        onNavigateToArchives = onNavigateToArchives,
        onNavigateToFolders = onNavigateToFolders,
        onNavigateToGroups = onNavigateToGroups,
        onNavigateToProviders = onNavigateToProviders,
        onNavigateToBlocks = onNavigateToBlocks,
        onNavigateToIdentities = onNavigateToIdentities,
        onNavigateToSchedules = onNavigateToSchedules,
        onNavigateToRuns = onNavigateToRuns,
        onNavigateToJobs = onNavigateToJobs,
        onNavigateToMessageBatches = onNavigateToMessageBatches,
        onNavigateToMcp = onNavigateToMcp,
        onNavigateToAbout = onNavigateToAbout,
        onNavigateToBotSettings = { },
        onNavigateToProjects = onNavigateToProjects,
        activeBackendLabel = activeBackendLabel,
        onNavigateToBackendSwitcher = onNavigateToBackendSwitcher,
        onCreateFirstAgent = onCreateFirstAgent,
    )
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
                    onNavigateToEditAgent = { agentId ->
                        navController.navigate(EditAgentRoute(agentId))
                    },
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
                    onNavigateToEditAgent = { agentId ->
                        navController.navigate(EditAgentRoute(agentId))
                    },
                    shareContentPreview = route.sharedText,
                ),
            )
        }
    }
}

/**
 * Wraps a composable in the [LocalAnimatedVisibilityScope] provider shared by
 * the two [AgentListScreen] destinations. Both agent-list variants need this
 * host for their shared-element transitions; extracting it avoids repeating
 * the same [CompositionLocalProvider] block twice.
 */
@Composable
private fun AnimatedContentScope.AgentListSharedTransitionHost(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
        content()
    }
}
