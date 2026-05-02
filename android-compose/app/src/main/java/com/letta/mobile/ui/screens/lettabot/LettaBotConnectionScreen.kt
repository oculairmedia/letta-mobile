package com.letta.mobile.ui.screens.lettabot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.screens.settings.ClientModeConnectionState
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.ui.theme.listItemSupporting
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Global LettaBot connection settings pane (gb57.9).
 *
 * Hosts the master Client Mode toggle, server URL, API key, and on-demand connection
 * test. Replaces the section that used to render inside [com.letta.mobile.ui.screens.settings.AgentSettingsScreen]
 * so that the LettaBot connection lives at app scope, not per-agent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LettaBotConnectionScreen(
    onNavigateBack: () -> Unit,
    viewModel: LettaBotConnectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current

    Scaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_lettabot_connection_title)) },
                colors = LettaTopBarDefaults.topAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.load() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> LettaBotConnectionContent(
                state = state.data,
                onEnabledChange = viewModel::setEnabled,
                onBaseUrlChange = viewModel::setBaseUrl,
                onApiKeyChange = viewModel::setApiKey,
                onTestConnection = viewModel::testConnection,
                onSave = {
                    viewModel.save(
                        onSuccess = {
                            snackbar.dispatch(context.getString(R.string.screen_lettabot_connection_saved))
                        },
                        onError = { msg -> snackbar.dispatch(msg) },
                    )
                },
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

@Composable
internal fun LettaBotConnectionContent(
    state: LettaBotConnectionUiState,
    onEnabledChange: (Boolean) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardGroup(title = { Text(stringResource(R.string.screen_lettabot_connection_section)) }) {
            item(
                headlineContent = {
                    FormItem(
                        label = { Text(stringResource(R.string.screen_lettabot_connection_enable)) },
                        description = {
                            Text(stringResource(R.string.screen_lettabot_connection_enable_description))
                        },
                        tail = {
                            Switch(
                                checked = state.enabled,
                                onCheckedChange = onEnabledChange,
                            )
                        },
                    )
                },
            )

            if (state.enabled) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.baseUrl,
                            onValueChange = onBaseUrlChange,
                            label = { Text(stringResource(R.string.screen_settings_client_mode_server_url)) },
                            placeholder = {
                                Text(stringResource(R.string.screen_settings_client_mode_server_url_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.apiKey,
                            onValueChange = onApiKeyChange,
                            label = { Text(stringResource(R.string.screen_settings_client_mode_api_key)) },
                            placeholder = {
                                Text(stringResource(R.string.screen_settings_client_mode_api_key_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.screen_settings_client_mode_api_key_helper),
                            style = MaterialTheme.typography.listItemSupporting,
                        )
                    },
                )
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text(stringResource(R.string.screen_settings_client_mode_test_connection)) },
                            description = {
                                val statusText = connectionStatusText(state.connectionState)
                                if (statusText != null) {
                                    Text(
                                        text = statusText,
                                        color = connectionStatusColor(state.connectionState),
                                    )
                                } else {
                                    Text(stringResource(R.string.screen_settings_client_mode_test_connection_helper))
                                }
                            },
                            tail = {
                                OutlinedButton(
                                    onClick = onTestConnection,
                                    enabled = state.connectionState !is ClientModeConnectionState.Testing &&
                                        state.baseUrl.isNotBlank(),
                                ) {
                                    if (state.connectionState is ClientModeConnectionState.Testing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text(stringResource(R.string.screen_settings_client_mode_test_connection_action))
                                    }
                                }
                            },
                        )
                    },
                )
            }
        }

        CardGroup {
            item(
                onClick = if (state.isSaving) ({}) else onSave,
                headlineContent = {
                    Text(stringResource(R.string.action_save_settings))
                },
                leadingContent = {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(LettaIcons.Check, contentDescription = null)
                    }
                },
            )
        }
    }
}

@Composable
private fun connectionStatusText(state: ClientModeConnectionState): String? {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())

    return when (state) {
        ClientModeConnectionState.Idle -> null
        ClientModeConnectionState.Testing -> stringResource(R.string.screen_settings_client_mode_testing)
        is ClientModeConnectionState.Success -> stringResource(
            R.string.screen_settings_client_mode_success,
            formatter.format(Instant.ofEpochMilli(state.testedAtMillis).atZone(ZoneId.systemDefault())),
        )
        is ClientModeConnectionState.Failure -> stringResource(
            R.string.screen_settings_client_mode_failure,
            state.message,
            formatter.format(Instant.ofEpochMilli(state.testedAtMillis).atZone(ZoneId.systemDefault())),
        )
    }
}

@Composable
private fun connectionStatusColor(state: ClientModeConnectionState) = when (state) {
    ClientModeConnectionState.Idle,
    ClientModeConnectionState.Testing,
    -> MaterialTheme.colorScheme.onSurfaceVariant
    is ClientModeConnectionState.Success -> MaterialTheme.colorScheme.tertiary
    is ClientModeConnectionState.Failure -> MaterialTheme.colorScheme.error
}
