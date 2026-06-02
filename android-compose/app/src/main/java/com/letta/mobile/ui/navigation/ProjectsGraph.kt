package com.letta.mobile.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.ui.screens.projects.CreateProjectScreen
import com.letta.mobile.ui.screens.projects.ProjectHomeScreen
import com.letta.mobile.ui.screens.projects.ProjectIssueDetailScreen
import com.letta.mobile.ui.screens.projects.ProjectIssuesScreen
import com.letta.mobile.feature.chat.route.NavGraphBuilder

fun NavGraphBuilder.projectsGraph(
    navController: NavHostController,
    activeBackendLabel: String?,
    openBackendSwitcher: () -> Unit,
) {
    composable<HomeRoute> {
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

    composable<ProjectsRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
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
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(PROJECT_CREATED_REFRESH_KEY, true)
                navController.popBackStack()
            },
        )
    }
}
