package com.letta.mobile.ui.screens.providers

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.modelcontrol.ProviderSettingsActions
import com.letta.mobile.ui.modelcontrol.ProviderSettingsPane

/**
 * Providers, live from the host (letta-mobile-w4q4p.6 / .6.1): Accounts, API
 * keys, Custom Endpoints and Models as tabs. The pane is shared with desktop;
 * this screen only adds the app bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderAdminScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProviderAdminViewModel = hiltViewModel(),
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val actions = remember(controller) { ProviderSettingsActions.bind(controller) }
    Scaffold(
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_providers_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        ProviderSettingsPane(state = state, actions = actions, modifier = Modifier.padding(padding))
    }
}
