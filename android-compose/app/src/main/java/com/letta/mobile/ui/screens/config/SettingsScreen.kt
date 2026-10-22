package com.letta.mobile.ui.screens.config

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import ca.oculair.meridian.R
import com.letta.mobile.data.settings.NotificationSettings
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.platform.BatteryOptimizationHelper
import com.letta.mobile.runtime.local.RotatingLineFile
import com.letta.mobile.runtime.local.runtimeLifecycleLogFile
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.notify.LocalAttentionPrefs
import com.letta.mobile.ui.screens.pairing.PairingViewModel
import com.letta.mobile.ui.settings.BackendSettingsActions
import com.letta.mobile.ui.settings.SavedBackends
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.ui.settings.SettingsPageState
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.shellSettingsPage
import com.letta.mobile.util.Telemetry

/**
 * The workspace's Settings page (letta-mobile-ckmub.3.12, ckmub.2): the shared SettingsPage, bound to the
 * settings view model, the model-file picker, battery optimization and Android's own screens. The page
 * owns its insets and draws every row; a saved backend closes it ([onBack]).
 */
internal fun androidSettingsPage(onBack: () -> Unit, destinations: SettingsDestinations) =
    ShellPageContent<ShellDestination.Settings> { destination, modifier ->
    val viewModel: ConfigViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config by viewModel.backendConfig.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current
    val pairing: PairingViewModel = hiltViewModel()
    val importModel = rememberLocalModelImportLauncher(viewModel, context)
    val inputs = AndroidSettingsInputs(
        viewModel = viewModel,
        destinations = destinations,
        battery = rememberBatteryOptimizationState(context),
        onImportModel = { importModel.launch(arrayOf("application/octet-stream", "*/*")) },
        onSaveModel = { viewModel.saveConfig(onSuccess = { snackbar.dispatch(CONFIG_SAVED) }, onError = snackbar::dispatch) },
        onCopyRuntimeLog = { snackbar.dispatch(copyRuntimeLifecycleLog(context)) },
    )
    val services = SettingsPageServices(
        backend = BackendSettingsActions(
            onConfigSaved = { saved ->
                viewModel.saveBackend(saved, onSuccess = { snackbar.dispatch(CONFIG_SAVED); onBack() }, onError = snackbar::dispatch)
            },
            onTokenCleared = { viewModel.clearToken(snackbar::dispatch) },
            testConnection = viewModel.connectionTester,
            onScanToConnect = destinations.onScanToConnect,
        ),
        pairing = pairing.installAndPair(context, pairing.agents.collectAsStateWithLifecycle().value, onOpenChat = onBack),
        appearance = viewModel.appearance,
        chat = viewModel.chat,
        notifications = NotificationSettings(LocalAttentionPrefs.current),
        onOpenProviders = destinations.onProviders,
        contributions = androidSettingsContributions(uiState, inputs),
    )
    val backends by viewModel.backends.collectAsStateWithLifecycle()
    val saved = SavedBackends(
        configs = backends, activeId = config.id,
        onSwitch = viewModel::switchBackend,
        onAdd = { added -> viewModel.addBackend(added, onSaved = { snackbar.dispatch(CONFIG_SAVED) }, onError = snackbar::dispatch) },
        onRemove = viewModel::removeBackend,
    )
    shellSettingsPage(SettingsPageState(config, saved = saved), services, onBack = onBack).Content(destination, modifier)
}

/** Debug builds: puts the on-device runtime's lifecycle log on the clipboard; returns what to tell the user. */
private fun copyRuntimeLifecycleLog(context: Context): String {
    val text = RotatingLineFile(runtimeLifecycleLogFile(context)).read()
    if (text.isBlank()) return "The runtime log is empty"
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return "No clipboard"
    clipboard.setPrimaryClip(ClipData.newPlainText("letta runtime log", text))
    return "Runtime log copied"
}

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

private const val CONFIG_SAVED = "Configuration saved"
