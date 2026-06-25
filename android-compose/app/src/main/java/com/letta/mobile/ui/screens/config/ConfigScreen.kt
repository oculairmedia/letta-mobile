package com.letta.mobile.ui.screens.config

import android.content.ActivityNotFoundException
import android.os.Build
import com.letta.mobile.BuildConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.platform.BatteryOptimizationHelper
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.util.Telemetry
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onNavigateBack: () -> Unit,
    onNavigateToConfigList: () -> Unit,
    onNavigateToSystemAccess: () -> Unit = {},
    onNavigateToVibesyncDebug: () -> Unit = {},
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
    val localModelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalModel(
                uri = uri,
                onSuccess = { fileName ->
                    snackbar.dispatch(context.getString(R.string.screen_config_local_model_import_success, fileName))
                },
                onError = { snackbar.dispatch(it) },
            )
        }
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                onHapticsEnabledChange = { viewModel.updateHapticsEnabled(it) },
                onLocalModelPathChange = { viewModel.updateLocalModelPath(it) },
                onLocalModelHandleChange = { viewModel.updateLocalModelHandle(it) },
                onLocalModelAcceleratorChange = { viewModel.updateLocalModelAccelerator(it) },
                onLocalModelMaxTokensChange = { viewModel.updateLocalModelMaxTokens(it) },
                onLocalProviderBaseUrlChange = { viewModel.updateLocalProviderBaseUrl(it) },
                onLocalProviderApiKeyChange = { viewModel.updateLocalProviderApiKey(it) },
                onLocalProviderModelChange = { viewModel.updateLocalProviderModel(it) },
                onHuggingFaceTokenChange = { viewModel.updateHuggingFaceToken(it) },
                onImportLocalModel = {
                    localModelImportLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                onDownloadEmbeddedModel = { viewModel.downloadEmbeddedModel(it) },
                onCancelEmbeddedModelDownload = { viewModel.cancelEmbeddedModelDownload(it) },
                onSelectEmbeddedModel = { viewModel.selectEmbeddedModel(it) },
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
                onNavigateToSystemAccess = onNavigateToSystemAccess,
                onNavigateToVibesyncDebug = onNavigateToVibesyncDebug,
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
    onHapticsEnabledChange: (Boolean) -> Unit,
    onLocalModelPathChange: (String) -> Unit,
    onLocalModelHandleChange: (String) -> Unit,
    onLocalModelAcceleratorChange: (String) -> Unit,
    onLocalModelMaxTokensChange: (String) -> Unit,
    onLocalProviderBaseUrlChange: (String) -> Unit,
    onLocalProviderApiKeyChange: (String) -> Unit,
    onLocalProviderModelChange: (String) -> Unit,
    onHuggingFaceTokenChange: (String) -> Unit,
    onImportLocalModel: () -> Unit,
    onDownloadEmbeddedModel: (EmbeddedModelCatalogItem) -> Unit,
    onCancelEmbeddedModelDownload: (EmbeddedModelCatalogItem) -> Unit,
    onSelectEmbeddedModel: (EmbeddedModelCatalogItem) -> Unit,
    batteryOptimizationExempt: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onNavigateToSystemAccess: () -> Unit,
    onNavigateToVibesyncDebug: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

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
                            onClick = {
                                HapticEffects.segmentTick(haptic, view, enabled = state.mode != ServerMode.CLOUD)
                                onModeChange(ServerMode.CLOUD)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                            label = { Text(stringResource(R.string.common_cloud)) },
                        )
                        SegmentedButton(
                            selected = state.mode == ServerMode.SELF_HOSTED,
                            onClick = {
                                HapticEffects.segmentTick(haptic, view, enabled = state.mode != ServerMode.SELF_HOSTED)
                                onModeChange(ServerMode.SELF_HOSTED)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                            label = { Text(stringResource(R.string.common_self_hosted)) },
                        )
                        SegmentedButton(
                            selected = state.mode == ServerMode.LOCAL,
                            onClick = {
                                HapticEffects.segmentTick(haptic, view, enabled = state.mode != ServerMode.LOCAL)
                                onModeChange(ServerMode.LOCAL)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                            label = { Text(stringResource(R.string.common_local_runtime)) },
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    val isCloud = state.mode == ServerMode.CLOUD
                    val isLocal = state.mode == ServerMode.LOCAL
                    OutlinedTextField(
                        value = when {
                            isCloud -> ConfigViewModel.DEFAULT_CLOUD_URL
                            isLocal -> ConfigViewModel.LOCAL_RUNTIME_URL
                            else -> state.serverUrl
                        },
                        onValueChange = onServerUrlChange,
                        label = { Text(stringResource(R.string.common_server_url)) },
                        placeholder = { Text(stringResource(R.string.screen_config_server_url_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(LettaIcons.Link, null) },
                        readOnly = isCloud || isLocal,
                        enabled = !isCloud && !isLocal,
                    )
                },
            )
            if (state.mode == ServerMode.LOCAL) {
                item(
                    headlineContent = {
                        EmbeddedRuntimeStatusItem(status = state.embeddedRuntimeStatus)
                    },
                )
                item(
                    headlineContent = {
                        LocalModelSettingsItem(
                            state = state,
                            onLocalModelPathChange = onLocalModelPathChange,
                            onLocalModelHandleChange = onLocalModelHandleChange,
                            onLocalModelAcceleratorChange = onLocalModelAcceleratorChange,
                            onLocalModelMaxTokensChange = onLocalModelMaxTokensChange,
                            onLocalProviderBaseUrlChange = onLocalProviderBaseUrlChange,
                            onLocalProviderApiKeyChange = onLocalProviderApiKeyChange,
                            onLocalProviderModelChange = onLocalProviderModelChange,
                            onHuggingFaceTokenChange = onHuggingFaceTokenChange,
                            onImportLocalModel = onImportLocalModel,
                            onDownloadEmbeddedModel = onDownloadEmbeddedModel,
                            onCancelEmbeddedModelDownload = onCancelEmbeddedModelDownload,
                            onSelectEmbeddedModel = onSelectEmbeddedModel,
                        )
                    },
                )
            }
            if (state.mode != ServerMode.LOCAL) {
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
        }

        CardGroup(title = { Text(stringResource(R.string.screen_config_appearance_section)) }) {
            item(
                headlineContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.screen_config_theme_mode))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = state.theme == AppTheme.SYSTEM,
                                onClick = {
                                    HapticEffects.segmentTick(haptic, view, enabled = state.theme != AppTheme.SYSTEM)
                                    onThemeChange(AppTheme.SYSTEM)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                                label = { Text(stringResource(R.string.screen_config_theme_mode_system)) },
                            )
                            SegmentedButton(
                                selected = state.theme == AppTheme.LIGHT,
                                onClick = {
                                    HapticEffects.segmentTick(haptic, view, enabled = state.theme != AppTheme.LIGHT)
                                    onThemeChange(AppTheme.LIGHT)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                                label = { Text(stringResource(R.string.common_light_theme)) },
                            )
                            SegmentedButton(
                                selected = state.theme == AppTheme.DARK,
                                onClick = {
                                    HapticEffects.segmentTick(haptic, view, enabled = state.theme != AppTheme.DARK)
                                    onThemeChange(AppTheme.DARK)
                                },
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
                    HapticSwitch(
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
                                onClick = {
                                    HapticEffects.segmentTick(haptic, view, enabled = state.themePreset != preset)
                                    onThemePresetChange(preset)
                                },
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
                    HapticSwitch(
                        checked = state.enableProjects,
                        onCheckedChange = onEnableProjectsChange,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.screen_config_haptics)) },
                supportingContent = { Text(stringResource(R.string.screen_config_haptics_description)) },
                trailingContent = {
                    HapticSwitch(
                        checked = state.hapticsEnabled,
                        onCheckedChange = onHapticsEnabledChange,
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
                onClick = onNavigateToSystemAccess,
                headlineContent = { Text(stringResource(R.string.screen_system_access_title)) },
                supportingContent = { Text(stringResource(R.string.screen_system_access_entry_description)) },
                leadingContent = { Icon(LettaIcons.Key, contentDescription = null) },
            )
            if (BuildConfig.DEBUG) {
                item(
                    onClick = onNavigateToVibesyncDebug,
                    headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_title)) },
                    supportingContent = { Text(stringResource(R.string.screen_vibesync_debug_entry_description)) },
                    leadingContent = { Icon(LettaIcons.Database, contentDescription = null) },
                )
            }
        }

        CardGroup {
            item(
                onClick = if (state.isSaving) null else onSave,
                headlineContent = { Text(stringResource(R.string.action_save_configuration)) },
                leadingContent = { Icon(LettaIcons.Save, contentDescription = null) },
                trailingContent = {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                },
            )
        }
    }
}

