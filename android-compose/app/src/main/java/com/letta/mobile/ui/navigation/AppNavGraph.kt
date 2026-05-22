package com.letta.mobile.ui.navigation

import android.content.Context
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.letta.mobile.AppLaunchTarget
import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.channel.ChatPushAlarmScheduler
import com.letta.mobile.data.model.toBackendLabel
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.feature.chat.AgentChatRoute
import com.letta.mobile.feature.chat.chatGraph
import com.letta.mobile.ui.screens.projects.CreateProjectScreen
import com.letta.mobile.ui.screens.projects.ProjectHomeScreen
import com.letta.mobile.ui.screens.projects.ProjectIssueDetailScreen
import com.letta.mobile.ui.screens.projects.ProjectIssuesScreen
import com.letta.mobile.ui.screens.dashboard.HomeScreen
import com.letta.mobile.ui.screens.about.AboutScreen
import com.letta.mobile.ui.screens.bot.BotConfigEditScreen
import com.letta.mobile.ui.screens.bot.BotSettingsScreen
import com.letta.mobile.ui.screens.lettabot.LettaBotConnectionScreen
import com.letta.mobile.ui.screens.agentlist.AgentListScreen
import com.letta.mobile.ui.screens.archives.ArchiveAdminScreen
import com.letta.mobile.ui.screens.archival.ArchivalScreen
import com.letta.mobile.ui.screens.blocks.BlockLibraryScreen
import com.letta.mobile.ui.screens.config.BackendSwitcherSheet
import com.letta.mobile.ui.screens.config.ConfigListScreen
import com.letta.mobile.ui.screens.config.ConfigScreen
import com.letta.mobile.ui.screens.config.VibesyncDebugScreen
import com.letta.mobile.ui.screens.conversations.ConversationsScreen
import com.letta.mobile.ui.screens.conversations.TwoPaneConversationsLayout
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth
import com.letta.mobile.feature.editagent.EditAgentScreen
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
import com.letta.mobile.ui.screens.systemaccess.SystemAccessDashboardScreen
import com.letta.mobile.ui.screens.telemetry.TelemetryScreen
import com.letta.mobile.ui.screens.usage.UsageScreen
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DrillTransitionDurationMs = 320

/**
 * letta-mobile-cygd: signal flag set on the previous backstack entry's
 * SavedStateHandle when CreateProjectRoute pops on success. Both
 * HomeRoute and ProjectsRoute (the two routes that host
 * ProjectHomeScreen) read this flag in a LaunchedEffect on resume and
 * trigger a refresh + clear so the new project shows up immediately.
 */
