package com.letta.mobile.ui.screens.runs

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.data.runs.RunMonitorController
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.shell.ShellAdminPages
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.shellRunsPage
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.pages.runs.RunsPageActions
import com.letta.mobile.ui.shell.pages.runs.RunsPageState
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/**
 * Android host for the shared Runs page (letta-mobile-ckmub.2.5): top bar and back only; the page
 * and its run detail are sharedUI's, the monitor is sharedLogic's RunMonitorController.
 */
@Composable
fun RunMonitorScreen(
    onNavigateBack: () -> Unit,
    onOpenConversation: (agentId: String, conversationId: String) -> Unit,
    viewModel: RunMonitorViewModel = hiltViewModel(),
) {
    val monitor by viewModel.state.collectAsStateWithLifecycle()
    val agentNames by viewModel.agentNames.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenConversation) { runsPageActions(viewModel.controller, onOpenConversation) }
    LettaPageScaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        header = {
            ShellHeader(
                title = HeaderTitle.Text(stringResource(R.string.screen_runs_title)),
                controls = HeaderControls(nav = HeaderNav.Back(onNavigateBack, stringResource(R.string.action_back))),
            )
        },
    ) { paddingValues ->
        val state = RunsPageState(monitor = monitor, agentNames = agentNames, canManageRuns = viewModel.controller.canManageRuns)
        ShellPage(
            destination = ShellDestination.Runs,
            deps = ShellPageDeps(admin = ShellAdminPages(runs = shellRunsPage(state, actions))),
            modifier = Modifier.padding(paddingValues),
        )
    }
}

private fun runsPageActions(
    controller: RunMonitorController,
    onOpenConversation: (agentId: String, conversationId: String) -> Unit,
) = RunsPageActions(
    onRefresh = controller::refresh,
    onQueryChange = controller::setQuery,
    onActiveOnlyChange = controller::setActiveOnly,
    onOpenRun = controller::open,
    onCloseRun = controller::closeDetail,
    onCancelRun = controller::cancel,
    onDeleteRun = controller::delete,
    onDismissError = controller::clearOperationError,
    onOpenConversation = onOpenConversation,
)
