package com.letta.mobile.ui.screens.config

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.BuildConfig
import ca.oculair.meridian.R
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.preview.LettaPreviewFrame
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.ui.theme.sectionTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onNavigateBack: () -> Unit,
    onNavigateToConfigList: () -> Unit,
    destinations: ConfigDestinations = ConfigDestinations(),
    viewModel: ConfigViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current
    val batteryOptimization = rememberBatteryOptimizationState(context)
    val localModelImportLauncher = rememberLocalModelImportLauncher(viewModel, context)
    val callbacks = rememberConfigContentCallbacks(
        viewModel = viewModel,
        onImportLocalModel = {
            localModelImportLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        },
        onSave = {
            viewModel.saveConfig(
                onSuccess = { snackbar.dispatch("Configuration saved"); onNavigateBack() },
                onError = snackbar::dispatch,
            )
        },
    )

    Scaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { ConfigTopAppBar(onNavigateBack, onNavigateToConfigList) }
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(
                modifier = Modifier.padding(paddingValues).padding(LettaDimens.Space.lg),
            )
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadConfig() },
                modifier = Modifier.padding(paddingValues)
            )
            is UiState.Success -> ConfigContent(
                state = state.data,
                callbacks = callbacks,
                host = ConfigContentHost(batteryOptimization, destinations),
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

/** Everything the settings content can ask of its owner, grouped by the card that asks. */
@Stable
private class ConfigContentCallbacks(
    val server: ServerSettingsCallbacks,
    val localModel: LocalModelCallbacks,
    val embeddedModel: EmbeddedModelCallbacks,
    val appearance: AppearanceCallbacks,
    val features: FeatureToggleCallbacks,
    val onRefresh: () -> Unit,
    val onSave: () -> Unit,
)

/** What the hosting screen supplies beside the view model: battery status and navigation. */
private class ConfigContentHost(
    val batteryOptimization: BatteryOptimizationState,
    val destinations: ConfigDestinations,
)

@Composable
private fun rememberConfigContentCallbacks(
    viewModel: ConfigViewModel,
    onImportLocalModel: () -> Unit,
    onSave: () -> Unit,
): ConfigContentCallbacks {
    val currentOnImportLocalModel by rememberUpdatedState(onImportLocalModel)
    val currentOnSave by rememberUpdatedState(onSave)
    return remember(viewModel) {
        ConfigContentCallbacks(
            server = ServerSettingsCallbacks(
                onModeChange = { viewModel.updateMode(it) },
                onServerUrlChange = { viewModel.updateServerUrl(it) },
                onApiTokenChange = { viewModel.updateApiToken(it) },
            ),
            localModel = localModelCallbacks(viewModel),
            embeddedModel = EmbeddedModelCallbacks(
                onImportLocalModel = { currentOnImportLocalModel() },
                onDownloadEmbeddedModel = { viewModel.downloadEmbeddedModel(it) },
                onCancelEmbeddedModelDownload = { viewModel.cancelEmbeddedModelDownload(it) },
                onSelectEmbeddedModel = { viewModel.selectEmbeddedModel(it) },
            ),
            appearance = AppearanceCallbacks(
                onThemeChange = { viewModel.updateTheme(it) },
                onThemePresetChange = { viewModel.updateThemePreset(it) },
                onDynamicColorChange = { viewModel.updateDynamicColor(it) },
            ),
            features = FeatureToggleCallbacks(
                onEnableProjectsChange = { viewModel.updateEnableProjects(it) },
                onHapticsEnabledChange = { viewModel.updateHapticsEnabled(it) },
                onSharedChatPageEnabledChange = { viewModel.updateSharedChatPageEnabled(it) },
                onOpenChatsOnCanvasChange = { viewModel.updateOpenChatsOnCanvas(it) },
            ),
            onRefresh = viewModel::loadConfig,
            onSave = { currentOnSave() },
        )
    }
}

private fun localModelCallbacks(viewModel: ConfigViewModel): LocalModelCallbacks {
    return LocalModelCallbacks(
        onLocalModelPathChange = { viewModel.updateLocalModelPath(it) },
        onLocalModelHandleChange = { viewModel.updateLocalModelHandle(it) },
        onLocalModelAcceleratorChange = { viewModel.updateLocalModelAccelerator(it) },
        onLocalModelMaxTokensChange = { viewModel.updateLocalModelMaxTokens(it) },
        onLocalProviderBaseUrlChange = { viewModel.updateLocalProviderBaseUrl(it) },
        onLocalProviderApiKeyChange = { viewModel.updateLocalProviderApiKey(it) },
        onLocalProviderModelChange = { viewModel.updateLocalProviderModel(it) },
        onHuggingFaceTokenChange = { viewModel.updateHuggingFaceToken(it) },
    )
}

@Composable
private fun ConfigContent(
    state: ConfigUiState,
    callbacks: ConfigContentCallbacks,
    host: ConfigContentHost,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)
    ) {
        ConfigRefreshStatus(
            isRefreshing = state.isRefreshing,
            error = state.refreshError,
            onRetry = callbacks.onRefresh,
        )
        ServerSection(
            state = state,
            server = callbacks.server,
            localModel = callbacks.localModel,
            embeddedModel = callbacks.embeddedModel,
        )
        AppearanceSection(state = state, callbacks = callbacks.appearance)
        FeaturesSection(state = state, callbacks = callbacks.features)
        BackgroundDeliverySection(batteryOptimization = host.batteryOptimization)
        IntegrationsSection(host.destinations)
        SaveSection(isSaving = state.isSaving, onSave = callbacks.onSave)
    }
}

