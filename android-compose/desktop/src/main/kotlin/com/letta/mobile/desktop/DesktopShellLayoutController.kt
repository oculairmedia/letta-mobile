package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.desktopshell.ShellLayoutReducer
import com.letta.mobile.data.desktopshell.ShellLayoutState
import com.letta.mobile.desktop.data.DesktopShellLayoutStore
import com.letta.mobile.desktop.data.PersistedShellLayout

/**
 * Desktop binding for [ShellLayoutState] (letta-mobile-o5m90): owns the
 * Compose-observable state, loads/saves it through [DesktopShellLayoutStore]
 * fenced by [backendConfigId], and forwards every mutation through
 * [ShellLayoutReducer]. All decision logic (breakpoints, width clamping,
 * explicit-vs-derived preference tracking) lives in sharedLogic — this class
 * is rendering/persistence wiring only, per the cardinal rule.
 */
internal class DesktopShellLayoutController(
    private val backendConfigId: String,
    private val store: DesktopShellLayoutStore,
) {
    var state by mutableStateOf(loadInitial())
        private set

    fun dispatch(event: ShellLayoutEvent) {
        val next = ShellLayoutReducer.reduce(state, event)
        state = next
        persistIfExplicit(next)
    }

    private fun loadInitial(): ShellLayoutState {
        val persisted = store.load(backendConfigId) ?: return ShellLayoutState()
        // A persisted record only ever exists because the user made an
        // explicit choice (see persistIfExplicit) — restoring it must not
        // let a later narrow-window default silently override it.
        return ShellLayoutState(
            collapsedPreference = persisted.collapsedPreference,
            hasExplicitPreference = true,
            sidebarWidthDp = persisted.sidebarWidthDp ?: ShellLayoutReducer.DEFAULT_SIDEBAR_WIDTH_DP,
        )
    }

    private fun persistIfExplicit(state: ShellLayoutState) {
        // Narrow-width auto-collapse is derived, not a user decision — never
        // persist it, or reopening the app on a wide window would wrongly
        // start collapsed from a transient narrow session.
        if (!state.hasExplicitPreference) return
        store.save(
            backendConfigId,
            PersistedShellLayout(
                collapsedPreference = state.collapsedPreference,
                sidebarWidthDp = state.sidebarWidthDp,
            ),
        )
    }
}

@Composable
internal fun rememberDesktopShellLayoutController(
    backendConfigId: String,
    store: DesktopShellLayoutStore,
): DesktopShellLayoutController =
    remember(backendConfigId, store) { DesktopShellLayoutController(backendConfigId, store) }
