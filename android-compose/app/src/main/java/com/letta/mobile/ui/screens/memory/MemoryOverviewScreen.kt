package com.letta.mobile.ui.screens.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.memory.MemoryPage
import com.letta.mobile.ui.memory.MemoryPageOptions
import com.letta.mobile.ui.shell.pages.memfs.MemfsPage
import com.letta.mobile.ui.shell.pages.memfs.MemfsPageOptions
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/** The phone's MemFS options: the top bar titles it and controls are touch-sized. */
private val AndroidMemfsPageOptions = MemfsPageOptions(showTitle = false, touch = true)

private enum class MemoryView { Blocks, Files }

/**
 * Android host for the shared [MemoryPage] and, under the Files switch, the shared MemFS browser
 * (letta-mobile-bzvro.37): top bar + back handling only. Both follow the agent chosen in the
 * memory graph's agent picker. Back closes an open node card before it leaves the screen.
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
    var view by rememberSaveable { mutableStateOf(MemoryView.Blocks) }
    BackHandler(enabled = view == MemoryView.Blocks && state.selection != null) { viewModel.actions.clearSelection() }
    // An open file closes first, through the shared unsaved-changes guard, so back never drops a
    // draft — also from Blocks: an unsaved draft brings Files back to show the guard.
    val closesFileFirst = files.editor?.let { view == MemoryView.Files || it.dirty } == true
    val closeFile = {
        view = MemoryView.Files
        filesViewModel.controller.closeFile()
    }
    BackHandler(enabled = closesFileFirst, onBack = closeFile)
    val onBack = { if (closesFileFirst) closeFile() else onNavigateBack() }
    val agentId = state.parity.memory.selectedAgentId
    LaunchedEffect(agentId) { filesViewModel.selectAgent(agentId) }

    Scaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_memory_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = LettaTopBarDefaults.topAppBarColors(),
            )
        },
    ) { paddingValues ->
        Column(Modifier.padding(paddingValues).fillMaxSize()) {
            MemoryViewSwitch(view = view, onSelect = { view = it })
            if (view == MemoryView.Files) {
                MemfsPage(
                    state = files,
                    actions = filesViewModel.controller,
                    options = AndroidMemfsPageOptions,
                    modifier = Modifier.weight(1f),
                )
            } else {
                MemoryPage(
                    state = state,
                    actions = viewModel.actions,
                    options = MemoryPageOptions(showTitle = false, reducedMotion = rememberReducedMotionEnabled()),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MemoryViewSwitch(view: MemoryView, onSelect: (MemoryView) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)) {
        MemoryView.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = view == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = MemoryView.entries.size),
            ) {
                Text(
                    stringResource(
                        when (option) {
                            MemoryView.Blocks -> R.string.screen_memory_view_blocks
                            MemoryView.Files -> R.string.screen_memory_view_files
                        },
                    ),
                )
            }
        }
    }
}
