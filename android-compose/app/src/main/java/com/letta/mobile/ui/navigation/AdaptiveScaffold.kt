package com.letta.mobile.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import com.letta.mobile.feature.chat.AgentChatRoute
import com.letta.mobile.ui.haptics.HapticEffects
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
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        val isChatDestination = TopLevelDestination.CHAT.isSelected(currentDestination)

        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                LettaBottomBar(navController = navController)
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(
                        top = innerPadding.calculateTopPadding(),
                        bottom = if (isChatDestination) 0.dp else innerPadding.calculateBottomPadding(),
                    )
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
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val visibleDestinations = visibleTopLevelDestinations()

    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        header = { Spacer(Modifier.width(8.dp)) },
    ) {
        Spacer(Modifier.weight(1f))
        visibleDestinations.forEach { destination ->
            val selected = destination.isSelected(currentDestination)

            NavigationRailItem(
                selected = selected,
                onClick = {
                    HapticEffects.segmentTick(haptic, view, enabled = !selected)
                    navController.navigateTopLevel(destination, focusManager)
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
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val visibleDestinations = visibleTopLevelDestinations()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        // Each item claims an equal slice of the bar width via Modifier.weight(1f)
        // so the entire column under each label is a valid tap target — not
        // just the area centered around the icon. The weight automatically
        // adapts when an entry is filtered out (letta-mobile-2ixd: Projects
        // hides on backends without project endpoints).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleDestinations.forEach { destination ->
                LettaBottomBarItem(
                    modifier = Modifier.weight(1f),
                    icon = destination.icon,
                    label = destination.label,
                    selected = destination.isSelected(currentDestination),
                    onClick = {
                        HapticEffects.segmentTick(
                            haptic = haptic,
                            view = view,
                            enabled = !destination.isSelected(currentDestination),
                        )
                        navController.navigateTopLevel(destination, focusManager)
                    },
                )
            }
        }
    }
}

/**
 * letta-mobile-2ixd: filter [TopLevelDestination] entries against the
 * connected backend's capabilities. Today only the Projects entry is
 * conditional; future capability gates layer in here.
 *
 * Default is "show everything" — when the probe hasn't completed yet, or
 * fails for a transient reason, we err on the side of visible. Hiding a
 * feature behind an inconclusive probe is worse UX than showing a
 * working feature that occasionally hits a 404.
 */
@Composable
private fun visibleTopLevelDestinations(): List<TopLevelDestination> {
    val capabilities: CapabilityViewModel = hiltViewModel()
    val projectsSupported by capabilities.projectsSupported.collectAsStateWithLifecycle()
    return TopLevelDestination.entries.filter { destination ->
        when (destination) {
            TopLevelDestination.HOME -> projectsSupported
            else -> true
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

private fun NavController.navigateTopLevel(
    destination: TopLevelDestination,
    focusManager: FocusManager,
) {
    focusManager.clearFocus(force = true)
    if (popToExistingTopLevel(destination)) {
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

private fun NavController.popToExistingTopLevel(destination: TopLevelDestination): Boolean =
    when (destination) {
        TopLevelDestination.HOME,
        TopLevelDestination.CHAT,
        -> false
        // Chat can be opened from Admin shortcuts. In that case AgentChatRoute
        // is visually a chat destination, but AdminRoute is still the parent
        // stack the user expects to return to when tapping Admin.
        TopLevelDestination.ADMIN -> popBackStack<AdminRoute>(inclusive = false)
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
            SystemAccessRoute::class,
            EditAgentRoute::class,
            ToolDetailRoute::class,
            ArchivalRoute::class,
        )
    }
    return currentDestination.hierarchy.any { destination ->
        routeClasses.any { routeClass -> destination.hasRoute(routeClass) }
    }
}
