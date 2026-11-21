package com.letta.mobile.data.shellstate

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The appearance area of the shell state document: the entries it owns. They all sit under the `appearance.`
 * namespace the host allows (`ShellStateWire.KEY_NAMESPACES`); no other area, and no secret, shares it.
 */
object AppearanceKeys {
    /** The theme mode, as the name of the app's theme constant (`LIGHT`, `DARK` or `SYSTEM`). */
    const val THEME: String = "appearance.theme"

    /** The colour preset, as the name of the app's preset constant. */
    const val THEME_PRESET: String = "appearance.theme_preset"

    /** Whether wallpaper colours replace the preset, as a boolean. */
    const val DYNAMIC_COLOR: String = "appearance.dynamic_color"

    /** Every key this area writes. */
    val all: Set<String> = setOf(THEME, THEME_PRESET, DYNAMIC_COLOR)
}

/** One appearance entry to write: the document [key] and its JSON [value]. */
data class AppearanceEdit(val key: String, val value: JsonElement)

/**
 * What the person chose for the look of the app, as the names the document stores ([theme] and [themePreset] are the
 * names of the app's theme and preset constants; the settings repository turns them into those constants, and reads a
 * name it does not know as no choice). A null field means no choice was recorded yet, so the platform default applies.
 */
data class AppearanceValues(
    val theme: String? = null,
    val themePreset: String? = null,
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
    fun editsExcept(taken: Set<String>): List<AppearanceEdit> = listOfNotNull(
        theme?.let { AppearanceEdit(AppearanceKeys.THEME, JsonPrimitive(it)) },
        themePreset?.let { AppearanceEdit(AppearanceKeys.THEME_PRESET, JsonPrimitive(it)) },
        dynamicColor?.let { AppearanceEdit(AppearanceKeys.DYNAMIC_COLOR, JsonPrimitive(it)) },
    ).filterNot { it.key in taken }

    companion object {
        /** No choice recorded. */
        val NONE = AppearanceValues()

        /** The choices held by [entries] of a shell state document (null reads as none; a wrongly typed value is no choice). */
        fun of(entries: Map<String, ShellStateEntry>?): AppearanceValues = AppearanceValues(
            theme = entries.text(AppearanceKeys.THEME),
            themePreset = entries.text(AppearanceKeys.THEME_PRESET),
            dynamicColor = entries?.get(AppearanceKeys.DYNAMIC_COLOR)?.value.asBoolean(),
        )

        private fun Map<String, ShellStateEntry>?.text(key: String): String? =
            (this?.get(key)?.value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    }
}
