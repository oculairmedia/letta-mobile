package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.shellstate.AppearanceKeys
import com.letta.mobile.data.shellstate.AppearanceValues
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
    fun theHostsEntriesReadBackAsTheNamesTheyWereWrittenWith() {
        val entries = mapOf(
            AppearanceKeys.THEME to entry(JsonPrimitive("DARK")),
            AppearanceKeys.THEME_PRESET to entry(JsonPrimitive("AMOLED_BLACK")),
            AppearanceKeys.DYNAMIC_COLOR to entry(JsonPrimitive(true)),
        )
        assertEquals(AppearanceValues("DARK", "AMOLED_BLACK", true), AppearanceValues.of(entries))
        assertEquals(AppearanceValues.NONE, AppearanceValues.of(null))
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
        val device = AppearanceValues("DARK", "OCEAN", dynamicColor = false)
        val host = AppearanceValues(theme = "LIGHT")
        assertEquals(AppearanceValues("LIGHT", "OCEAN", false), host.orElse(device))
        val proposed = device.editsExcept(setOf(AppearanceKeys.THEME)).map { it.key }
        assertEquals(listOf(AppearanceKeys.THEME_PRESET, AppearanceKeys.DYNAMIC_COLOR), proposed)
        assertTrue(device.editsExcept(AppearanceKeys.all).isEmpty())
    }

    private fun entry(value: JsonElement) = ShellStateEntry(value, 1, ShellStateActor.User("endpoint-b"), 1)
}
