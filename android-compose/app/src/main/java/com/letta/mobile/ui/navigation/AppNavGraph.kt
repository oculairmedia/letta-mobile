package com.letta.mobile.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.screens.projects.ProjectHomeScreen
import com.letta.mobile.ui.screens.about.AboutScreen
import com.letta.mobile.ui.screens.bot.BotConfigEditScreen
import com.letta.mobile.ui.screens.bot.BotSettingsScreen
import com.letta.mobile.ui.screens.agentlist.AgentListScreen
import com.letta.mobile.ui.screens.archives.ArchiveAdminScreen
import com.letta.mobile.ui.screens.archival.ArchivalScreen
import com.letta.mobile.ui.screens.blocks.BlockLibraryScreen
import com.letta.mobile.ui.screens.chat.AgentScaffold
import com.letta.mobile.ui.screens.config.ConfigListScreen
import com.letta.mobile.ui.screens.config.ConfigScreen
import com.letta.mobile.ui.screens.conversations.ConversationsScreen
import com.letta.mobile.ui.screens.conversations.TwoPaneConversationsLayout
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth
import com.letta.mobile.ui.screens.editagent.EditAgentScreen
import com.letta.mobile.ui.screens.folders.FolderAdminScreen
import com.letta.mobile.ui.screens.groups.GroupAdminScreen
import com.letta.mobile.ui.screens.identities.IdentityListScreen
import com.letta.mobile.ui.screens.jobs.JobMonitorScreen
import com.letta.mobile.ui.screens.mcp.McpScreen
import com.letta.mobile.ui.screens.mcp.McpServerToolsScreen
import com.letta.mobile.ui.screens.messagebatches.MessageBatchMonitorScreen
import com.letta.mobile.ui.screens.models.ModelBrowserScreen
import com.letta.mobile.ui.screens.providers.ProviderAdminScreen
import com.letta.mobile.ui.screens.runs.RunMonitorScreen
import com.letta.mobile.ui.screens.schedules.ScheduleListScreen
import com.letta.mobile.ui.screens.templates.TemplatesScreen
import com.letta.mobile.ui.screens.tools.AllToolsScreen
import com.letta.mobile.ui.screens.tools.ToolDetailScreen
import com.letta.mobile.ui.screens.usage.UsageScreen
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DrillTransitionDurationMs = 320

private val drillInEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(DrillTransitionDurationMs),
        initialOffset = { distance -> distance / 8 },
    ) + fadeIn(animationSpec = tween(DrillTransitionDurationMs))
}

private val drillInExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(DrillTransitionDurationMs / 2))
}

private val drillInPopEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(DrillTransitionDurationMs / 2))
}