@Composable
private fun SaveSection(
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    CardGroup {
        item(
            onClick = if (isSaving) null else onSave,
            headlineContent = { Text(stringResource(R.string.action_save_configuration)) },
            leadingContent = { Icon(LettaIcons.Save, contentDescription = null) },
            trailingContent = {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(LettaDimens.Orb.sm))
                }
            },
        )
    }
}

@Composable
private fun ConfigRefreshStatus(
    isRefreshing: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    when {
        isRefreshing -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LettaDimens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(LettaDimens.Control.icon),
                strokeWidth = LettaDimens.Space.hair,
            )
            Text(
                text = stringResource(R.string.screen_config_refreshing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        error != null -> CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.screen_config_refresh_failed))
                },
                supportingContent = { Text(error) },
                leadingContent = {
                    Icon(
                        imageVector = LettaIcons.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                trailingContent = {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                },
            )
        }
    }
}

@Composable
internal fun ConfigSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.sectionTitle,
    )
}

// region Previews

@PreviewLightDark
@Composable
private fun ConfigRefreshStatusErrorPreview() {
    LettaPreviewFrame {
        ConfigRefreshStatus(
            isRefreshing = false,
            error = "Failed to refresh configuration",
            onRetry = {},
        )
    }
}

// endregion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTopAppBar(
    onNavigateBack: () -> Unit,
    onNavigateToConfigList: () -> Unit,
) {
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

@Composable
private fun rememberLocalModelImportLauncher(
    viewModel: ConfigViewModel,
    context: Context,
): ActivityResultLauncher<Array<String>> {
    val snackbar = LocalSnackbarDispatcher.current
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalModel(
                uri = uri,
                onSuccess = { fileName ->
                    snackbar.dispatch(context.getString(R.string.screen_config_local_model_import_success, fileName))
                },
                onError = snackbar::dispatch,
            )
        }
    }
}

/**
 * The integrations card, plus the debug-only entries that sit under it. Each new debug surface
 * lands here rather than deeper inside [ConfigContent], which is what kept growing every time one
 * was added.
 */
@Composable
private fun IntegrationsSection(destinations: ConfigDestinations) {
    CardGroup(title = {
        ConfigSectionTitle(stringResource(R.string.screen_config_integrations_section))
    }) {
        item(
            onClick = destinations.onProviders,
            headlineContent = { Text(stringResource(R.string.screen_providers_title)) },
            supportingContent = { Text(stringResource(R.string.screen_providers_entry_description)) },
            leadingContent = { Icon(LettaIcons.Server, contentDescription = null) },
        )
        item(
            onClick = destinations.onSystemAccess,
            headlineContent = { Text(stringResource(R.string.screen_system_access_title)) },
            supportingContent = { Text(stringResource(R.string.screen_system_access_entry_description)) },
            leadingContent = { Icon(LettaIcons.Key, contentDescription = null) },
        )
        if (BuildConfig.DEBUG) {
            item(
                onClick = destinations.onVibesyncDebug,
                headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_title)) },
                supportingContent = { Text(stringResource(R.string.screen_vibesync_debug_entry_description)) },
                leadingContent = { Icon(LettaIcons.Database, contentDescription = null) },
            )
            item(
                onClick = destinations.onCanvasDebug,
                headlineContent = { Text(stringResource(R.string.screen_canvas_debug_title)) },
                supportingContent = { Text(stringResource(R.string.screen_canvas_debug_entry_description)) },
                leadingContent = { Icon(LettaIcons.Edit, contentDescription = null) },
            )
        }
    }
}
