package com.letta.mobile.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/**
 * letta-mobile-ckmub.2.5 (hard cut S17): the Home tab's projects catalog (the shared Projects page)
 * and the same page pushed from elsewhere. Project issues have no App Server route and were deleted.
 */
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
            onNavigateBack = null,
            backend = ProjectsBackendLabel(activeBackendLabel, openBackendSwitcher),
        )
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
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
