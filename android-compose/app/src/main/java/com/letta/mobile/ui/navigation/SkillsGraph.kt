package com.letta.mobile.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.screens.skills.SkillsScreen
import com.letta.mobile.ui.screens.skills.SkillsScreenNavigation

/**
 * letta-mobile-zxk2n.32: Skills and Tools are one shared page. [SkillsRoute] opens it on Skills;
 * [AllToolsRoute] (deep links, the dashboard shortcut, /mcp) opens the same page on its Tools anchor.
 * letta-mobile-ckmub.3.4: each route only maps to its [ShellDestination.Skills].
 */
internal fun NavGraphBuilder.skillsGraph(navController: NavHostController) {
    val navigation = SkillsScreenNavigation(
        onNavigateBack = { navController.popBackStack() },
        onNavigateToToolDetail = { toolId -> navController.navigate(ToolDetailRoute(toolId)) },
    )
    composable<SkillsRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) { entry ->
        SkillsScreen(navigation = navigation, destination = entry.toRoute<SkillsRoute>().toSkillsDestination())
    }
    composable<AllToolsRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        SkillsScreen(navigation = navigation, destination = AllToolsRoute.toSkillsDestination())
    }
}

@Suppress("NoAnyType")
private fun Any.toSkillsDestination(): ShellDestination.Skills = shellDestinationOf(this) as ShellDestination.Skills
