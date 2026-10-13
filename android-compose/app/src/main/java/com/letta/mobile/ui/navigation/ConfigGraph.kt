package com.letta.mobile.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.letta.mobile.ui.screens.config.ConfigListScreen
import com.letta.mobile.ui.screens.config.SettingsDestinations
import com.letta.mobile.ui.screens.config.SettingsScreen

fun NavGraphBuilder.configGraph(
    navController: NavHostController,
) {
    // letta-mobile-ckmub.3.12: the shared Settings page (ShellPage's Settings destination).
    composable<ConfigRoute> {
        SettingsScreen(
            onNavigateBack = {
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else {
                    navController.navigate(AdminRoute) {
                        popUpTo<ConfigRoute> { inclusive = true }
                    }
                }
            },
            destinations = SettingsDestinations(
                onConfigList = { navController.navigate(ConfigListRoute) },
                onSystemAccess = { navController.navigate(SystemAccessRoute) },
                onCanvasDebug = { navController.navigate(CanvasDebugRoute) },
                onProviders = { navController.navigate(ProvidersRoute) },
            ),
        )
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
