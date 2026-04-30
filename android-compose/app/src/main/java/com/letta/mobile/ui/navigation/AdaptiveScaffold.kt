package com.letta.mobile.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
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
        // Hide the bottom navigation while the keyboard is visible so the
        // input field sits flush above the IME instead of floating over a
        // dead gap where the nav used to be. The nav would otherwise stay
        // at the bottom of the screen (windowSoftInputMode=adjustNothing +
        // enableEdgeToEdge) while per-screen imePadding() lifts only the
        // content, leaving a black void between the content bottom and
        // the top of the IME.
        val density = LocalDensity.current
        val imeInsets = WindowInsets.ime
        val isImeVisible by remember {
            derivedStateOf { imeInsets.getBottom(density) > 0 }
        }

        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                // Anchor the expand/shrink to the Bottom edge so the bar
                // reveals by growing UPWARD from the bottom of the screen
                // (its final resting edge) rather than appearing above
                // its final slot and dropping down into place. On exit it
                // shrinks back into the bottom edge.
                AnimatedVisibility(
                    visible = !isImeVisible,
                    enter = expandVertically(
                        expandFrom = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 150),
                    ),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 150),
                    ),
                ) {
                    LettaBottomBar(navController = navController)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { innerPadding ->
            // consumeWindowInsets(innerPadding) tells downstream imePadding()
            // (used by Chat/Home screens) that the Scaffold has already
            // accounted for the system navigationBars+bottomBar. Without this,
            // imePadding() would also add navigationBars-worth of space on top
            // of the Scaffold's own padding, producing a visible gap between
            // the composer and the top of the keyboard.
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
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
                    navController.navigateToTopLevelDestination(
                        destination = destination,
                        currentDestination = currentDestination,
                    )
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

/**
 * Slim bottom navigation row. Material3's NavigationBar enforces an 80dp
 * height and bulky internal padding around each NavigationBarItem; this
 * custom layout halves the vertical footprint by tightly stacking a
 * compact icon + small label inside a 56dp tall surface (plus the
 * system gesture inset).
 */
@Composable
private fun LettaBottomBar(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        // Each item claims an equal 1/3 of the bar width via Modifier.weight(1f)
        // so the entire column under the Admin/Chat/Home label is a valid tap
        // target — not just the ~190dp centered around the icon. Arrangement
        // previously used SpaceEvenly which made items as narrow as their
        // content, leaving ~225dp dead zones at each horizontal gap (and in
        // particular past the Admin label, on the right edge of the screen).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                LettaBottomBarItem(
                    modifier = Modifier.weight(1f),
                    icon = destination.icon,
                    label = destination.label,
                    selected = destination.isSelected(currentDestination),
                    onClick = {
                        navController.navigateToTopLevelDestination(
                            destination = destination,
                            currentDestination = currentDestination,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LettaBottomBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // clickable() wraps the full-width column so the whole 1/3 strip is a hit
    // target; the inner padding only controls where the icon+label *draw*.
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun NavController.navigateToTopLevelDestination(
    destination: TopLevelDestination,
    currentDestination: NavDestination?,
) {
    val isInAgentChat = currentDestination
        ?.hierarchy
        ?.any { navDestination -> navDestination.hasRoute(AgentChatRoute::class) } == true

    if (destination == TopLevelDestination.ADMIN && isInAgentChat) {
        if (popBackStack<AdminRoute>(inclusive = false)) {
            return
        }

        navigate(destination.route) {
            popUpTo(graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        return
    }

    navigate(destination.route) {
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
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
