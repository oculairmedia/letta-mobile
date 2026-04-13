package com.letta.mobile.bot.context

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides network connectivity status as device context for the bot envelope.
 * No special permissions required beyond INTERNET (already declared).
 */
@Singleton
class ConnectivityContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceContextProvider {

    override val providerId: String = "connectivity"
    override val displayName: String = "Network Status"
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun hasPermission(): Boolean = true

    override suspend fun gatherContext(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "Network: unknown"

        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
            ?: return "Network: disconnected"

        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }

        val metered = if (cm.isActiveNetworkMetered) "metered" else "unmetered"
        return "Network: connected ($type, $metered)"
    }
}
