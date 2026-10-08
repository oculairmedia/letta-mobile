package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.data.storage.SecureSettingsStore

internal const val RAIL_RECENCY_DAYS_KEY = "desktop.rail.recencyDays"
internal val RAIL_RECENCY_DAYS_DEFAULT: Int = RecentAgentsPolicy.DEFAULT_WINDOW.inWholeDays.toInt()

/** The rail-strip preferences, persisted in the settings store. `recencyDays == 0` means every agent. */
internal class DesktopRailPrefs(private val store: SecureSettingsStore) {
    var recencyDays: Int by mutableStateOf(
        store.getString(RAIL_RECENCY_DAYS_KEY)?.toIntOrNull()?.coerceAtLeast(0) ?: RAIL_RECENCY_DAYS_DEFAULT,
    )
        private set

    fun updateRecencyDays(days: Int) {
        recencyDays = days.coerceAtLeast(0)
        store.putString(RAIL_RECENCY_DAYS_KEY, recencyDays.toString())
    }

    /** The policy [recentRailAgents] should apply; "every agent" is a window nothing falls outside. */
    val recencyPolicy: RecentAgentsPolicy
        get() = RecentAgentsPolicy.forDays(recencyDays)
}

@Composable
internal fun rememberDesktopRailPrefs(store: SecureSettingsStore): DesktopRailPrefs =
    remember(store) { DesktopRailPrefs(store) }
