package com.letta.mobile.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.letta.mobile.ui.screens.config.ConfigListScreen
import com.letta.mobile.ui.screens.config.ConfigScreen
import com.letta.mobile.ui.screens.config.VibesyncDebugScreen

fun NavGraphBuilder.configGraph(
    navController: NavHostController,
) {
    composable<ConfigRoute> {
        ConfigScreen(
            onNavigateBack = {
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else {
                    navController.navigate(AdminRoute) {
                        popUpTo<ConfigRoute> { inclusive = true }
                    }
                }
            },
            onNavigateToConfigList = {
                navController.navigate(ConfigListRoute)
            },
            onNavigateToSystemAccess = {
                navController.navigate(SystemAccessRoute)
            },
            onNavigateToVibesyncDebug = {
                navController.navigate(VibesyncDebugRoute)
            },
            onNavigateToCanvasDebug = {
                navController.navigate(CanvasDebugRoute)
            },
        )
    }

    composable<VibesyncDebugRoute> {
        VibesyncDebugScreen(onNavigateBack = { navController.popBackStack() })
    }

    composable<CanvasDebugRoute> {
        com.letta.mobile.ui.canvas.CanvasWorkspace(
            initialJson = com.letta.mobile.ui.canvas.CanvasSamples.buildCycleJson,
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<ConfigListRoute> {
        ConfigListScreen(
            onNavigateBack = { navController.popBackStack() }
        )
    }
}
