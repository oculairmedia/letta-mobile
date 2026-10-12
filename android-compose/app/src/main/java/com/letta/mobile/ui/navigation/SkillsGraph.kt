package com.letta.mobile.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.letta.mobile.ui.screens.skills.SkillsScreen
import com.letta.mobile.ui.screens.skills.SkillsScreenNavigation
import com.letta.mobile.ui.shell.pages.skills.SkillsPageSection

/**
 * letta-mobile-zxk2n.32: Skills and Tools are one shared page. [SkillsRoute] opens it on Skills;
 * [AllToolsRoute] (deep links, the dashboard shortcut, /mcp) opens the same page on its Tools section.
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
    ) {
        SkillsScreen(navigation = navigation, initialSection = SkillsPageSection.Skills)
    }
    composable<AllToolsRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        SkillsScreen(navigation = navigation, initialSection = SkillsPageSection.Tools)
    }
}
