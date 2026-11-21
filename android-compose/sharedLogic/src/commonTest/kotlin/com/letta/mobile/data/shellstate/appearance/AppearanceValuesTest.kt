package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class AppearanceValuesTest {
    @Test
    fun everyKeyThisAreaWritesIsInTheNamespaceTheHostAllows() {
        assertTrue(AppearanceKeys.all.all(ShellStateWire::isAllowedKey), "${AppearanceKeys.all}")
    }

    @Test
    fun theHostsEntriesReadBackAsTheChoices() {
        val entries = mapOf(
            AppearanceKeys.THEME to entry(JsonPrimitive("AMOLED")),
            AppearanceKeys.THEME_PRESET to entry(JsonPrimitive("AMOLED_BLACK")),
            AppearanceKeys.DYNAMIC_COLOR to entry(JsonPrimitive(true)),
        )
        // "AMOLED" is not an AppTheme: an unrecognised value (a newer client's) reads as no choice, never as a crash.
        assertEquals(AppearanceValues(null, ThemePreset.AMOLED_BLACK, true), AppearanceValues.of(entries))
        assertEquals(AppearanceValues.NONE, AppearanceValues.of(null))
        assertEquals(AppearanceValues(AppTheme.DARK), AppearanceValues.of(mapOf(AppearanceKeys.THEME to entry(JsonPrimitive("DARK")))))
    }

    @Test
    fun aWronglyTypedValueIsNoChoice() {
        val entries = mapOf(
            AppearanceKeys.THEME to entry(JsonPrimitive(1)),
            AppearanceKeys.DYNAMIC_COLOR to entry(JsonPrimitive("true")),
        )
        assertEquals(AppearanceValues.NONE, AppearanceValues.of(entries))
    }

    @Test
    fun theHostsChoiceWinsOverTheDevicesAndMigrationSkipsWhatTheHostHolds() {
        val device = AppearanceValues(AppTheme.DARK, ThemePreset.OCEAN, dynamicColor = false)
        val host = AppearanceValues(theme = AppTheme.LIGHT)
        assertEquals(AppearanceValues(AppTheme.LIGHT, ThemePreset.OCEAN, false), host.orElse(device))
        assertEquals(listOf(AppearanceKeys.THEME_PRESET, AppearanceKeys.DYNAMIC_COLOR), device.editsExcept(setOf(AppearanceKeys.THEME)).map { it.key })
        assertTrue(device.editsExcept(AppearanceKeys.all).isEmpty())
    }

    private fun entry(value: JsonElement) = ShellStateEntry(value, 1, ShellStateActor.User("endpoint-b"), 1)
}
