package com.letta.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ca.oculair.meridian.R
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.chat.route.ProjectChatStartAction
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.screens.projects.ProjectHomeNavigation
import com.letta.mobile.ui.screens.projects.ProjectHomeScreen
import com.letta.mobile.ui.shell.pages.projects.ProjectChatIntent

internal data class ProjectsCapabilitiesState(val projectsSupported: Boolean)

/** The backend label the Home tab's header shows, and the switcher it opens. */
internal data class ProjectsBackendLabel(val label: String?, val onOpenSwitcher: () -> Unit)

/** Projects reach Android only through an Iroh host; elsewhere the route falls back to the conversations. */
@Composable
internal inline fun <reified PopRoute : Any> rememberProjectsCapabilities(
    navController: NavHostController,
    tryPopBackFirst: Boolean = false,
): ProjectsCapabilitiesState {
    val capabilities: CapabilityViewModel = hiltViewModel()
    val projectsSupported by capabilities.projectsSupported.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val unavailableMessage = stringResource(R.string.screen_projects_unavailable_message)
    if (!projectsSupported) {
        LaunchedEffect(Unit) {
            if (!tryPopBackFirst || !navController.popBackStack()) {
                navController.navigate(ConversationsRoute) {
                    popUpTo<PopRoute> { inclusive = true }
                    launchSingleTop = true
                }
            }
            snackbar.dispatch(unavailableMessage)
        }
    }
    return ProjectsCapabilitiesState(projectsSupported = projectsSupported)
}

@Composable
internal fun ProjectHomeRouteScreen(
    navController: NavHostController,
    onNavigateBack: (() -> Unit)?,
    backend: ProjectsBackendLabel? = null,
) {
    val navigation = remember(navController, onNavigateBack, backend) {
        ProjectHomeNavigation(
            onBack = onNavigateBack,
            onOpenChat = { project, intent -> navController.navigate(project.chatRoute(intent)) },
            onSettings = { navController.navigate(ConfigRoute()) },
            backendLabel = backend?.label,
            onBackendSwitcher = backend?.onOpenSwitcher,
        )
    }
    ProjectHomeScreen(navigation = navigation)
}

/** The project's agent's chat, carrying the project's context (and the bug-report start). */
internal fun ProjectSummary.chatRoute(intent: ProjectChatIntent): AgentChatRoute = AgentChatRoute(
    agentId = lettaAgentId?.value.orEmpty(),
    projectIdentifier = identifier,
    projectName = name,
    projectLettaFolderId = lettaFolderId,
    projectFilesystemPath = filesystemPath,
    projectGitUrl = gitUrl,
    projectLastSyncAt = lastSyncAt,
    projectActiveCodingAgents = techStack,
    projectStartAction = ProjectChatStartAction.BUG_REPORT.takeIf { intent == ProjectChatIntent.BugReport },
)
