package com.letta.mobile.data.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.letta.mobile.data.controller.DeviceConditionsSyncBudget
import com.letta.mobile.data.controller.DeviceSyncConditions
import com.letta.mobile.data.controller.SyncBudgetPolicy

/**
 * letta-mobile-by4vm.50: the phone's cadence-sync budget. Periodic `sync`s run only on an
 * unmetered network while the phone is charging or its screen is on (the rule itself is
 * [DeviceConditionsSyncBudget]); gap-driven and foreground resyncs are never budgeted.
 *
 * Reads the system services on each check, so a phone that leaves Wi-Fi or is unplugged with its
 * screen off stops cadence syncs on the next tick. A service that is missing or throws counts as
 * the conservative answer (metered, not charging, screen off).
 */
class AndroidSyncBudgetPolicy(context: Context) : SyncBudgetPolicy {
    private val appContext = context.applicationContext
    private val rule = DeviceConditionsSyncBudget(::readConditions)

    override fun allowsPeriodicSync(): Boolean = rule.allowsPeriodicSync()

    private fun readConditions() = DeviceSyncConditions(
        unmeteredNetwork = unmeteredNetwork(),
        charging = charging(),
        screenOn = screenOn(),
    )

    private fun unmeteredNetwork(): Boolean = runCatching {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    private fun charging(): Boolean = runCatching {
        appContext.getSystemService(BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)

    private fun screenOn(): Boolean = runCatching {
        appContext.getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(false)
}
