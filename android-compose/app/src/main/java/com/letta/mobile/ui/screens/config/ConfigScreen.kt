package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onNavigateBack: () -> Unit,
    onNavigateToConfigList: () -> Unit,
    viewModel: ConfigViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToConfigList) {
                        Icon(Icons.Default.List, stringResource(R.string.screen_config_list_title))
                    }
                }
            )
        }
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadConfig() },
                modifier = Modifier.padding(paddingValues)
            )
            is UiState.Success -> ConfigContent(
                state = state.data,
                onModeChange = { viewModel.updateMode(it) },
                onServerUrlChange = { viewModel.updateServerUrl(it) },
                onApiTokenChange = { viewModel.updateApiToken(it) },
                onThemeChange = { viewModel.updateTheme(it) },
                onSave = {
                    viewModel.saveConfig(
                        onSuccess = { snackbar.dispatch("Configuration saved"); onNavigateBack() },
                        onError = { snackbar.dispatch(it) },
                    )
                },
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun ConfigContent(
    state: ConfigUiState,
    onModeChange: (ServerMode) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onApiTokenChange: (String) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.screen_config_server_section),
            style = MaterialTheme.typography.titleLarge
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.mode == ServerMode.CLOUD,
                onClick = { onModeChange(ServerMode.CLOUD) },
                label = { Text(stringResource(R.string.common_cloud)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = state.mode == ServerMode.SELF_HOSTED,
                onClick = { onModeChange(ServerMode.SELF_HOSTED) },
                label = { Text(stringResource(R.string.common_self_hosted)) },
                modifier = Modifier.weight(1f)
            )
        }

        if (state.mode == ServerMode.SELF_HOSTED) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text(stringResource(R.string.common_server_url)) },
                placeholder = { Text(stringResource(R.string.screen_config_server_url_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Link, null)
                }
            )
        }

        OutlinedTextField(
            value = state.apiToken,
            onValueChange = onApiTokenChange,
            label = { Text(stringResource(R.string.common_api_token)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = {
                Icon(Icons.Default.Key, null)
            }
        )

        Divider()

        Text(
            text = stringResource(R.string.screen_config_appearance_section),
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.common_dark_theme),
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = state.isDarkTheme,
                onCheckedChange = onThemeChange
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_save_configuration))
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error
                )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