const val PROJECT_CREATED_REFRESH_KEY: String = "project_created_refresh"

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
    private val settingsRepository: ISettingsRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {
    val hasConfig = settingsRepository.activeConfig.map { it != null }
    val activeConfig = settingsRepository.activeConfig
    val favoriteAgentId = settingsRepository.favoriteAgentId
    val adminAgentId = settingsRepository.adminAgentId
    val lastChatSelection = settingsRepository.lastChatSelection

    fun clearAllData() {
        ChatPushAlarmScheduler.cancel(appContext)
        viewModelScope.launch { settingsRepository.clearAllData() }
    }

    fun setActiveConfig(configId: String) {
        viewModelScope.launch { settingsRepository.setActiveConfigId(configId) }
    }
}

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    notificationTarget: AppLaunchTarget? = null,
    onNotificationTargetConsumed: () -> Unit = {},
) {
    val navViewModel: NavViewModel = hiltViewModel()
    val hasConfig by navViewModel.hasConfig.collectAsStateWithLifecycle(initialValue = true)
    val activeConfig by navViewModel.activeConfig.collectAsStateWithLifecycle(initialValue = null)
    val favoriteAgentId by navViewModel.favoriteAgentId.collectAsStateWithLifecycle(initialValue = null)
    val adminAgentId by navViewModel.adminAgentId.collectAsStateWithLifecycle(initialValue = null)
    val lastChatSelection by navViewModel.lastChatSelection.collectAsStateWithLifecycle(initialValue = null)
    val activeBackendLabel = activeConfig.toBackendLabel()

    // letta-mobile-cdlk: backend-switcher bottom sheet. State is lifted to
    // the NavHost host so a single sheet instance overlays whichever screen
    // owns the pill (Home, Conversations, ProjectHome, TwoPaneConversations).
    // rememberSaveable so the sheet survives configuration changes; the
    // ModalBottomSheet manages its own enter/exit animation internally.
    var showBackendSwitcher by rememberSaveable { mutableStateOf(false) }
    val openBackendSwitcher: () -> Unit = remember { { showBackendSwitcher = true } }

    val initialNotificationTarget = remember { notificationTarget }
    val restoredChatSelection = lastChatSelection
    val fallbackAgentId = favoriteAgentId ?: adminAgentId
    val startDestination: Any = when {
        hasConfig && initialNotificationTarget != null -> initialNotificationTarget.toRoute()
        hasConfig && restoredChatSelection != null -> restoredChatSelection.let { selection ->
            AgentChatRoute(
                agentId = selection.agentId,
                agentName = selection.agentName,
                conversationId = selection.conversationId,
            )
        }
        hasConfig && fallbackAgentId != null -> AgentChatRoute(agentId = fallbackAgentId)
        hasConfig -> HomeRoute
        else -> ConfigRoute()
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
            // letta-mobile-2ixd: PR #56 hid the Projects tab from the bottom
            // bar / nav rail on backends without /api/projects, but the same
            // route is still reachable via startDestination (cold-start
            // landing), deep-link, and several internal navigate(HomeRoute)
            // call sites. Intercept here: if the capability probe says
            // projects are unsupported, redirect to Conversations and tell
            // the user why via the global snackbar.
            val capabilities: CapabilityViewModel = hiltViewModel()
            val projectsSupported by capabilities.projectsSupported.collectAsStateWithLifecycle()
            val snackbar = com.letta.mobile.ui.common.LocalSnackbarDispatcher.current
            val unavailableMessage = androidx.compose.ui.res.stringResource(com.letta.mobile.R.string.screen_projects_unavailable_message)
            if (!projectsSupported) {
                LaunchedEffect(Unit) {
                    navController.navigate(ConversationsRoute) {
                        popUpTo<HomeRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                    snackbar.dispatch(unavailableMessage)
                }
                return@composable
            }
            ProjectHomeScreen(
                onNavigateBack = null,
                onNavigateToProjectChat = { project, projectStartAction ->
                    navController.navigate(
                        AgentChatRoute(
                            agentId = project.lettaAgentId?.value.orEmpty(),
                            projectIdentifier = project.identifier,
                            projectName = project.name,
                            projectLettaFolderId = project.lettaFolderId,
                            projectFilesystemPath = project.filesystemPath,
                            projectGitUrl = project.gitUrl,
                            projectLastSyncAt = project.lastSyncAt,
                            projectActiveCodingAgents = project.techStack,
                            projectStartAction = projectStartAction,
                        )
                    )
                },
                onNavigateToProjectIssues = { project ->
                    navController.navigate(
                        ProjectIssuesRoute(
                            projectId = project.identifier,
                            projectName = project.name,
                        )
                    )
                },
                onNavigateToPmAgentChat = { agentId -> navController.navigate(AgentChatRoute(agentId = agentId)) },
                onNavigateToSettings = { navController.navigate(ConfigRoute()) },
                onNavigateToCreateProject = { navController.navigate(CreateProjectRoute) },
                activeBackendLabel = activeBackendLabel,
                onNavigateToBackendSwitcher = openBackendSwitcher,
            )
        }

        composable<ProjectIssuesRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<ProjectIssuesRoute>()
            ProjectIssuesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToIssue = { issueId ->
                    navController.navigate(
                        ProjectIssueDetailRoute(
                            projectId = route.projectId,
                            issueId = issueId,
                            projectName = route.projectName,
                        )
                    )
                },
            )
        }

        composable<ProjectIssueDetailRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            ProjectIssueDetailScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable<AdminRoute> {
            HomeScreen(
                onNavigateToAgents = { navController.navigate(AgentListRoute) },
                onNavigateToConversations = { navController.navigate(ConversationsRoute) },
                onNavigateToTools = { navController.navigate(AllToolsRoute) },
                onNavigateToBlocks = { navController.navigate(BlocksRoute) },
                onNavigateToSettings = { navController.navigate(ConfigRoute()) },
                onNavigateToChat = { agentId, agentName, initialMessage ->
                    navController.navigate(
                        AgentChatRoute(
                            agentId = agentId,
                            agentName = agentName,
                            initialMessage = initialMessage,
                        )
                    )
                },
                onNavigateToChatMessage = { agentId, conversationId, messageId ->
                    navController.navigate(AgentChatRoute(agentId = agentId, conversationId = conversationId, scrollToMessageId = messageId))
                },
                onNavigateToEditAgent = { agentId ->
                    navController.navigate(EditAgentRoute(agentId))
                },
                onNavigateToUsage = { navController.navigate(UsageRoute) },
                onNavigateToTemplates = { navController.navigate(TemplatesRoute) },
                onNavigateToArchives = { navController.navigate(ArchivesRoute) },
                onNavigateToFolders = { navController.navigate(FoldersRoute) },
                onNavigateToGroups = { navController.navigate(GroupsRoute) },
                onNavigateToProviders = { navController.navigate(ProvidersRoute) },
                onNavigateToIdentities = { navController.navigate(IdentitiesRoute) },
                onNavigateToSchedules = { navController.navigate(SchedulesRoute) },
                onNavigateToRuns = { navController.navigate(RunsRoute) },
                onNavigateToJobs = { navController.navigate(JobsRoute) },
                onNavigateToMessageBatches = { navController.navigate(MessageBatchesRoute) },
                onNavigateToMcp = { navController.navigate(McpRoute) },
                onNavigateToAbout = { navController.navigate(AboutRoute) },
                onNavigateToTelemetry = { navController.navigate(TelemetryRoute) },
                onNavigateToSystemAccess = { navController.navigate(SystemAccessRoute) },
                onNavigateToBotSettings = { navController.navigate(BotSettingsRoute) },
                onNavigateToProjects = { navController.navigate(HomeRoute) },
                onNavigateToModels = { navController.navigate(ModelsRoute) },
                activeBackendLabel = activeBackendLabel,
                onNavigateToBackendSwitcher = openBackendSwitcher,
                title = "Admin",
            )
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

        composable<TelemetryRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            TelemetryScreen(onBack = { navController.popBackStack() })
        }

        composable<SystemAccessRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            SystemAccessDashboardScreen(onNavigateBack = { navController.popBackStack() })
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
                        onNavigateToSettings = { navController.navigate(ConfigRoute()) },
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
                        onNavigateToProjects = { navController.navigate(HomeRoute) },
                        activeBackendLabel = activeBackendLabel,
                        onNavigateToBackendSwitcher = openBackendSwitcher,
                    )
                } else {
                    ConversationsScreen(
                        onNavigateToChat = { agentId, conversationId, agentName ->
                            navController.navigate(
                                AgentChatRoute(
                                    agentId = agentId,
                                    agentName = agentName,
                                    conversationId = conversationId,
                                )
                            )
                        },
                        onNavigateToSettings = { navController.navigate(ConfigRoute()) },
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
                        onNavigateToProjects = { navController.navigate(HomeRoute) },
                        activeBackendLabel = activeBackendLabel,
                        onNavigateToBackendSwitcher = openBackendSwitcher,
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
                    onNavigateToAgent = { agentId, agentName ->
                        navController.navigate(AgentChatRoute(agentId = agentId, agentName = agentName))
                    },
                    onNavigateToEditAgent = { agentId ->
                        navController.navigate(EditAgentRoute(agentId))
                    },
                )
            }
        }

        composable<ShareToAgentRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<ShareToAgentRoute>()
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                AgentListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAgent = { agentId, agentName ->
                        navController.navigate(
                            AgentChatRoute(
                                agentId = agentId,
                                agentName = agentName,
                                initialMessage = route.sharedText,
                            ),
                        ) {
                            popUpTo<ShareToAgentRoute> { inclusive = true }
                        }
                    },
                    onNavigateToEditAgent = { agentId ->
                        navController.navigate(EditAgentRoute(agentId))
                    },
                    shareContentPreview = route.sharedText,
                )
            }
        }

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
                onNavigateToLettaBotConnection = {
                    navController.navigate(LettaBotConnectionRoute)
                },
                onNavigateToSystemAccess = {
                    navController.navigate(SystemAccessRoute)
                },
                onNavigateToVibesyncDebug = {
                    navController.navigate(VibesyncDebugRoute)
                },
            )
        }

        composable<VibesyncDebugRoute> {
            VibesyncDebugScreen(onNavigateBack = { navController.popBackStack() })
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

        composable<LettaBotConnectionRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            LettaBotConnectionScreen(
                onNavigateBack = { navController.popBackStack() },
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
            // letta-mobile-2ixd: same capability gate as HomeRoute above —
            // catches direct deep-links to the explicit projects route.
            val capabilities: CapabilityViewModel = hiltViewModel()
            val projectsSupported by capabilities.projectsSupported.collectAsStateWithLifecycle()
            val snackbar = com.letta.mobile.ui.common.LocalSnackbarDispatcher.current
            val unavailableMessage = androidx.compose.ui.res.stringResource(com.letta.mobile.R.string.screen_projects_unavailable_message)
            if (!projectsSupported) {
                LaunchedEffect(Unit) {
                    if (!navController.popBackStack()) {
                        navController.navigate(ConversationsRoute) {
                            popUpTo<ProjectsRoute> { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                    snackbar.dispatch(unavailableMessage)
                }
                return@composable
            }
            ProjectHomeScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProjectChat = { project, projectStartAction ->
                    navController.navigate(
                        AgentChatRoute(
                            agentId = project.lettaAgentId?.value.orEmpty(),
                            projectIdentifier = project.identifier,
                            projectName = project.name,
                            projectLettaFolderId = project.lettaFolderId,
                            projectFilesystemPath = project.filesystemPath,
                            projectGitUrl = project.gitUrl,
                            projectLastSyncAt = project.lastSyncAt,
                            projectActiveCodingAgents = project.techStack,
                            projectStartAction = projectStartAction,
                        )
                    )
                },
                onNavigateToProjectIssues = { project ->
                    navController.navigate(
                        ProjectIssuesRoute(
                            projectId = project.identifier,
                            projectName = project.name,
                        )
                    )
                },
                onNavigateToPmAgentChat = { agentId -> navController.navigate(AgentChatRoute(agentId = agentId)) },
                onNavigateToSettings = { navController.navigate(ConfigRoute()) },
                onNavigateToCreateProject = { navController.navigate(CreateProjectRoute) },
            )
        }

        composable<CreateProjectRoute>(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
        ) {
            CreateProjectScreen(
                onNavigateBack = { navController.popBackStack() },
                onProjectCreated = { _ ->
                    // letta-mobile-cygd: signal the previous backstack
                    // entry (HomeRoute or ProjectsRoute) that it should
                    // refresh on resume so the freshly-created project
                    // shows up without a manual pull-to-refresh.
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(PROJECT_CREATED_REFRESH_KEY, true)
                    navController.popBackStack()
                },
            )
        }

        chatGraph(
            enterTransition = drillInEnter,
            exitTransition = drillInExit,
            popEnterTransition = drillInPopEnter,
            popExitTransition = drillInPopExit,
            // letta-mobile: when AgentChatRoute is the cold-start landing
            // (lastChatSelection or fallbackAgentId picked it as the
            // startDestination), the back stack is empty. Fall back to the
            // conversations list so back-from-default-chat takes the user
            // somewhere useful instead.
            onNavigateBack = {
                if (!navController.popBackStack()) {
                    navController.navigate(ConversationsRoute) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            },
            onNavigateToSettings = { agentId ->
                navController.navigate(EditAgentRoute(agentId))
            },
            onNavigateToArchival = { agentId ->
                navController.navigate(ArchivalRoute(agentId))
            },
            onNavigateToTools = {
                navController.navigate(AllToolsRoute)
            },
            onSwitchConversation = { route ->
                navController.navigate(route) {
                    popUpTo<AgentChatRoute> { inclusive = true }
                }
            },
        )
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
                    navController.navigate(ConfigRoute()) {
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

    // letta-mobile-cdlk: render the backend-switcher sheet at the top level
    // so it overlays whichever screen owns the pill. The Hilt-scoped
    // ConfigListViewModel that BackendSwitcherSheet pulls observes the
    // settings flow, so the sheet contents stay fresh across reopens.
    if (showBackendSwitcher) {
        BackendSwitcherSheet(
            onDismiss = { showBackendSwitcher = false },
            onNavigateToAddNewServer = {
                navController.navigate(ConfigRoute(createNew = true))
            },
            onNavigateToEditServer = {
                navController.navigate(ConfigRoute())
            },
        )
    }
}
