package com.letta.mobile.ui.screens.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.memory.MemoryPageOptions
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.pages.memfs.MemfsPageOptions
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceBinding
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceView
import com.letta.mobile.ui.shell.pages.memory.rememberMemoryWorkspaceView
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.shell.shellMemoryPage
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/** The phone's MemFS options: the top bar titles it and controls are touch-sized. */
private val AndroidMemfsPageOptions = MemfsPageOptions(showTitle = false, touch = true)

/**
 * Android host for the shared Memory page (the memory graph and, under the Files switch, the shared
 * MemFS browser; letta-mobile-bzvro.37), drawn by ShellPage (letta-mobile-ckmub.3.4): top bar +
 * back handling only. Back closes an open node card before it leaves the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryOverviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: MemoryOverviewViewModel = hiltViewModel(),
    filesViewModel: MemoryFilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val files by filesViewModel.state.collectAsStateWithLifecycle()
    var view by rememberMemoryWorkspaceView()
    BackHandler(enabled = view == MemoryWorkspaceView.Blocks && state.selection != null) { viewModel.actions.clearSelection() }
    // An open file closes first, through the shared unsaved-changes guard, so back never drops a
    // draft — also from Blocks: an unsaved draft brings Files back to show the guard.
    val closesFileFirst = files.editor?.let { view == MemoryWorkspaceView.Files || it.dirty } == true
    val closeFile = {
        view = MemoryWorkspaceView.Files
        filesViewModel.controller.closeFile()
    }
    BackHandler(enabled = closesFileFirst, onBack = closeFile)
    val onBack = { if (closesFileFirst) closeFile() else onNavigateBack() }
    val binding = MemoryWorkspaceBinding(
        memory = state,
        memoryActions = viewModel.actions,
        files = files,
        filesActions = filesViewModel.controller,
        onFilesAgentChange = filesViewModel::selectAgent,
        view = view,
        onViewChange = { view = it },
        memoryOptions = MemoryPageOptions(showTitle = false, reducedMotion = rememberReducedMotionEnabled()),
        filesOptions = AndroidMemfsPageOptions,
    )

    LettaPageScaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        header = {
            ShellHeader(
                title = HeaderTitle.Text(stringResource(R.string.screen_memory_title)),
                controls = HeaderControls(nav = HeaderNav.Back(onBack, stringResource(R.string.action_back))),
            )
        },
    ) { paddingValues ->
        ShellPage(
            destination = ShellDestination.Memory(state.parity.memory.selectedAgentId),
            deps = ShellPageDeps(memory = shellMemoryPage(binding)),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
