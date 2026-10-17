package com.letta.mobile.data.desktopshell

import com.letta.mobile.data.storage.JsonPreference
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where [ShellLayoutState] is kept between launches (letta-mobile-o5m90): the person's explicit
 * sidebar choice and width, nothing derived from the window. The host gives it a [key] per backend
 * config, so switching backends never restores another backend's sidebar.
 */
class ShellLayoutStore(store: SecureSettingsStore, key: String) {
    private val preference = JsonPreference(store, key, PersistedShellLayout.serializer())

    /** The stored choice as layout state, or the width-derived default when none was recorded. */
    fun load(): ShellLayoutState {
        val persisted = preference.load() ?: return ShellLayoutState()
        // A record only exists because the person chose: a later narrow window must not override it.
        return ShellLayoutState(
            collapsedPreference = persisted.collapsed,
            hasExplicitPreference = true,
            sidebarWidthDp = persisted.sidebarWidthDp ?: ShellLayoutReducer.DEFAULT_SIDEBAR_WIDTH_DP,
        )
    }

    /** Persists [next] when it carries an explicit choice that differs from [previous]. */
    fun record(previous: ShellLayoutState, next: ShellLayoutState) {
        if (shouldPersist(previous, next)) {
            preference.save(PersistedShellLayout(collapsed = next.collapsedPreference, sidebarWidthDp = next.sidebarWidthDp))
        }
    }

    companion object {
        /** Window-width changes alone never persist; only the person's own choices do. */
        fun shouldPersist(previous: ShellLayoutState, next: ShellLayoutState): Boolean =
            next.hasExplicitPreference && (
                !previous.hasExplicitPreference ||
                    previous.collapsedPreference != next.collapsedPreference ||
                    previous.sidebarWidthDp != next.sidebarWidthDp
                )
    }
}

@Serializable
private data class PersistedShellLayout(
    @SerialName("collapsed") val collapsed: Boolean,
    @SerialName("sidebar_width_dp") val sidebarWidthDp: Float? = null,
)
