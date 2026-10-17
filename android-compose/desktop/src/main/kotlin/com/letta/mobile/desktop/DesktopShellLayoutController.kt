package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.desktopshell.ShellLayoutStore
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.data.DesktopPreferences

/**
 * Desktop binding for [com.letta.mobile.data.desktopshell.ShellLayoutState] (letta-mobile-o5m90):
 * Compose-observable state over the shared [ShellLayoutReducer] and [ShellLayoutStore]. Rendering
 * wiring only; the rules and the persistence live in sharedLogic.
 */
internal class DesktopShellLayoutController(private val store: ShellLayoutStore) {
    var state by mutableStateOf(store.load())
        private set

    fun dispatch(event: ShellLayoutEvent) {
        val previous = state
        state = ShellLayoutReducer.reduce(previous, event)
        store.record(previous, state)
    }
}

/** The layout store for [backendConfigId]: one record per backend config. */
internal fun desktopShellLayoutStore(
    backendConfigId: String,
    preferences: SecureSettingsStore = DesktopPreferences.store,
): ShellLayoutStore = ShellLayoutStore(preferences, key = "shell.$backendConfigId.layout")

@Composable
internal fun rememberDesktopShellLayoutController(backendConfigId: String): DesktopShellLayoutController =
    remember(backendConfigId) { DesktopShellLayoutController(desktopShellLayoutStore(backendConfigId)) }
