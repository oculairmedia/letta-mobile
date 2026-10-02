package com.letta.mobile.ui.screens.config

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ca.oculair.meridian.R
import com.letta.mobile.platform.BatteryOptimizationHelper
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.util.Telemetry

// The settings screen's background-delivery card and the battery-optimization state behind it.

@Composable
internal fun BackgroundDeliverySection(batteryOptimization: BatteryOptimizationState) {
    val exempt = batteryOptimization.exempt
    CardGroup(title = {
        ConfigSectionTitle(stringResource(R.string.screen_config_background_delivery_section))
    }) {
        item(
            headlineContent = { Text(stringResource(R.string.screen_config_reliable_background_delivery)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (exempt) {
                            R.string.screen_config_battery_optimization_exempt_description
                        } else {
                            R.string.screen_config_battery_optimization_restricted_description
                        }
                    )
                )
            },
            leadingContent = { Icon(LettaIcons.Settings, contentDescription = null) },
            trailingContent = {
                if (exempt) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.screen_config_battery_optimization_status_unrestricted)) },
                        leadingIcon = { Icon(LettaIcons.CheckCircle, contentDescription = null) },
                    )
                } else {
                    TextButton(onClick = batteryOptimization.requestExemption) {
                        Text(stringResource(R.string.screen_config_battery_optimization_allow_action))
                    }
                }
            },
        )
    }
}

internal class BatteryOptimizationState(
    val exempt: Boolean,
    val requestExemption: () -> Unit,
)

@Composable
internal fun rememberBatteryOptimizationState(
    context: Context,
): BatteryOptimizationState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = LocalSnackbarDispatcher.current
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

    return BatteryOptimizationState(
        exempt = batteryOptimizationExempt,
        requestExemption = {
            requestBatteryOptimizationExemption(
                context = context,
                launcher = batteryOptimizationLauncher,
                exempt = batteryOptimizationExempt,
                onFailure = snackbar::dispatch,
            )
        }
    )
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
            Telemetry.error(
                "BatteryOptimization",
                "requestFailed",
                fallbackError,
                "primaryError" to primaryError.javaClass.simpleName,
            )
            onFailure(context.getString(R.string.screen_config_battery_optimization_request_failed))
        }
    }
}
