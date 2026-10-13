package com.letta.mobile.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ca.oculair.meridian.BuildConfig
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.ui.screens.dashboard.HomeScreen
import com.letta.mobile.ui.screens.about.AboutScreen
import com.letta.mobile.ui.screens.archival.ArchivalScreen
import com.letta.mobile.ui.screens.blocks.BlockLibraryScreen
import com.letta.mobile.ui.screens.mcp.McpScreen
import com.letta.mobile.ui.screens.mcp.McpServerToolsScreen
import com.letta.mobile.ui.screens.memory.MemoryOverviewScreen
import com.letta.mobile.ui.screens.models.ModelBrowserScreen
import com.letta.mobile.ui.screens.providers.ProviderAdminScreen
import com.letta.mobile.ui.screens.runs.RunMonitorScreen
import com.letta.mobile.ui.screens.schedules.ScheduleListScreen
import com.letta.mobile.ui.screens.templates.TemplatesScreen
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
            onNavigateToAgents = { navController.navigate(AgentListRoute) },
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
            onNavigateToEditAgent = { agentId -> navController.navigate(EditAgentRoute(agentId)) },
            onNavigateToConversation = { agentId, conversationId -> navController.navigate(AgentChatRoute(agentId = agentId, conversationId = conversationId)) },
            onNavigateToUsage = { navController.navigate(UsageRoute) },
            onNavigateToTemplates = { navController.navigate(TemplatesRoute) },
            onNavigateToProviders = { navController.navigate(ProvidersRoute) },
            onNavigateToSchedules = { navController.navigate(SchedulesRoute()) },
            onNavigateToRuns = { navController.navigate(RunsRoute) },
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
        SystemAccessDashboardScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToPairingScan = { navController.navigate(PairingScanRoute) },
            onNavigateToPairDevice = { navController.navigate(PairingInviteRoute) },
        )
    }

    pairingRoutes(navController)

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
                navController.navigate(AgentListRoute)
            },
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
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    skillsGraph(navController)

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
