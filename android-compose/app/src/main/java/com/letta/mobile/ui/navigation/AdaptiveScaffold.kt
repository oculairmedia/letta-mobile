package com.letta.mobile.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth
import kotlin.reflect.KClass

@Composable
fun AdaptiveScaffold(
    navController: NavController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val windowSizeClass = LocalWindowSizeClass.current

    if (windowSizeClass.isExpandedWidth) {
        Row(modifier = modifier.fillMaxSize()) {
            LettaNavigationRail(navController = navController)
            VerticalDivider()
            content()
        }
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = { LettaBottomBar(navController = navController) },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                content()
            }
        }
    }
}

@Composable
private fun LettaNavigationRail(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        header = { Spacer(Modifier.width(8.dp)) },
    ) {
        Spacer(Modifier.weight(1f))
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination.isSelected(currentDestination)

            NavigationRailItem(
                selected = selected,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(destination.icon, contentDescription = destination.label)
                },
                label = { Text(destination.label) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun LettaBottomBar(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination.isSelected(currentDestination)

            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(destination.icon, contentDescription = destination.label)
                },
                label = { Text(destination.label) },
            )
        }
    }
}

private fun TopLevelDestination.isSelected(currentDestination: NavDestination?): Boolean {
    if (currentDestination == null) return false
    val routeClasses: List<KClass<*>> = when (this) {
        TopLevelDestination.HOME -> listOf(HomeRoute::class, ProjectsRoute::class)
        TopLevelDestination.CHAT -> listOf(ConversationsRoute::class, AgentChatRoute::class)
        TopLevelDestination.ADMIN -> listOf(
            AdminRoute::class,
            AgentListRoute::class,
            ConfigRoute::class,
            ConfigListRoute::class,
            TemplatesRoute::class,
            ArchivesRoute::class,
            FoldersRoute::class,
            GroupsRoute::class,
            ProvidersRoute::class,
            BlocksRoute::class,
            IdentitiesRoute::class,
            SchedulesRoute::class,
            RunsRoute::class,
            JobsRoute::class,
            MessageBatchesRoute::class,
            McpRoute::class,
            McpServerToolsRoute::class,
            AboutRoute::class,
            ModelsRoute::class,
            AllToolsRoute::class,
            UsageRoute::class,
            BotSettingsRoute::class,
            BotConfigEditRoute::class,
            EditAgentRoute::class,
            ToolDetailRoute::class,
            ArchivalRoute::class,
        )
    }
    return currentDestination.hierarchy.any { destination ->
        routeClasses.any { routeClass -> destination.hasRoute(routeClass) }
    }
}
