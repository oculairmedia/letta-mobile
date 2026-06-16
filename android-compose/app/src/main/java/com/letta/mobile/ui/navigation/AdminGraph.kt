package com.letta.mobile.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.editagent.EditAgentRoute
import com.letta.mobile.ui.screens.dashboard.HomeScreen
import com.letta.mobile.ui.screens.about.AboutScreen
import com.letta.mobile.ui.screens.archives.ArchiveAdminScreen
import com.letta.mobile.ui.screens.archival.ArchivalScreen
import com.letta.mobile.ui.screens.blocks.BlockLibraryScreen
import com.letta.mobile.ui.screens.folders.FolderAdminScreen
import com.letta.mobile.ui.screens.groups.GroupAdminScreen
import com.letta.mobile.ui.screens.identities.IdentityListScreen
import com.letta.mobile.ui.screens.jobs.JobMonitorScreen
import com.letta.mobile.ui.screens.mcp.McpScreen
import com.letta.mobile.ui.screens.mcp.McpServerToolsScreen
import com.letta.mobile.ui.screens.memory.MemoryOverviewScreen
import com.letta.mobile.ui.screens.messagebatches.MessageBatchMonitorScreen
import com.letta.mobile.ui.screens.models.ModelBrowserScreen
import com.letta.mobile.ui.screens.providers.ProviderAdminScreen
import com.letta.mobile.ui.screens.runs.RunMonitorScreen
import com.letta.mobile.ui.screens.schedules.ScheduleListScreen
import com.letta.mobile.ui.screens.templates.TemplatesScreen
import com.letta.mobile.ui.screens.tools.AllToolsScreen
import com.letta.mobile.ui.screens.tools.ToolDetailScreen
import com.letta.mobile.ui.screens.systemaccess.SystemAccessDashboardScreen
import com.letta.mobile.ui.screens.telemetry.TelemetryScreen
import com.letta.mobile.ui.screens.usage.UsageScreen

fun NavGraphBuilder.adminGraph(
    navController: NavHostController,
    activeBackendLabel: String?,
    openBackendSwitcher: () -> Unit,
    clearAllData: () -> Unit,
) {
    composable<AdminRoute> {
        HomeScreen(
            onNavigateToAgents = { navController.navigate(AgentListRoute()) },
            onNavigateToConversations = { navController.navigate(ConversationsRoute) },
            onNavigateToTools = { navController.navigate(AllToolsRoute) },
            onNavigateToBlocks = { navController.navigate(BlocksRoute) },
            onNavigateToSettings = { navController.navigate(ConfigRoute()) },
            onNavigateToChat = { agentId, agentName, initialMessage ->
                navController.navigate(
                    AgentChatRoute(
                        agentId = agentId,
                        agentName = agentName,
                        initialMessage = initialMessage,
                    )
                )
            },
            onNavigateToChatMessage = { agentId, conversationId, messageId ->
                navController.navigate(AgentChatRoute(agentId = agentId, conversationId = conversationId, scrollToMessageId = messageId))
            },
            onNavigateToEditAgent = { agentId ->
                navController.navigate(EditAgentRoute(agentId))
            },
            onNavigateToUsage = { navController.navigate(UsageRoute) },
            onNavigateToTemplates = { navController.navigate(TemplatesRoute) },
            onNavigateToArchives = { navController.navigate(ArchivesRoute) },
            onNavigateToFolders = { navController.navigate(FoldersRoute) },
            onNavigateToGroups = { navController.navigate(GroupsRoute) },
            onNavigateToProviders = { navController.navigate(ProvidersRoute) },
            onNavigateToIdentities = { navController.navigate(IdentitiesRoute) },
            onNavigateToSchedules = { navController.navigate(SchedulesRoute) },
            onNavigateToRuns = { navController.navigate(RunsRoute) },
            onNavigateToJobs = { navController.navigate(JobsRoute) },
            onNavigateToMessageBatches = { navController.navigate(MessageBatchesRoute) },
            onNavigateToMcp = { navController.navigate(McpRoute) },
            onNavigateToAbout = { navController.navigate(AboutRoute) },
            onNavigateToTelemetry = { navController.navigate(TelemetryRoute) },
            onNavigateToSystemAccess = { navController.navigate(SystemAccessRoute) },
            onNavigateToBotSettings = { },
            onNavigateToProjects = { navController.navigate(HomeRoute) },
            onNavigateToModels = { navController.navigate(ModelsRoute) },
            activeBackendLabel = activeBackendLabel,
            onNavigateToBackendSwitcher = openBackendSwitcher,
            title = "Admin",
        )
    }

    composable<UsageRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        UsageScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToRuns = { navController.navigate(RunsRoute) },
        )
    }

    composable<TelemetryRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        TelemetryScreen(onBack = { navController.popBackStack() })
    }

    composable<SystemAccessRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        SystemAccessDashboardScreen(onNavigateBack = { navController.popBackStack() })
    }

    composable<TemplatesRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        TemplatesScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToAgent = { agentId ->
                navController.navigate(AgentChatRoute(agentId = agentId))
            },
            onNavigateToAgentList = {
                navController.navigate(AgentListRoute())
            },
        )
    }

    composable<ArchivesRoute> {
        ArchiveAdminScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<FoldersRoute> {
        FolderAdminScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<GroupsRoute> {
        GroupAdminScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<ProvidersRoute> {
        ProviderAdminScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<BlocksRoute> {
        BlockLibraryScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<IdentitiesRoute> {
        IdentityListScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<SchedulesRoute> {
        ScheduleListScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<MemoryRoute> {
        MemoryOverviewScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<RunsRoute> {
        RunMonitorScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<JobsRoute> {
        JobMonitorScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<MessageBatchesRoute> {
        MessageBatchMonitorScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<McpRoute> {
        McpScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToServerTools = { serverId ->
                navController.navigate(McpServerToolsRoute(serverId))
            }
        )
    }

    composable<McpServerToolsRoute> {
        McpServerToolsScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<AboutRoute> {
        AboutScreen(
            onNavigateBack = { navController.popBackStack() },
            onLogout = {
                clearAllData()
                navController.navigate(ConfigRoute()) {
                    popUpTo(0) { inclusive = true }
                }
            },
        )
    }

    composable<AllToolsRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
            AllToolsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToToolDetail = { toolId ->
                    navController.navigate(ToolDetailRoute(toolId))
                },
            )
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    composable<ToolDetailRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
            ToolDetailScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }

    composable<ModelsRoute> {
        ModelBrowserScreen(
            onNavigateBack = { navController.popBackStack() },
            onModelSelected = { modelId ->
                navController.previousBackStackEntry?.savedStateHandle?.set("selectedModel", modelId)
                navController.popBackStack()
            }
        )
    }

    composable<ArchivalRoute> {
        ArchivalScreen(
            onNavigateBack = { navController.popBackStack() }
        )
    }
}
