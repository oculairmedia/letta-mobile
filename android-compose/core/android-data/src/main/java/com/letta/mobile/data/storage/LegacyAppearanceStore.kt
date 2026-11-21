package com.letta.mobile.data.storage

import com.letta.mobile.data.shellstate.AppearanceValues
import com.letta.mobile.data.shellstate.LegacyAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The theme choices this device kept in its preferences before appearance moved onto the host's shell state
 * document. They are not a client store entry: this only reads them (as the names they were written with), to be
 * proposed to a host that has none yet ([com.letta.mobile.data.shellstate.appearance.SharedShellAppearance]), and
 * removes them once the host holds them.
 */
class LegacyAppearanceStore(private val preferences: SettingsPreferencesStore) : LegacyAppearance {
    override val values: Flow<AppearanceValues> = preferences.snapshots.map { snapshot ->
        AppearanceValues(
            theme = snapshot.getString(THEME),
            themePreset = snapshot.getString(THEME_PRESET),
            dynamicColor = snapshot.getBoolean(DYNAMIC_COLOR),
        )
    }

    override suspend fun clear() = preferences.edit { editor ->
        editor.remove(THEME)
        editor.remove(THEME_PRESET)
        editor.remove(DYNAMIC_COLOR)
    }

    private companion object {
        const val THEME = "theme"
        const val THEME_PRESET = "theme_preset"
        const val DYNAMIC_COLOR = "dynamic_color"
    }
}
