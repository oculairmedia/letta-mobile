package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.shared.SharedShellState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The appearance area of the shell state document: the entries it owns. They all sit under the `appearance.`
 * namespace the host allows (`ShellStateWire.KEY_NAMESPACES`); no other area, and no secret, shares it.
 */
object AppearanceKeys {
    /** The theme mode, as the [AppTheme] constant's name. */
    const val THEME: String = "appearance.theme"

    /** The colour preset, as the [ThemePreset] constant's name. */
    const val THEME_PRESET: String = "appearance.theme_preset"

    /** Whether wallpaper colours replace the preset, as a boolean. */
    const val DYNAMIC_COLOR: String = "appearance.dynamic_color"

    /** Every key this area writes. */
    val all: Set<String> = setOf(THEME, THEME_PRESET, DYNAMIC_COLOR)
}

/**
 * What the person chose for the look of the app; a null field means no choice was recorded yet, so the platform
 * default applies. Values that are not recognised (written by a newer client) read as no choice.
 */
data class AppearanceValues(
    val theme: AppTheme? = null,
    val themePreset: ThemePreset? = null,
    val dynamicColor: Boolean? = null,
) {
    /** Whether no field holds a choice. */
    val isEmpty: Boolean get() = this == NONE

    /** This choice where there is one, else [fallback]'s. */
    fun orElse(fallback: AppearanceValues): AppearanceValues = AppearanceValues(
        theme = theme ?: fallback.theme,
        themePreset = themePreset ?: fallback.themePreset,
        dynamicColor = dynamicColor ?: fallback.dynamicColor,
    )

    /** The edits that record every choice here whose key is not in [taken]: what a migration may propose. */
    fun editsExcept(taken: Set<String>): List<SharedShellState.Edit> = listOfNotNull(
        theme?.let { SharedShellState.Edit(AppearanceKeys.THEME, JsonPrimitive(it.name)) },
        themePreset?.let { SharedShellState.Edit(AppearanceKeys.THEME_PRESET, JsonPrimitive(it.name)) },
        dynamicColor?.let { SharedShellState.Edit(AppearanceKeys.DYNAMIC_COLOR, JsonPrimitive(it)) },
    ).filterNot { it.key in taken }

    companion object {
        /** No choice recorded. */
        val NONE = AppearanceValues()

        /** The choices held by [entries] of a shell state document (null reads as none). */
        fun of(entries: Map<String, ShellStateEntry>?): AppearanceValues = AppearanceValues(
            theme = entries.name(AppearanceKeys.THEME)?.let { name -> AppTheme.entries.firstOrNull { it.name == name } },
            themePreset = entries.name(AppearanceKeys.THEME_PRESET)?.let { name -> ThemePreset.entries.firstOrNull { it.name == name } },
            dynamicColor = entries?.get(AppearanceKeys.DYNAMIC_COLOR)?.value.asBoolean(),
        )

        private fun Map<String, ShellStateEntry>?.name(key: String): String? =
            (this?.get(key)?.value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    }
}
