package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.storage.SecureSettingsStore
import java.time.Duration

internal const val RAIL_RECENCY_DAYS_KEY = "desktop.rail.recencyDays"
internal const val RAIL_RECENCY_DAYS_DEFAULT = 7

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

    /** The window [recentRailAgents] should apply; "every agent" is a window nothing falls outside. */
    val recencyWindow: Duration
        get() = if (recencyDays == 0) Duration.ofDays(EVERY_AGENT_DAYS) else Duration.ofDays(recencyDays.toLong())

    private companion object {
        const val EVERY_AGENT_DAYS = 36_500L
    }
}

@Composable
internal fun rememberDesktopRailPrefs(store: SecureSettingsStore): DesktopRailPrefs =
    remember(store) { DesktopRailPrefs(store) }
