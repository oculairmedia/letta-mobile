package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.desktopshell.ShellLayoutStore
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.storage.SecureSettingsStore

/**
 * The shell's sidebar layout as Compose state (letta-mobile-o5m90, moved from :desktop by
 * letta-mobile-ckmub.17.4): the shared [ShellLayoutReducer]'s rules over the shared [ShellLayoutStore],
 * so the desktop window and the browser keep the same sidebar the same way.
 */
class ShellLayoutController(private val store: ShellLayoutStore) {
    var state by mutableStateOf(store.load())
        private set

    fun dispatch(event: ShellLayoutEvent) {
        val previous = state
        state = ShellLayoutReducer.reduce(previous, event)
        store.record(previous, state)
    }
}

/** The layout store for [backend] in [preferences]: one record per backend config. */
fun shellLayoutStore(backend: LettaConfig, preferences: SecureSettingsStore): ShellLayoutStore =
    ShellLayoutStore(preferences, key = "shell.${backend.id}.layout")

/** The [ShellLayoutController] for [backend], kept while the backend config is the same. */
@Composable
fun rememberShellLayoutController(backend: LettaConfig, preferences: SecureSettingsStore): ShellLayoutController =
    remember(backend.id, preferences) { ShellLayoutController(shellLayoutStore(backend, preferences)) }