private val drillInPopExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(DrillTransitionDurationMs),
        targetOffset = { distance -> distance / 8 },
    ) + fadeOut(animationSpec = tween(DrillTransitionDurationMs))
}

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@HiltViewModel
class NavViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val hasConfig = settingsRepository.activeConfig.map { it != null }

    fun clearAllData() {
        viewModelScope.launch { settingsRepository.clearAllData() }
    }
}

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    notificationTarget: NotificationNavigationTarget? = null,
    onNotificationTargetConsumed: () -> Unit = {},
) {
    val navViewModel: NavViewModel = hiltViewModel()
    val hasConfig by navViewModel.hasConfig.collectAsState(initial = true)

    val initialNotificationTarget = remember { notificationTarget }
    val startDestination: Any = when {
        hasConfig && initialNotificationTarget != null -> initialNotificationTarget.toRoute()
        hasConfig -> HomeRoute
        else -> ConfigRoute
    }

    LaunchedEffect(notificationTarget) {
        val target = notificationTarget ?: return@LaunchedEffect
        if (target != initialNotificationTarget) {
            navController.navigate(target.toRoute()) {
                launchSingleTop = true
            }
        }
        onNotificationTargetConsumed()
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = startDestination
            ) {
        composable<HomeRoute> {
            LaunchedEffect(Unit) {
                navController.navigate(ConversationsRoute) {
                    popUpTo<HomeRoute> { inclusive = true }
                }
            }
        }

        composable<UsageRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            UsageScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRuns = { navController.navigate(RunsRoute) },
            )
        }

        composable<ConversationsRoute>(
            enterTransition = drillInPopEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                val windowSizeClass = LocalWindowSizeClass.current
                if (windowSizeClass.isExpandedWidth) {
                    TwoPaneConversationsLayout(
                        outerNavController = navController,
                        onNavigateToSettings = { navController.navigate(ConfigRoute) },
                        onNavigateToAgentList = { navController.navigate(AgentListRoute) },
                        onNavigateToTemplates = { navController.navigate(TemplatesRoute) },
                        onNavigateToArchives = { navController.navigate(ArchivesRoute) },
                        onNavigateToFolders = { navController.navigate(FoldersRoute) },
                        onNavigateToGroups = { navController.navigate(GroupsRoute) },
                        onNavigateToProviders = { navController.navigate(ProvidersRoute) },
                        onNavigateToBlocks = { navController.navigate(BlocksRoute) },
                        onNavigateToIdentities = { navController.navigate(IdentitiesRoute) },
                        onNavigateToSchedules = { navController.navigate(SchedulesRoute) },
                        onNavigateToRuns = { navController.navigate(RunsRoute) },
                        onNavigateToJobs = { navController.navigate(JobsRoute) },
                        onNavigateToMessageBatches = { navController.navigate(MessageBatchesRoute) },
                        onNavigateToMcp = { navController.navigate(McpRoute) },
                        onNavigateToAbout = { navController.navigate(AboutRoute) },
                        onNavigateToBotSettings = { navController.navigate(BotSettingsRoute) },
                        onNavigateToProjects = { navController.navigate(ProjectsRoute) },
                    )
                } else {
                    ConversationsScreen(
                        onNavigateToChat = { agentId, conversationId ->
                            navController.navigate(AgentChatRoute(agentId = agentId, conversationId = conversationId))
                        },
                        onNavigateToSettings = { navController.navigate(ConfigRoute) },
                        onNavigateToAgentList = { navController.navigate(AgentListRoute) },
                        onNavigateToTemplates = { navController.navigate(TemplatesRoute) },
                        onNavigateToArchives = { navController.navigate(ArchivesRoute) },
                        onNavigateToFolders = { navController.navigate(FoldersRoute) },
                        onNavigateToGroups = { navController.navigate(GroupsRoute) },
                        onNavigateToProviders = { navController.navigate(ProvidersRoute) },
                        onNavigateToBlocks = { navController.navigate(BlocksRoute) },
                        onNavigateToIdentities = { navController.navigate(IdentitiesRoute) },
                        onNavigateToSchedules = { navController.navigate(SchedulesRoute) },
                        onNavigateToRuns = { navController.navigate(RunsRoute) },
                        onNavigateToJobs = { navController.navigate(JobsRoute) },
                        onNavigateToMessageBatches = { navController.navigate(MessageBatchesRoute) },
                        onNavigateToMcp = { navController.navigate(McpRoute) },
                        onNavigateToAbout = { navController.navigate(AboutRoute) },
                        onNavigateToBotSettings = { navController.navigate(BotSettingsRoute) },
                        onNavigateToProjects = { navController.navigate(ProjectsRoute) },
                    )
                }
            }
        }

        composable<AgentListRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                AgentListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAgent = { agentId ->
                        navController.navigate(AgentChatRoute(agentId = agentId))
                    },
                    onNavigateToEditAgent = { agentId ->
                        navController.navigate(EditAgentRoute(agentId))
                    },
                )
            }
        }

        composable<ConfigRoute> {
            ConfigScreen(
                onNavigateBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(ConversationsRoute) {
                            popUpTo<ConfigRoute> { inclusive = true }
                        }
                    }
                },
                onNavigateToConfigList = {
                    navController.navigate(ConfigListRoute)
                }
            )
        }

        composable<ConfigListRoute> {
            ConfigListScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<TemplatesRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            TemplatesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAgent = { agentId ->
                    navController.navigate(AgentChatRoute(agentId = agentId))
                },
                onNavigateToAgentList = {
                    navController.navigate(AgentListRoute)
                },
            )
        }

        composable<ArchivesRoute> {
            ArchiveAdminScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<FoldersRoute> {
            FolderAdminScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<GroupsRoute> {
            GroupAdminScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<ProvidersRoute> {
            ProviderAdminScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<BlocksRoute> {
            BlockLibraryScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<IdentitiesRoute> {
            IdentityListScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<SchedulesRoute> {
            ScheduleListScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<RunsRoute> {
            RunMonitorScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<JobsRoute> {
            JobMonitorScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<MessageBatchesRoute> {
            MessageBatchMonitorScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<McpRoute> {
            McpScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToServerTools = { serverId ->
                    navController.navigate(McpServerToolsRoute(serverId))
                }
            )
        }

        composable<McpServerToolsRoute> {
            McpServerToolsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<BotSettingsRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            BotSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { configId ->
                    navController.navigate(BotConfigEditRoute(configId))
                },
            )
        }

        composable<BotConfigEditRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            BotConfigEditScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<ProjectsRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            ProjectHomeScreen(
                onNavigateToProjectChat = { project ->
                    navController.navigate(
                        AgentChatRoute(
                            agentId = project.lettaAgentId.orEmpty(),
                            projectIdentifier = project.identifier,
                            projectName = project.name,
                            projectFilesystemPath = project.filesystemPath,
                            projectGitUrl = project.gitUrl,
                            projectLastSyncAt = project.lastSyncAt,
                            projectActiveCodingAgents = project.techStack,
                        )
                    )
                },
                onNavigateToSettings = { navController.navigate(ConfigRoute) },
            )
        }

        composable<AgentChatRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                AgentScaffold(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSettings = { agentId ->
                        navController.navigate(EditAgentRoute(agentId))
                    },
                    onNavigateToArchival = { agentId ->
                        navController.navigate(ArchivalRoute(agentId))
                    },
                    onNavigateToTools = {
                        navController.navigate(AllToolsRoute)
                    },
                    onSwitchConversation = { agentId, conversationId ->
                        navController.navigate(AgentChatRoute(agentId = agentId, conversationId = conversationId)) {
                            popUpTo<ConversationsRoute>()
                        }
                    },
                )
            }
        }

        composable<EditAgentRoute> {
            EditAgentScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<AboutRoute> {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() },
                onLogout = {
                    navViewModel.clearAllData()
                    navController.navigate(ConfigRoute) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable<AllToolsRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                AllToolsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToToolDetail = { toolId ->
                        navController.navigate(ToolDetailRoute(toolId))
                    },
                )
            }
        }

        composable<ToolDetailRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                ToolDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }

        composable<ModelsRoute> {
            ModelBrowserScreen(
                onNavigateBack = { navController.popBackStack() },
                onModelSelected = { modelId ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("selectedModel", modelId)
                    navController.popBackStack()
                }
            )
        }

        composable<ArchivalRoute> {
            ArchivalScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
        }
    }
}
