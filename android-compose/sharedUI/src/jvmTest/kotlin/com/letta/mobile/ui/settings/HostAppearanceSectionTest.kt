@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.shellstate.AppearanceValues
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Appearance section as the desktop and the web draw it: bound to the host's appearance by
 * [rememberHostAppearanceSettings], not to anything the platform keeps. The host here is the [HostAppearance] port itself;
 * its behaviour against the production routes is asserted in sharedLogic (HostAppearanceSettingsTest).
 */
class HostAppearanceSectionTest {
    private val host = RecordingHost()

    @Test
    fun theSectionShowsWhatTheHostHoldsAndPicksChangeTheHostsValue() = runComposeUiTest {
        host.held.value = AppearanceValues(theme = "LIGHT", themePreset = "SAKURA")
        setContent { Frame { AppearanceSettingsSection(rememberHostAppearanceSettings(host)) } }

        onNodeWithTag(SettingsSectionTags.SECTION_PREFIX + "appearance").assertExists()
        onNodeWithText("Light").assertExists()
        onNodeWithText("Sakura").assertExists()

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-theme").performClick()
        onNodeWithTag(SettingsSectionTags.OPTION_PREFIX + "appearance-theme-" + AppTheme.DARK.name).performClick()
        waitForIdle()

        assertEquals("DARK", host.held.value.theme, "the host's value changed")
        onNodeWithText("Dark").assertExists()
    }

    @Test
    fun aPresetChangesTheHostsPresetAndTurnsDynamicColourOffThere() = runComposeUiTest {
        host.held.value = AppearanceValues(dynamicColor = true)
        setContent { Frame { AppearanceSettingsSection(rememberHostAppearanceSettings(host, dynamicColorSupported = true)) } }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-theme-preset").performClick()
        onNodeWithTag(SettingsSectionTags.OPTION_PREFIX + "appearance-theme-preset-" + ThemePreset.OCEAN.name).performClick()
        waitForIdle()

        assertEquals("OCEAN", host.held.value.themePreset)
        assertEquals(false, host.held.value.dynamicColor)
    }

    @Test
    fun dynamicColourIsNotOfferedWhereThePlatformHasNone() = runComposeUiTest {
        setContent { Frame { AppearanceSettingsSection(rememberHostAppearanceSettings(host)) } }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-theme").assertExists()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-theme-preset").assertExists()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-dynamic-color").assertDoesNotExist()
    }

    @Test
    fun dynamicColourIsOfferedWhereThePlatformHasIt() = runComposeUiTest {
        setContent { Frame { AppearanceSettingsSection(rememberHostAppearanceSettings(host, dynamicColorSupported = true)) } }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-dynamic-color").assertExists()
    }

    @Test
    fun anEditTheHostRefusesShowsWhyAndTheSectionKeepsTheHostsValue() = runComposeUiTest {
        host.held.value = AppearanceValues(theme = "LIGHT")
        host.outcome = ShellStateEditOutcome.OFFLINE
        setContent { Frame { AppearanceSettingsSection(rememberHostAppearanceSettings(host)) } }

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-theme").performClick()
        onNodeWithTag(SettingsSectionTags.OPTION_PREFIX + "appearance-theme-" + AppTheme.DARK.name).performClick()
        waitForIdle()

        onNodeWithText("Can't reach your host, so this was not changed.", substring = true).assertExists()
        assertEquals("LIGHT", host.held.value.theme)
        onNodeWithText("Light").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun Frame(content: @androidx.compose.runtime.Composable () -> Unit) = SharedMaterialTheme(darkTheme = true) { content() }

    /** The port with the host's semantics at its boundary: an edit the host takes changes what it holds; one it refuses changes nothing. */
    private class RecordingHost : HostAppearance {
        val held = MutableStateFlow(AppearanceValues.NONE)
        var outcome = ShellStateEditOutcome.APPLIED
        override val values = held
        override val current: AppearanceValues get() = held.value

        override suspend fun setTheme(name: String) = take { it.copy(theme = name) }
        override suspend fun setThemePreset(name: String) = take { it.copy(themePreset = name) }
        override suspend fun setDynamicColor(enabled: Boolean) = take { it.copy(dynamicColor = enabled) }
        override suspend fun selectThemePreset(name: String, dynamicColor: Boolean?) =
            take { it.copy(themePreset = name, dynamicColor = dynamicColor ?: it.dynamicColor) }
        override suspend fun run() = Unit
        override suspend fun clearLocal() = Unit

        private fun take(change: (AppearanceValues) -> AppearanceValues): ShellStateEditOutcome =
            outcome.also { if (it == ShellStateEditOutcome.APPLIED) held.value = change(held.value) }
    }
}
