package com.letta.mobile.ui.screens.schedules

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageOptions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageState
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.shell.shellSchedulesPage
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/** The phone's page options: the top bar titles it, chips are touch-sized and it opens on the agenda. */
private val AndroidSchedulesPageOptions = SchedulesPageOptions(showTitle = false, touch = true, initialView = SchedulesView.Agenda)

/**
 * Android host for the shared Schedules page (letta-mobile-ckmub.3.16): top bar and back only; the
 * page is the shared ShellPage. The route's agent (opened from a chat) is pre-selected by the view
 * model; the page's agent chips switch it.
 */
@Composable
fun ScheduleListScreen(
    onNavigateBack: () -> Unit,
    viewModel: ScheduleListViewModel = hiltViewModel(),
) {
    val library by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        SchedulesPageActions(
            onRefresh = viewModel::loadData,
            onSelectAgent = viewModel::selectAgent,
            onCreate = viewModel::create,
            // Crons are read-only on the phone (no cron API): only native schedules delete.
            onDeleteSchedule = { ref -> viewModel.deleteSchedule(ref.scheduleId) },
        )
    }
    LettaPageScaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        header = {
            ShellHeader(
                title = HeaderTitle.Text(stringResource(R.string.screen_schedules_title)),
                controls = HeaderControls(nav = HeaderNav.Back(onNavigateBack, stringResource(R.string.action_back))),
            )
        },
    ) { paddingValues ->
        ShellPage(
            destination = ShellDestination.Schedules(library.selectedAgentId),
            deps = ShellPageDeps(schedules = shellSchedulesPage(androidSchedulesPageState(library), actions, AndroidSchedulesPageOptions)),
            modifier = Modifier.padding(paddingValues),
        )
    }
}

/**
 * The phone creates through the native schedule route only: a cron-backed backend (no native
 * route) lists its crons read-only.
 */
internal fun androidSchedulesPageState(library: ScheduleLibraryState): SchedulesPageState = SchedulesPageState(
    library = library,
    crons = if (library.cronMode) library.cronsForSelectedAgent else emptyList(),
    canCreate = library.scheduleAdminAvailable && !library.cronMode && library.selectedAgentId != null,
)
