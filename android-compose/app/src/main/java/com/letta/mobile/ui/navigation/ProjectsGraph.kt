package com.letta.mobile.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.letta.mobile.ui.screens.projects.CreateProjectScreen
import com.letta.mobile.ui.screens.projects.ProjectIssueDetailScreen
import com.letta.mobile.ui.screens.projects.ProjectIssuesScreen

fun NavGraphBuilder.projectsGraph(
    navController: NavHostController,
    activeBackendLabel: String?,
    openBackendSwitcher: () -> Unit,
) {
    composable<HomeRoute> {
        val capabilities = rememberProjectsCapabilities<HomeRoute>(navController)
        if (!capabilities.projectsSupported) {
            return@composable
        }
        ProjectHomeRouteScreen(
            navController = navController,
            projectWorkSupported = capabilities.projectWorkSupported,
            onNavigateBack = null,
            activeBackendLabel = activeBackendLabel,
            onNavigateToBackendSwitcher = openBackendSwitcher,
        )
    }

    composable<ProjectIssuesRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) { backStackEntry ->
        if (!rememberProjectWorkSupportedOrPopBack(navController)) {
            return@composable
        }
        val route = backStackEntry.toRoute<ProjectIssuesRoute>()
        ProjectIssuesScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToIssue = { issueId ->
                navController.navigate(
                    ProjectIssueDetailRoute(
                        projectId = route.projectId,
                        issueId = issueId,
                        projectName = route.projectName,
                    )
                )
            },
        )
    }

    composable<ProjectIssueDetailRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        if (!rememberProjectWorkSupportedOrPopBack(navController)) {
            return@composable
        }
        ProjectIssueDetailScreen(onNavigateBack = { navController.popBackStack() })
    }

    composable<ProjectsRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        val capabilities = rememberProjectsCapabilities<ProjectsRoute>(
            navController = navController,
            tryPopBackFirst = true,
        )
        if (!capabilities.projectsSupported) {
            return@composable
        }
        ProjectHomeRouteScreen(
            navController = navController,
            projectWorkSupported = capabilities.projectWorkSupported,
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<CreateProjectRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        CreateProjectScreen(
            onNavigateBack = { navController.popBackStack() },
            onProjectCreated = { _ ->
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(PROJECT_CREATED_REFRESH_KEY, true)
                navController.popBackStack()
            },
        )
    }
}
