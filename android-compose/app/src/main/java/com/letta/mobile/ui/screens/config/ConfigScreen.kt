package com.letta.mobile.ui.screens.config

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults

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
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
                colors = LettaTopBarDefaults.topAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToConfigList) {
                        Icon(LettaIcons.ListIcon, stringResource(R.string.screen_config_list_title))
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
                onThemePresetChange = { viewModel.updateThemePreset(it) },
                onDynamicColorChange = { viewModel.updateDynamicColor(it) },
                onEnableProjectsChange = { viewModel.updateEnableProjects(it) },
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
    onThemeChange: (AppTheme) -> Unit,
    onThemePresetChange: (ThemePreset) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onEnableProjectsChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup(title = { Text(stringResource(R.string.screen_config_server_section)) }) {
            item(
                headlineContent = {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.mode == ServerMode.CLOUD,
                            onClick = { onModeChange(ServerMode.CLOUD) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            label = { Text(stringResource(R.string.common_cloud)) },
                        )
                        SegmentedButton(
                            selected = state.mode == ServerMode.SELF_HOSTED,
                            onClick = { onModeChange(ServerMode.SELF_HOSTED) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            label = { Text(stringResource(R.string.common_self_hosted)) },
                        )
                    }
                },
            )
            if (state.mode == ServerMode.CLOUD) {
                item(
                    headlineContent = { Text(stringResource(R.string.common_server_url)) },
                    supportingContent = { Text(ConfigViewModel.DEFAULT_CLOUD_URL) },
                    leadingContent = { Icon(LettaIcons.Link, null) },
                )
            } else {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.serverUrl,
                            onValueChange = onServerUrlChange,
                            label = { Text(stringResource(R.string.common_server_url)) },
                            placeholder = { Text(stringResource(R.string.screen_config_server_url_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(LettaIcons.Link, null) },
                        )
                    },
                )
            }
            item(
                headlineContent = {
                    OutlinedTextField(
                        value = state.apiToken,
                        onValueChange = onApiTokenChange,
                        label = { Text(stringResource(R.string.common_api_token)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(LettaIcons.Key, null) },
                    )
                },
            )
        }

        CardGroup(title = { Text(stringResource(R.string.screen_config_appearance_section)) }) {
            item(
                headlineContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.screen_config_theme_mode))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = state.theme == AppTheme.SYSTEM,
                                onClick = { onThemeChange(AppTheme.SYSTEM) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                                label = { Text(stringResource(R.string.screen_config_theme_mode_system)) },
                            )
                            SegmentedButton(
                                selected = state.theme == AppTheme.LIGHT,
                                onClick = { onThemeChange(AppTheme.LIGHT) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                                label = { Text(stringResource(R.string.common_light_theme)) },
                            )
                            SegmentedButton(
                                selected = state.theme == AppTheme.DARK,
                                onClick = { onThemeChange(AppTheme.DARK) },
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                                label = { Text(stringResource(R.string.common_dark_theme)) },
                            )
                        }
                    }
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.screen_config_dynamic_color)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (dynamicColorSupported) {
                                R.string.screen_config_dynamic_color_supported
                            } else {
                                R.string.screen_config_dynamic_color_unsupported
                            }
                        )
                    )
                },
                trailingContent = {
                    Switch(
                        checked = state.dynamicColor,
                        onCheckedChange = onDynamicColorChange,
                        enabled = dynamicColorSupported,
                    )
                },
            )
            item(
                headlineContent = {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = state.themePreset == preset,
                                onClick = { onThemePresetChange(preset) },
                                label = { Text(themePresetLabel(preset)) },
                            )
                        }
                    }
                },
                supportingContent = {
                    Text(
                        stringResource(
                            if (state.dynamicColor && dynamicColorSupported) {
                                R.string.screen_config_theme_preset_overridden
                            } else {
                                R.string.screen_config_theme_preset
                            }
                        )
                    )
                },
            )
        }

        CardGroup(title = { Text(stringResource(R.string.screen_config_features_section)) }) {
            item(
                headlineContent = { Text(stringResource(R.string.screen_config_enable_projects)) },
                supportingContent = { Text(stringResource(R.string.screen_config_enable_projects_description)) },
                trailingContent = {
                    Switch(
                        checked = state.enableProjects,
                        onCheckedChange = onEnableProjectsChange,
                    )
                },
            )
        }

        CardGroup {
            item(
                onClick = onSave,
                headlineContent = { Text(stringResource(R.string.action_save_configuration)) },
                leadingContent = { Icon(LettaIcons.Save, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun themePresetLabel(themePreset: ThemePreset): String {
    return when (themePreset) {
        ThemePreset.DEFAULT -> stringResource(R.string.screen_config_theme_preset_default)
        ThemePreset.OCEAN -> stringResource(R.string.screen_config_theme_preset_ocean)
        ThemePreset.AMOLED_BLACK -> stringResource(R.string.screen_config_theme_preset_amoled_black)
        ThemePreset.SAKURA -> stringResource(R.string.screen_config_theme_preset_sakura)
        ThemePreset.AUTUMN -> stringResource(R.string.screen_config_theme_preset_autumn)
        ThemePreset.SPRING -> stringResource(R.string.screen_config_theme_preset_spring)
    }
}
