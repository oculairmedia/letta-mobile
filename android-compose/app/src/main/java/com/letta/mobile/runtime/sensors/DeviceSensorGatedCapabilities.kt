package com.letta.mobile.runtime.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object DeviceSensorGatedCapabilityIds {
    const val LOCATION = "location.precise"
    const val WIFI_SSID = "network.wifi_ssid"
    const val ACTIVITY_RECOGNITION = "motion.activity_recognition"
    const val NOTIFICATION_NOW_PLAYING = "notifications.now_playing"
    const val BODY_SENSORS = "body.sensors"
}

enum class DeviceSensorGatedStatus {
    Disabled,
    PermissionRequired,
    NotGranted,
    Granted,
    Unavailable,
}

@kotlinx.serialization.Serializable
data class DeviceSensorGatedCapability(
    val id: String,
    val label: String,
    val status: DeviceSensorGatedStatus,
    val defaultEnabled: Boolean,
    val runtimePermissions: List<String> = emptyList(),
    val reason: String,
)

interface DeviceSensorGatedCapabilityProvider {
    fun listCapabilities(): List<DeviceSensorGatedCapability>
}

class AndroidDeviceSensorGatedCapabilityProvider(
    private val context: Context,
    private val optIn: DeviceSensorOptInState = DeviceSensorOptInState.Default,
) : DeviceSensorGatedCapabilityProvider {
    override fun listCapabilities(): List<DeviceSensorGatedCapability> = listOf(
        permissionBacked(
            id = DeviceSensorGatedCapabilityIds.LOCATION,
            label = "Precise location",
            optedIn = optIn.location,
            permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            reason = "Precise location is sensitive and requires explicit opt-in plus Android location permission.",
        ),
        permissionBacked(
            id = DeviceSensorGatedCapabilityIds.WIFI_SSID,
            label = "Wi‑Fi SSID/BSSID",
            optedIn = optIn.wifiSsid,
            permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            reason = "Android gates Wi‑Fi network identifiers behind location permission; disabled until explicit opt-in.",
        ),
        permissionBacked(
            id = DeviceSensorGatedCapabilityIds.ACTIVITY_RECOGNITION,
            label = "Activity recognition / steps",
            optedIn = optIn.activityRecognition,
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                listOf(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                emptyList()
            },
            reason = "Activity recognition can reveal movement patterns; disabled until explicit opt-in.",
        ),
        DeviceSensorGatedCapability(
            id = DeviceSensorGatedCapabilityIds.NOTIFICATION_NOW_PLAYING,
            label = "Now playing / notification listener",
            status = if (optIn.notificationNowPlaying) DeviceSensorGatedStatus.PermissionRequired else DeviceSensorGatedStatus.Disabled,
            defaultEnabled = false,
            reason = "Notification access is cross-app data and requires explicit opt-in via Android notification listener settings.",
        ),
        permissionBacked(
            id = DeviceSensorGatedCapabilityIds.BODY_SENSORS,
            label = "Body sensors",
            optedIn = optIn.bodySensors,
            permissions = listOf(Manifest.permission.BODY_SENSORS),
            reason = "Body sensors are health data; disabled until explicit opt-in plus Android body-sensors permission.",
            unavailableWhenNoFeature = PackageManager.FEATURE_SENSOR_HEART_RATE,
        ),
    )

    private fun permissionBacked(
        id: String,
        label: String,
        optedIn: Boolean,
        permissions: List<String>,
        reason: String,
        unavailableWhenNoFeature: String? = null,
    ): DeviceSensorGatedCapability {
        if (unavailableWhenNoFeature != null && !context.packageManager.hasSystemFeature(unavailableWhenNoFeature)) {
            return DeviceSensorGatedCapability(
                id = id,
                label = label,
                status = DeviceSensorGatedStatus.Unavailable,
                defaultEnabled = false,
                runtimePermissions = permissions,
                reason = "Device does not report required feature $unavailableWhenNoFeature.",
            )
        }
        if (!optedIn) {
            return DeviceSensorGatedCapability(
                id = id,
                label = label,
                status = DeviceSensorGatedStatus.Disabled,
                defaultEnabled = false,
                runtimePermissions = permissions,
                reason = reason,
            )
        }
        if (permissions.isEmpty()) {
            return DeviceSensorGatedCapability(id, label, DeviceSensorGatedStatus.Granted, false, permissions, reason)
        }
        val granted = permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        return DeviceSensorGatedCapability(
            id = id,
            label = label,
            status = if (granted) DeviceSensorGatedStatus.Granted else DeviceSensorGatedStatus.NotGranted,
            defaultEnabled = false,
            runtimePermissions = permissions,
            reason = if (granted) "Explicit opt-in enabled and Android permission is granted." else reason,
        )
    }
}

@kotlinx.serialization.Serializable
data class DeviceSensorOptInState(
    val location: Boolean = false,
    val wifiSsid: Boolean = false,
    val activityRecognition: Boolean = false,
    val notificationNowPlaying: Boolean = false,
    val bodySensors: Boolean = false,
) {
    companion object {
        val Default = DeviceSensorOptInState()
    }
}
