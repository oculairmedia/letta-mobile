package com.letta.mobile.ui.navigation

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.letta.mobile.data.plugin.catalog.UnavailablePluginCatalog
import com.letta.mobile.data.plugin.catalog.inMemoryLiveViewsSetting
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.screens.config.ConfigListScreen
import com.letta.mobile.ui.screens.config.SettingsDestinations
import com.letta.mobile.ui.screens.config.SettingsScreen
import com.letta.mobile.ui.screens.plugins.AndroidPluginPackagePicker
import com.letta.mobile.ui.shell.ShellAdminPages
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.pages.plugins.PluginsPageBinding
import com.letta.mobile.ui.shell.pages.plugins.shellPluginsPage

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
                onPlugins = { navController.navigate(PluginsRoute) },
            ),
        )
    }

    // letta-mobile-cdgpr.9: the shared Plugins page. C7 (letta-mobile-cdgpr.7) binds the admin_rpc
    // catalog in place of UnavailablePluginCatalog; C8 persists the live-views switch per device.
    composable<PluginsRoute> {
        val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
        val resolver = LocalContext.current.contentResolver
        val binding = remember(registry, resolver) {
            PluginsPageBinding(UnavailablePluginCatalog(), AndroidPluginPackagePicker(registry, resolver)::pick, inMemoryLiveViewsSetting())
        }
        val page = shellPluginsPage(binding, onBack = { navController.popBackStack() })
        ShellPage(ShellDestination.Plugins, ShellPageDeps(admin = ShellAdminPages(plugins = page)))
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
