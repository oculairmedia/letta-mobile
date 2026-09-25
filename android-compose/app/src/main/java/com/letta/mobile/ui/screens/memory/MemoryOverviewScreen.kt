package com.letta.mobile.ui.screens.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.memory.MemoryPage
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/**
 * Android host for the shared [MemoryPage]: top bar + back handling only.
 * Back closes an open node card before it leaves the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryOverviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: MemoryOverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.selection != null) { viewModel.actions.clearSelection() }

    Scaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_memory_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = LettaTopBarDefaults.topAppBarColors(),
            )
        },
    ) { paddingValues ->
        MemoryPage(
            state = state,
            actions = viewModel.actions,
            showTitle = false,
            reducedMotion = rememberReducedMotionEnabled(),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
