package com.letta.mobile.ui.screens.config

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import ca.oculair.meridian.BuildConfig
import ca.oculair.meridian.R
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.plugin.catalog.pluginsSettingsContributionOrNull
import com.letta.mobile.data.settings.SettingsAction
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.platform.BatteryOptimizationHelper
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.Telemetry

/*
 * letta-mobile-ckmub.3.12 (S10): which of Android's own sections the shared Settings page shows,
 * and the platform services behind them (battery optimization). No UI here.
 */

/** Where the Settings page's rows lead on Android. */
data class SettingsDestinations(
    val onConfigList: () -> Unit = {},
    val onSystemAccess: () -> Unit = {},
    val onCanvasDebug: () -> Unit = {},
    val onProviders: () -> Unit = {},
    /** Null while the host does not run plugins: the Plugins entry is hidden (letta-mobile-cdgpr.8). */
    val onPlugins: (() -> Unit)? = null,
)

/** Whether the app is exempt from battery optimization, and how to ask for the exemption. */
internal class BatteryOptimizationState(val exempt: Boolean, val requestExemption: () -> Unit)

/** What Android's sections are built from besides the form. */
internal class AndroidSettingsInputs(
    val viewModel: ConfigViewModel,
    val destinations: SettingsDestinations,
    val battery: BatteryOptimizationState,
    val onImportModel: () -> Unit,
    val onSaveModel: () -> Unit,
)

/** Android's own sections for [state]: nothing while loading, a retry when the load failed. */
internal fun androidSettingsContributions(state: UiState<ConfigUiState>, inputs: AndroidSettingsInputs): List<SettingsContribution> {
    val form = when (state) {
        is UiState.Success -> state.data
        is UiState.Error -> return listOf(SettingsContribution(SettingsGroup.Backend, loadFailedSection(state.message, inputs.viewModel::loadConfig)))
        UiState.Loading -> return emptyList()
    }
    val vm = inputs.viewModel
    val destinations = inputs.destinations
    val debug = if (BuildConfig.DEBUG) DeveloperDestinations(destinations.onCanvasDebug) else null
    return listOfNotNull(
        form.refreshError?.let { SettingsContribution(SettingsGroup.Backend, loadFailedSection(it, vm::loadConfig)) },
        SettingsContribution(
            SettingsGroup.Backend,
            onDeviceModelSection(form, OnDeviceModelActions(vm, inputs.onImportModel, inputs.onSaveModel)),
            showForMode = LettaConfig.Mode.LOCAL,
        ),
        SettingsContribution(SettingsGroup.Backend, savedBackendsSection(destinations.onConfigList)),
        SettingsContribution(SettingsGroup.Notifications, backgroundDeliverySection(inputs.battery.exempt, inputs.battery.requestExemption)),
        SettingsContribution(SettingsGroup.Providers, systemAccessSection(destinations.onSystemAccess)),
        pluginsSettingsContributionOrNull(destinations.onPlugins),
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

/** Asks Android to exempt the app from battery optimization, falling back to the settings list. */
internal fun requestBatteryOptimizationExemption(
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
