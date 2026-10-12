package com.letta.mobile.ui.screens.config

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.BuildConfig
import ca.oculair.meridian.R
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.settings.BackendConnectionTester
import com.letta.mobile.data.settings.NotificationSettings
import com.letta.mobile.data.settings.SettingsAction
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.platform.BatteryOptimizationHelper
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.notify.LocalAttentionPrefs
import com.letta.mobile.ui.settings.BackendSettingsActions
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.ui.settings.SettingsPageState
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.shellSettingsPage
import com.letta.mobile.util.Telemetry

/** Where the Settings page's rows lead on Android. */
data class SettingsDestinations(
    val onConfigList: () -> Unit = {},
    val onSystemAccess: () -> Unit = {},
    val onVibesyncDebug: () -> Unit = {},
    val onCanvasDebug: () -> Unit = {},
    val onProviders: () -> Unit = {},
)

/**
 * Container (letta-mobile-ckmub.3.12, hard cut S10): Android's Settings route is the shared
 * SettingsPage drawn by ShellPage's Settings destination. This only binds the settings view model,
 * the model-file picker, battery optimization and navigation to it; the page owns its insets (edge
 * to edge) and draws every row. Replaces ConfigScreen and its section composables.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    destinations: SettingsDestinations = SettingsDestinations(),
    viewModel: ConfigViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config by viewModel.backendConfig.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current
    val battery = rememberBatteryOptimizationState(context)
    val importModel = rememberLocalModelImportLauncher(viewModel, context)
    val services = SettingsPageServices(
        backend = BackendSettingsActions(
            onConfigSaved = { saved ->
                viewModel.saveBackend(
                    saved,
                    onSuccess = {
                        snackbar.dispatch(CONFIG_SAVED)
                        onNavigateBack()
                    },
                    onError = snackbar::dispatch,
                )
            },
            onTokenCleared = { viewModel.clearToken(snackbar::dispatch) },
            testConnection = BackendConnectionTester(viewModel::testConnection),
        ),
        appearance = viewModel.appearance,
        chat = viewModel.chat,
        notifications = NotificationSettings(LocalAttentionPrefs.current),
        onOpenProviders = destinations.onProviders,
        contributions = androidContributions(
            state = uiState,
            inputs = ContributionInputs(viewModel, destinations, battery) {
                importModel.launch(arrayOf("application/octet-stream", "*/*"))
            },
            onSaveModel = { viewModel.saveConfig(onSuccess = { snackbar.dispatch(CONFIG_SAVED) }, onError = snackbar::dispatch) },
        ),
    )
    ShellPage(
        destination = ShellDestination.Settings(),
        deps = ShellPageDeps(settings = shellSettingsPage(SettingsPageState(config), services, onBack = onNavigateBack)),
    )
}

private class ContributionInputs(
    val viewModel: ConfigViewModel,
    val destinations: SettingsDestinations,
    val battery: BatteryOptimizationState,
    val onImportModel: () -> Unit,
)

private fun androidContributions(
    state: UiState<ConfigUiState>,
    inputs: ContributionInputs,
    onSaveModel: () -> Unit,
): List<SettingsContribution> {
    val form = when (state) {
        is UiState.Success -> state.data
        is UiState.Error -> return listOf(SettingsContribution(SettingsGroup.Backend, loadFailedSection(state.message, inputs.viewModel::loadConfig)))
        UiState.Loading -> return emptyList()
    }
    val vm = inputs.viewModel
    val destinations = inputs.destinations
    val debug = if (BuildConfig.DEBUG) DeveloperDestinations(destinations.onVibesyncDebug, destinations.onCanvasDebug) else null
    return listOfNotNull(
        form.refreshError?.let { SettingsContribution(SettingsGroup.Backend, loadFailedSection(it, vm::loadConfig)) },
        SettingsContribution(
            SettingsGroup.Backend,
            onDeviceModelSection(form, OnDeviceModelActions(vm, inputs.onImportModel, onSaveModel)),
            showForMode = LettaConfig.Mode.LOCAL,
        ),
        SettingsContribution(SettingsGroup.Backend, savedBackendsSection(destinations.onConfigList)),
        SettingsContribution(SettingsGroup.Notifications, backgroundDeliverySection(inputs.battery.exempt, inputs.battery.requestExemption)),
        SettingsContribution(SettingsGroup.Providers, systemAccessSection(destinations.onSystemAccess)),
        SettingsContribution(SettingsGroup.About, androidAboutSection(BuildConfig.VERSION_NAME, form.embeddedRuntimeStatus)),
        SettingsContribution(SettingsGroup.Developer, developerSection(form, vm, debug)),
    )
}

private fun loadFailedSection(message: String, onRetry: () -> Unit) = SettingsSectionModel(
    key = "android-load-failed",
    title = "Settings did not load",
    rows = listOf(
        SettingsRow.Note("load-failed", SettingsStatus(message, SettingsTone.Error)),
        SettingsRow.Actions("load-failed-actions", listOf(SettingsAction("load-retry", "Retry") { onRetry() })),
    ),
)

@Composable
private fun rememberLocalModelImportLauncher(viewModel: ConfigViewModel, context: Context): ActivityResultLauncher<Array<String>> {
    val snackbar = LocalSnackbarDispatcher.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importLocalModel(
                uri = uri,
                onSuccess = { fileName -> snackbar.dispatch(context.getString(R.string.screen_config_local_model_import_success, fileName)) },
                onError = snackbar::dispatch,
            )
        }
    }
}

/** Whether the app is exempt from battery optimization, and how to ask for the exemption. */
internal class BatteryOptimizationState(val exempt: Boolean, val requestExemption: () -> Unit)

/** Battery optimization, re-read whenever the app resumes (the system dialog returns to it). */
@Composable
private fun rememberBatteryOptimizationState(context: Context): BatteryOptimizationState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = LocalSnackbarDispatcher.current
    var exempt by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }
    fun refresh(source: String) {
        exempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        Telemetry.event("BatteryOptimization", "status", "source" to source, "exempt" to exempt)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh("requestReturned") }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh("resume") }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return BatteryOptimizationState(exempt) {
        requestBatteryOptimizationExemption(context, launcher, exempt) { snackbar.dispatch(it) }
    }
}

private fun requestBatteryOptimizationExemption(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
    exempt: Boolean,
    onFailure: (String) -> Unit,
) {
    Telemetry.event("BatteryOptimization", "requestTapped", "exemptBefore" to exempt)
    try {
        launcher.launch(BatteryOptimizationHelper.requestExemptionIntent(context))
        Telemetry.event("BatteryOptimization", "requestLaunched", "target" to "requestExemption")
    } catch (primaryError: ActivityNotFoundException) {
        try {
            launcher.launch(BatteryOptimizationHelper.batteryOptimizationSettingsIntent())
            Telemetry.event("BatteryOptimization", "requestLaunched", "target" to "settingsFallback")
        } catch (fallbackError: ActivityNotFoundException) {
            Telemetry.error("BatteryOptimization", "requestFailed", fallbackError, "primaryError" to primaryError.javaClass.simpleName)
            onFailure(context.getString(R.string.screen_config_battery_optimization_request_failed))
        }
    }
}

private const val CONFIG_SAVED = "Configuration saved"
