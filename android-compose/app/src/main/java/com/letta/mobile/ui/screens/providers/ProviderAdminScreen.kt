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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.data.repository.modelcontrol.ProviderAdminController
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.modelcontrol.ProviderConnectionPane
import com.letta.mobile.ui.modelcontrol.ProviderFormActions
import com.letta.mobile.ui.modelcontrol.ProviderPaneActions

/**
 * App Server providers, live from the host (letta-mobile-w4q4p): connected
 * providers with Disconnect, every connectable provider with a Connect form
 * generated from its auth-method fields. The pane is shared with desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderAdminScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProviderAdminViewModel = hiltViewModel(),
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
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
                actions = {
                    IconButton(onClick = controller::refresh) {
                        Icon(LettaIcons.Refresh, stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { padding ->
        ProviderConnectionPane(
            state = state,
            actions = providerPaneActions(controller),
            modifier = Modifier.padding(padding),
        )
    }
}

internal fun providerPaneActions(controller: ProviderAdminController) = ProviderPaneActions(
    onConnect = controller::openConnect,
    onDisconnect = controller::requestDisconnect,
    form = ProviderFormActions(
        onChange = controller::updateForm,
        onSubmit = controller::submitConnect,
        onDismiss = controller::dismissForm,
    ),
    onConfirmDisconnect = controller::confirmDisconnect,
    onDismissDisconnect = controller::dismissDisconnect,
)
