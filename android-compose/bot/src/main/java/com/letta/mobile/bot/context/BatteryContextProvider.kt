package com.letta.mobile.bot.context

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides battery status as device context for the bot envelope.
 * No special permissions required.
 */
@Singleton
class BatteryContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceContextProvider {

    override val providerId: String = "battery"
    override val displayName: String = "Battery Status"
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun hasPermission(): Boolean = true

    override suspend fun gatherContext(): String? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (scale > 0) (level * 100) / scale else -1

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val charging = plugged != 0

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }

        return "Battery: ${percentage}% ($statusText${if (charging) ", plugged in" else ""})"
    }
}
