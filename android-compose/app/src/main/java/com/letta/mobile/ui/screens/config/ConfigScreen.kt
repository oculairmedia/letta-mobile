package com.letta.mobile.ui.screens.config

import android.content.ActivityNotFoundException
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.platform.BatteryOptimizationHelper
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.util.Telemetry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onNavigateBack: () -> Unit,
    onNavigateToConfigList: () -> Unit,
    onNavigateToLettaBotConnection: () -> Unit = {},
    onNavigateToSystemAccess: () -> Unit = {},
    viewModel: ConfigViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryOptimizationExempt by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    fun refreshBatteryOptimizationStatus(source: String) {
        val exempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        batteryOptimizationExempt = exempt
        Telemetry.event(
            "BatteryOptimization",
            "status",
            "source" to source,
            "exempt" to exempt,
        )
    }

    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshBatteryOptimizationStatus("requestReturned")
        Telemetry.event(
            "BatteryOptimization",
            "requestReturned",
            "exempt" to BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context),
        )
    }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshBatteryOptimizationStatus("resume")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                batteryOptimizationExempt = batteryOptimizationExempt,
                onRequestBatteryOptimizationExemption = {
                    Telemetry.event(
                        "BatteryOptimization",
                        "requestTapped",
                        "exemptBefore" to batteryOptimizationExempt,
                    )
                    try {
                        batteryOptimizationLauncher.launch(BatteryOptimizationHelper.requestExemptionIntent(context))
                        Telemetry.event("BatteryOptimization", "requestLaunched", "target" to "requestExemption")
                    } catch (primaryError: ActivityNotFoundException) {
                        try {
                            batteryOptimizationLauncher.launch(BatteryOptimizationHelper.batteryOptimizationSettingsIntent())
                            Telemetry.event("BatteryOptimization", "requestLaunched", "target" to "settingsFallback")
                        } catch (fallbackError: ActivityNotFoundException) {
                            Telemetry.error(
                                "BatteryOptimization",
                                "requestFailed",
                                fallbackError,
                                "primaryError" to primaryError.javaClass.simpleName,
                            )
                            snackbar.dispatch(context.getString(R.string.screen_config_battery_optimization_request_failed))
                        }
                    }
                },
                onNavigateToLettaBotConnection = onNavigateToLettaBotConnection,
                onNavigateToSystemAccess = onNavigateToSystemAccess,
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
    batteryOptimizationExempt: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onNavigateToLettaBotConnection: () -> Unit,
    onNavigateToSystemAccess: () -> Unit,
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
            item(
                headlineContent = {
                    val isCloud = state.mode == ServerMode.CLOUD
                    OutlinedTextField(
                        value = if (isCloud) ConfigViewModel.DEFAULT_CLOUD_URL else state.serverUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text(stringResource(R.string.common_server_url)) },
                        placeholder = { Text(stringResource(R.string.screen_config_server_url_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(LettaIcons.Link, null) },
                        readOnly = isCloud,
                        enabled = !isCloud,
                    )
                },
            )
            item(
                headlineContent = {
                    var tokenVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = state.apiToken,
                        onValueChange = onApiTokenChange,
                        label = { Text(stringResource(R.string.common_api_token)) },
                        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(LettaIcons.Key, null) },
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (tokenVisible) "Hide token" else "Show token",
                                )
                            }
                        },
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

        CardGroup(title = { Text(stringResource(R.string.screen_config_background_delivery_section)) }) {
            item(
                headlineContent = { Text(stringResource(R.string.screen_config_reliable_background_delivery)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (batteryOptimizationExempt) {
                                R.string.screen_config_battery_optimization_exempt_description
                            } else {
                                R.string.screen_config_battery_optimization_restricted_description
                            }
                        )
                    )
                },
                leadingContent = { Icon(LettaIcons.Settings, contentDescription = null) },
                trailingContent = {
                    if (batteryOptimizationExempt) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(stringResource(R.string.screen_config_battery_optimization_status_unrestricted)) },
                            leadingIcon = { Icon(LettaIcons.CheckCircle, contentDescription = null) },
                        )
                    } else {
                        TextButton(onClick = onRequestBatteryOptimizationExemption) {
                            Text(stringResource(R.string.screen_config_battery_optimization_allow_action))
                        }
                    }
                },
            )
        }

        CardGroup(title = { Text(stringResource(R.string.screen_config_integrations_section)) }) {
            item(
                onClick = onNavigateToLettaBotConnection,
                headlineContent = { Text(stringResource(R.string.screen_lettabot_connection_title)) },
                supportingContent = {
                    Text(stringResource(R.string.screen_lettabot_connection_entry_description))
                },
                leadingContent = { Icon(LettaIcons.Link, contentDescription = null) },
            )
            item(
                onClick = onNavigateToSystemAccess,
                headlineContent = { Text(stringResource(R.string.screen_system_access_title)) },
                supportingContent = { Text(stringResource(R.string.screen_system_access_entry_description)) },
                leadingContent = { Icon(LettaIcons.Key, contentDescription = null) },
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
