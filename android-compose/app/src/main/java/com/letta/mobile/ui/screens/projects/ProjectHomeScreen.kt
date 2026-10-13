package com.letta.mobile.ui.screens.projects

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.projects.ProjectsPageController
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.pages.projects.ProjectChatIntent
import com.letta.mobile.ui.shell.pages.projects.ProjectDetailActions
import com.letta.mobile.ui.shell.pages.projects.ProjectEditorActions
import com.letta.mobile.ui.shell.pages.projects.ProjectsListActions
import com.letta.mobile.ui.shell.pages.projects.ProjectsPage
import com.letta.mobile.ui.shell.pages.projects.ProjectsPageActions
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/** Where the Projects screen leaves to. [onBack] null is the Home tab's root (no back arrow). */
data class ProjectHomeNavigation(
    val onBack: (() -> Unit)?,
    val onOpenChat: (ProjectSummary, ProjectChatIntent) -> Unit,
    val onSettings: () -> Unit,
    /** The backend label under the title and the switcher it opens, on the Home tab. */
    val backendLabel: String? = null,
    val onBackendSwitcher: (() -> Unit)? = null,
)

/**
 * Android host for the shared Projects page (letta-mobile-ckmub.2.5): the shell header (back or
 * the backend label, and Settings) around sharedUI's ProjectsPage; the catalog is sharedLogic's
 * ProjectsPageController. Project issues have no App Server route and were deleted.
 */
@Composable
fun ProjectHomeScreen(navigation: ProjectHomeNavigation, viewModel: ProjectHomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel, navigation) { projectsPageActions(viewModel.controller, navigation.onOpenChat) }
    val back = navigation.onBack
    LettaPageScaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        header = {
            ShellHeader(
                title = HeaderTitle.Text(stringResource(R.string.screen_projects_title), navigation.backendLabel, navigation.onBackendSwitcher),
                controls = HeaderControls(
                    nav = back?.let { HeaderNav.Back(it, stringResource(R.string.action_back)) } ?: HeaderNav.None,
                    actions = listOf(HeaderAction.Icon(LettaIcons.Settings, stringResource(R.string.common_settings), navigation.onSettings)),
                ),
            )
        },
    ) { paddingValues ->
        ProjectsPage(state = state, actions = actions, modifier = Modifier.padding(paddingValues))
    }
}

private fun projectsPageActions(
    controller: ProjectsPageController,
    onOpenChat: (ProjectSummary, ProjectChatIntent) -> Unit,
) = ProjectsPageActions(
    list = ProjectsListActions(
        onRefresh = controller::refresh,
        onQueryChange = controller::setQuery,
        onSelect = controller::select,
        onTogglePinned = controller::togglePinned,
        onStartCreate = controller.editor::startCreate,
        onDismissNotice = controller::dismissNotice,
        onOpenChat = { project, intent ->
            controller.select(null)
            onOpenChat(project, intent)
        },
    ),
    detail = ProjectDetailActions(
        onStartEdit = controller.editor::startEdit,
        onSync = controller::sync,
        onProvisionBeads = controller::provisionBeadsRemote,
        onArchive = controller::archive,
        onDelete = controller::delete,
    ),
    editor = ProjectEditorActions(
        onDraftChange = controller.editor::updateDraft,
        onSubmit = controller.editor::submit,
        onClose = controller.editor::close,
    ),
)
