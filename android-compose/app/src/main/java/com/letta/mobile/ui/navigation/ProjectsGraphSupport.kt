package com.letta.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.letta.mobile.R
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.screens.projects.ProjectHomeScreen

internal data class ProjectsCapabilitiesState(
    val projectsSupported: Boolean,
    val projectWorkSupported: Boolean,
)

@Composable
internal inline fun <reified PopRoute : Any> rememberProjectsCapabilities(
    navController: NavHostController,
    tryPopBackFirst: Boolean = false,
): ProjectsCapabilitiesState {
    val capabilities: CapabilityViewModel = hiltViewModel()
    val projectsSupported by capabilities.projectsSupported.collectAsStateWithLifecycle()
    val projectWorkSupported by capabilities.projectWorkSupported.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val unavailableMessage = stringResource(R.string.screen_projects_unavailable_message)
    if (!projectsSupported) {
        LaunchedEffect(Unit) {
            if (tryPopBackFirst) {
                if (!navController.popBackStack()) {
                    navController.navigate(ConversationsRoute) {
                        popUpTo<PopRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                }
            } else {
                navController.navigate(ConversationsRoute) {
                    popUpTo<PopRoute> { inclusive = true }
                    launchSingleTop = true
                }
            }
            snackbar.dispatch(unavailableMessage)
        }
    }
    return ProjectsCapabilitiesState(
        projectsSupported = projectsSupported,
        projectWorkSupported = projectWorkSupported,
    )
}

@Composable
internal fun rememberProjectWorkSupportedOrPopBack(navController: NavHostController): Boolean {
    val capabilities: CapabilityViewModel = hiltViewModel()
    val projectWorkSupported by capabilities.projectWorkSupported.collectAsStateWithLifecycle()
    if (!projectWorkSupported) {
        LaunchedEffect(Unit) {
            navController.popBackStack()
        }
    }
    return projectWorkSupported
}

@Composable
internal fun ProjectHomeRouteScreen(
    navController: NavHostController,
    projectWorkSupported: Boolean,
    onNavigateBack: (() -> Unit)?,
    activeBackendLabel: String? = null,
    onNavigateToBackendSwitcher: (() -> Unit)? = null,
) {
    ProjectHomeScreen(
        onNavigateBack = onNavigateBack,
        onNavigateToProjectChat = projectHomeNavigateToChat(navController),
        onNavigateToProjectIssues = projectHomeNavigateToIssues(navController),
        onNavigateToPmAgentChat = { agentId -> navController.navigate(AgentChatRoute(agentId = agentId)) },
        onNavigateToSettings = { navController.navigate(ConfigRoute()) },
        onNavigateToCreateProject = { navController.navigate(CreateProjectRoute) },
        projectWorkSupported = projectWorkSupported,
        activeBackendLabel = activeBackendLabel,
        onNavigateToBackendSwitcher = onNavigateToBackendSwitcher,
    )
}

private fun projectHomeNavigateToChat(
    navController: NavHostController,
): (ProjectSummary, String?) -> Unit = { project, projectStartAction ->
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
}

private fun projectHomeNavigateToIssues(
    navController: NavHostController,
): (ProjectSummary) -> Unit = { project ->
    navController.navigate(
        ProjectIssuesRoute(
            projectId = project.identifier,
            projectName = project.name,
        )
    )
}
