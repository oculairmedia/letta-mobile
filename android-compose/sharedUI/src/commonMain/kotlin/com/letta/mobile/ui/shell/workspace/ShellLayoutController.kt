package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.desktopshell.ShellLayoutStore
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.storage.SecureSettingsStore

/**
 * The shell's layout for one backend config: the sidebar as Compose state (the shared
 * [ShellLayoutReducer]'s rules over the shared [ShellLayoutStore], so every host keeps the same
 * sidebar the same way) and what the shell's lanes hold ([lanes], the stored layout config).
 */
class ShellLayoutController(private val store: ShellLayoutStore, val lanes: ShellLayoutConfigStore) {
    var state by mutableStateOf(store.load())
        private set

    fun dispatch(event: ShellLayoutEvent) {
        val previous = state
        state = ShellLayoutReducer.reduce(previous, event)
        store.record(previous, state)
    }

    /**
     * The workspace's layout as this controller now has it: the stored lanes, collected so a saved
     * layout redraws the shell at once, and the person's sidebar choice.
     */
    @Composable
    fun workspaceLayout(reducedMotion: Boolean = false, forceCompact: Boolean = false): ShellWorkspaceLayout =
        ShellWorkspaceLayout(lanes.config.collectAsState().value, state.isSidebarVisible, reducedMotion, forceCompact)
}

/** The layout store for [backend] in [preferences]: one record per backend config. */
fun shellLayoutStore(backend: LettaConfig?, preferences: SecureSettingsStore): ShellLayoutStore =
    ShellLayoutStore(preferences, key = "shell.${backend?.id.orEmpty()}.layout")

/**
 * The [ShellLayoutController] for [backend] (null before one is configured), kept while the backend
 * config is the same: its sidebar and its lanes are both that backend's.
 */
@Composable
fun rememberShellLayoutController(backend: LettaConfig?, preferences: SecureSettingsStore): ShellLayoutController =
    remember(backend?.id, preferences) {
        ShellLayoutController(shellLayoutStore(backend, preferences), ShellLayoutConfigStore.forBackend(preferences, backend?.id))
    }
