package com.letta.mobile.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ca.oculair.meridian.BuildConfig
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.ui.screens.about.AboutScreen
import com.letta.mobile.ui.screens.dashboard.HomeBackendLabel
import com.letta.mobile.ui.screens.dashboard.HomeChatRoutes
import com.letta.mobile.ui.screens.dashboard.HomeNavigationCallbacks
import com.letta.mobile.ui.screens.dashboard.HomeScreen
import com.letta.mobile.ui.screens.dashboard.HomeScreenSpec
import com.letta.mobile.ui.screens.memory.MemoryOverviewScreen
import com.letta.mobile.ui.screens.providers.ProviderAdminScreen
import com.letta.mobile.ui.screens.runs.RunMonitorScreen
import com.letta.mobile.ui.screens.schedules.ScheduleListScreen
import com.letta.mobile.ui.screens.systemaccess.SystemAccessDashboardScreen
import com.letta.mobile.ui.screens.telemetry.TelemetryScreen
import com.letta.mobile.ui.screens.templates.TemplatesScreen

/**
 * The Admin tab's routes. letta-mobile-ckmub.2.5 (hard cut S17): Templates and Runs are the shared
 * pages in thin containers; the REST-only admin screens (archival passages, block library, MCP
 * servers, tool editor, model browser, usage) were deleted with no App Server route behind them.
 */
fun NavGraphBuilder.adminGraph(
    navController: NavHostController,
    activeBackendLabel: String?,
    openBackendSwitcher: () -> Unit,
    clearAllData: () -> Unit,
) {
    composable<AdminRoute> {
        HomeScreen(
            HomeScreenSpec(
                navigation = adminHomeNavigation(navController),
                routes = HomeChatRoutes(
                    onNavigateToChatMessage = { agentId, conversationId, messageId ->
                        val route = AgentChatRoute(agentId = agentId, conversationId = conversationId, scrollToMessageId = messageId)
                        navController.navigate(route)
                    },
                    onNavigateToConversation = { agentId, conversationId ->
                        navController.navigate(AgentChatRoute(agentId = agentId, conversationId = conversationId))
                    },
                    onNavigateToEditAgent = { agentId -> navController.navigate(EditAgentRoute(agentId)) },
                ),
                backend = HomeBackendLabel(activeBackendLabel, openBackendSwitcher),
                title = "Admin",
            ),
        )
    }

    adminToolRoutes(navController)

    pairingRoutes(navController)

    composable<TemplatesRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        TemplatesScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToAgent = { agentId -> navController.navigate(AgentChatRoute(agentId = agentId)) },
        )
    }

    composable<ProvidersRoute> {
        ProviderAdminScreen(onNavigateBack = { navController.popBackStack() })
    }

    composable<SchedulesRoute> {
        ScheduleListScreen(onNavigateBack = { navController.popBackStack() })
    }

    composable<MemoryRoute> {
        MemoryOverviewScreen(onNavigateBack = { navController.popBackStack() })
    }

    composable<RunsRoute> {
        RunMonitorScreen(
            onNavigateBack = { navController.popBackStack() },
            onOpenConversation = { agentId, conversationId ->
                navController.navigate(AgentChatRoute(agentId = agentId, conversationId = conversationId))
            },
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
}

/** Telemetry and the system-access dashboard: local diagnostics, kept as they are. */
private fun NavGraphBuilder.adminToolRoutes(navController: NavHostController) {
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
}

/** Where the Admin home's shortcuts, drawer rows and search hits lead. */
private fun adminHomeNavigation(navController: NavHostController) = HomeNavigationCallbacks(
    onNavigateToAgents = { navController.navigate(AgentListRoute) },
    onNavigateToConversations = { navController.navigate(ConversationsRoute) },
    onNavigateToTools = { navController.navigateTo(ShellDestination.Skills(section = ShellSkillsSection.Tools)) },
    onNavigateToMemory = { navController.navigateTo(ShellDestination.Memory()) },
    onNavigateToSettings = { navController.navigate(ConfigRoute()) },
    onNavigateToChat = { agentId, agentName, initialMessage ->
        navController.navigate(AgentChatRoute(agentId = agentId, agentName = agentName, initialMessage = initialMessage))
    },
    onNavigateToTemplates = { navController.navigate(TemplatesRoute) },
    onNavigateToProviders = { navController.navigateTo(ShellDestination.Providers) },
    onNavigateToSchedules = { navController.navigate(SchedulesRoute()) },
    onNavigateToRuns = { navController.navigate(RunsRoute) },
    onNavigateToAbout = { navController.navigate(AboutRoute) },
    onNavigateToTelemetry = { navController.navigate(TelemetryRoute) },
    onNavigateToSystemAccess = { navController.navigate(SystemAccessRoute) },
    onNavigateToProjects = { navController.navigate(HomeRoute) },
)
