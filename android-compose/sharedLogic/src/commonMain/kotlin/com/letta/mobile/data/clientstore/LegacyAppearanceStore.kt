package com.letta.mobile.data.clientstore

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.shellstate.appearance.AppearanceValues
import com.letta.mobile.data.shellstate.appearance.LegacyAppearance
import com.letta.mobile.data.storage.SettingsPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The theme choices this device kept in its preferences before appearance moved onto the host's shell state
 * document. They are not a client store entry: this only reads them, to be proposed to a host that has none yet
 * ([com.letta.mobile.data.shellstate.appearance.HostAppearance]), and removes them once the host holds them.
 */
class LegacyAppearanceStore(private val preferences: SettingsPreferencesStore) : LegacyAppearance {
    override val values: Flow<AppearanceValues> = preferences.snapshots.map { snapshot ->
        AppearanceValues(
            theme = snapshot.getString(THEME)?.let { name -> AppTheme.entries.firstOrNull { it.name == name } },
            themePreset = snapshot.getString(THEME_PRESET)?.let { name -> ThemePreset.entries.firstOrNull { it.name == name } },
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