@Composable
private fun EmbeddedRuntimeStatusItem(
    status: EmbeddedLettaCodeRuntimeStatus,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = LettaIcons.Psychology,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.screen_config_embedded_runtime_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(
                        if (status.runnable) {
                            R.string.screen_config_embedded_runtime_enabled_placeholder
                        } else {
                            R.string.screen_config_embedded_runtime_disabled_placeholder
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.runnable) {
                    Text(
                        text = stringResource(R.string.screen_config_embedded_runtime_notifications_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(stringResource(R.string.screen_config_embedded_runtime_version, status.version.ifBlank { "disabled" }))
                },
            )
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        stringResource(
                            if (status.runnable) {
                                R.string.screen_config_embedded_runtime_execution_enabled
                            } else {
                                R.string.screen_config_embedded_runtime_execution_disabled
                            }
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun LocalModelSettingsItem(
    state: ConfigUiState,
    onLocalModelPathChange: (String) -> Unit,
    onLocalModelHandleChange: (String) -> Unit,
    onLocalModelAcceleratorChange: (String) -> Unit,
    onLocalModelMaxTokensChange: (String) -> Unit,
    onLocalProviderBaseUrlChange: (String) -> Unit,
    onLocalProviderApiKeyChange: (String) -> Unit,
    onLocalProviderModelChange: (String) -> Unit,
    onHuggingFaceTokenChange: (String) -> Unit,
    onImportLocalModel: () -> Unit,
    onDownloadEmbeddedModel: (EmbeddedModelCatalogItem) -> Unit,
    onCancelEmbeddedModelDownload: (EmbeddedModelCatalogItem) -> Unit,
    onSelectEmbeddedModel: (EmbeddedModelCatalogItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.screen_config_on_device_model_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        var hfTokenVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = state.huggingFaceToken,
            onValueChange = onHuggingFaceTokenChange,
            label = { Text(stringResource(R.string.screen_config_hugging_face_token)) },
            placeholder = { Text(stringResource(R.string.screen_config_hugging_face_token_placeholder)) },
            visualTransformation = if (hfTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(LettaIcons.Key, null) },
            trailingIcon = {
                IconButton(onClick = { hfTokenVisible = !hfTokenVisible }) {
                    Icon(
                        imageVector = if (hfTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (hfTokenVisible) "Hide token" else "Show token",
                    )
                }
            },
            singleLine = true,
        )
        EmbeddedModelCatalogSection(
            items = state.embeddedModelCatalog,
            selectedPath = state.localModelPath,
            hasHuggingFaceToken = state.huggingFaceToken.isNotBlank(),
            onDownload = onDownloadEmbeddedModel,
            onCancel = onCancelEmbeddedModelDownload,
            onSelect = onSelectEmbeddedModel,
        )
        OutlinedTextField(
            value = state.localModelPath,
            onValueChange = onLocalModelPathChange,
            label = { Text(stringResource(R.string.screen_config_local_model_path)) },
            placeholder = { Text(stringResource(R.string.screen_config_local_model_path_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(LettaIcons.Database, null) },
            singleLine = true,
        )
        OutlinedButton(
            onClick = onImportLocalModel,
            enabled = !state.isImportingLocalModel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isImportingLocalModel) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Icon(LettaIcons.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    if (state.isImportingLocalModel) {
                        R.string.screen_config_local_model_importing
                    } else {
                        R.string.screen_config_local_model_import
                    }
                )
            )
        }
        OutlinedTextField(
            value = state.localModelHandle,
            onValueChange = onLocalModelHandleChange,
            label = { Text(stringResource(R.string.screen_config_local_model_handle)) },
            placeholder = { Text(stringResource(R.string.screen_config_local_model_handle_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(LettaIcons.Psychology, null) },
            singleLine = true,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.screen_config_local_model_accelerator),
                style = MaterialTheme.typography.bodyMedium,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LocalModelAcceleratorOption.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = state.localModelAccelerator == option.value,
                        onClick = {
                            HapticEffects.segmentTick(
                                haptic,
                                view,
                                enabled = state.localModelAccelerator != option.value,
                            )
                            onLocalModelAcceleratorChange(option.value)
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = LocalModelAcceleratorOption.entries.size,
                        ),
                        label = { Text(stringResource(option.labelRes)) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.localModelMaxTokens,
            onValueChange = onLocalModelMaxTokensChange,
            label = { Text(stringResource(R.string.screen_config_local_model_max_tokens)) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            leadingIcon = { Icon(LettaIcons.Settings, null) },
            singleLine = true,
        )
        Text(
            text = stringResource(R.string.screen_config_local_provider_section),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.screen_config_local_provider_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.localProviderBaseUrl,
            onValueChange = onLocalProviderBaseUrlChange,
            label = { Text(stringResource(R.string.screen_config_local_provider_base_url)) },
            placeholder = { Text("http://192.168.1.10:8082/v1") },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(LettaIcons.Link, null) },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.localProviderApiKey,
            onValueChange = onLocalProviderApiKeyChange,
            label = { Text(stringResource(R.string.screen_config_local_provider_api_key)) },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(LettaIcons.Key, null) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
    }
}

@Composable
private fun EmbeddedModelCatalogSection(
    items: List<EmbeddedModelCatalogItem>,
    selectedPath: String,
    hasHuggingFaceToken: Boolean,
    onDownload: (EmbeddedModelCatalogItem) -> Unit,
    onCancel: (EmbeddedModelCatalogItem) -> Unit,
    onSelect: (EmbeddedModelCatalogItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.screen_config_embedded_model_catalog),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.screen_config_embedded_model_catalog_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items.forEach { item ->
            EmbeddedModelCatalogRow(
                item = item,
                selected = (item.state as? EmbeddedModelDownloadState.Downloaded)?.localPath == selectedPath,
                hasHuggingFaceToken = hasHuggingFaceToken,
                onDownload = { onDownload(item) },
                onCancel = { onCancel(item) },
                onSelect = { onSelect(item) },
            )
        }
    }
}

@Composable
private fun EmbeddedModelCatalogRow(
    item: EmbeddedModelCatalogItem,
    selected: Boolean,
    hasHuggingFaceToken: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
) {
    val entry = item.entry
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(
                    R.string.screen_config_embedded_model_size,
                    formatBytes(entry.sizeInBytes),
                    formatBytes(entry.estimatedPeakMemoryInBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (entry.requiresAuth && !hasHuggingFaceToken) {
                AssistChip(
                    enabled = false,
                    onClick = {},
                    label = { Text(stringResource(R.string.screen_config_embedded_model_requires_hf_token_badge)) },
                )
                Text(
                    text = stringResource(R.string.screen_config_embedded_model_requires_hf_token_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!entry.isSupported) {
                AssistChip(
                    enabled = false,
                    onClick = {},
                    label = { Text(entry.unsupportedReason ?: stringResource(R.string.screen_config_embedded_model_unsupported)) },
                )
            } else {
                when (val state = item.state) {
                    EmbeddedModelDownloadState.Idle,
                    EmbeddedModelDownloadState.Cancelled,
                    is EmbeddedModelDownloadState.Failed -> {
                        if (state is EmbeddedModelDownloadState.Failed) {
                            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        val downloadEnabled = !entry.requiresAuth || hasHuggingFaceToken
                        OutlinedButton(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = downloadEnabled,
                        ) {
                            Text(
                                stringResource(
                                    if (downloadEnabled) {
                                        R.string.screen_config_embedded_model_download
                                    } else {
                                        R.string.screen_config_embedded_model_add_hf_token
                                    }
                                )
                            )
                        }
                    }
                    is EmbeddedModelDownloadState.Downloading -> {
                        val progress = embeddedModelDownloadProgress(state.bytesDownloaded, state.totalBytes)
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = embeddedModelDownloadProgressLabel(state.bytesDownloaded, state.totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.screen_config_embedded_model_cancel))
                        }
                    }
                    is EmbeddedModelDownloadState.Downloaded -> {
                        FilledTonalButton(onClick = onSelect, modifier = Modifier.fillMaxWidth(), enabled = !selected) {
                            Text(
                                stringResource(
                                    if (selected) R.string.screen_config_embedded_model_downloaded
                                    else R.string.screen_config_embedded_model_select,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return if (gib >= 1.0) "%.1f GiB".format(gib) else "%.0f MiB".format(mib)
}

fun embeddedModelDownloadProgress(bytesDownloaded: Long, totalBytes: Long?): Float? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    return (bytesDownloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

fun embeddedModelDownloadProgressLabel(bytesDownloaded: Long, totalBytes: Long?): String {
    val downloaded = formatBytes(bytesDownloaded.coerceAtLeast(0L))
    val total = totalBytes?.takeIf { it > 0L } ?: return downloaded
    val percent = ((bytesDownloaded.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    return "$downloaded / ${formatBytes(total)} · $percent%"
}

private enum class LocalModelAcceleratorOption(
    val value: String,
    val labelRes: Int,
) {
    CPU("cpu", R.string.screen_config_local_model_accelerator_cpu),
    GPU("gpu", R.string.screen_config_local_model_accelerator_gpu),
    NPU("npu", R.string.screen_config_local_model_accelerator_npu),
}

@Composable
private fun HapticSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    Switch(
        checked = checked,
        enabled = enabled,
        modifier = modifier,
        onCheckedChange = { isChecked ->
            if (isChecked) {
                HapticEffects.toggleOn(haptic, view)
            } else {
                HapticEffects.toggleOff(haptic, view)
            }
            onCheckedChange(isChecked)
        },
    )
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
