@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.shellstate.AppearanceValues
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What every client draws comes from the host's appearance: its theme mode and its colour preset, with SYSTEM following
 * the OS. The oracles are the presets' own token colours (hue) and the luminance of the drawn background, not the theme's
 * own helpers.
 */
class HostAppearanceThemeTest {
    private val held = MutableStateFlow(AppearanceValues.NONE)
    private val host = FixedHost(held)

    /** The primary and background the theme draws for [appearance], with the OS dark or not. */
    private fun drawn(appearance: AppearanceValues, systemDark: Boolean, darkOverride: Boolean? = null): Pair<Color, Color> {
        held.value = appearance
        var primary = Color.Unspecified
        var background = Color.Unspecified
        runComposeUiTest {
            setContent {
                HostAppearanceTheme(host, systemDark = systemDark, darkOverride = darkOverride) {
                    primary = MaterialTheme.colorScheme.primary
                    background = MaterialTheme.colorScheme.background
                    Text("drawn")
                }
            }
            waitForIdle()
        }
        return primary to background
    }

    private fun isDark(background: Color) = background.luminance() < DARK_BELOW

    private fun isLight(background: Color) = background.luminance() > LIGHT_ABOVE

    @Test
    fun systemFollowsTheOsInBothDirections() {
        assertTrue(isDark(drawn(AppearanceValues(theme = "SYSTEM"), systemDark = true).second), "SYSTEM on a dark OS draws dark")
        assertTrue(isLight(drawn(AppearanceValues(theme = "SYSTEM"), systemDark = false).second), "SYSTEM on a light OS draws light")
    }

    @Test
    fun theHostsLightAndDarkThemesOverrideTheOs() {
        assertTrue(isLight(drawn(AppearanceValues(theme = "LIGHT"), systemDark = true).second), "LIGHT on a dark OS draws light")
        assertTrue(isDark(drawn(AppearanceValues(theme = "DARK"), systemDark = false).second), "DARK on a light OS draws dark")
    }

    @Test
    fun theHostsPresetIsTheOneDrawn() {
        assertTrue(hueDistance(drawn(AppearanceValues("DARK", "OCEAN"), systemDark = true).first, OCEAN_DARK_PRIMARY) < HUE_TOLERANCE)
        assertTrue(hueDistance(drawn(AppearanceValues("DARK", "SAKURA"), systemDark = true).first, SAKURA_DARK_PRIMARY) < HUE_TOLERANCE)
        assertTrue(hueDistance(drawn(AppearanceValues("DARK", "DEFAULT"), systemDark = true).first, TEAL_DARK_PRIMARY) < HUE_TOLERANCE)
    }

    @Test
    fun aHostWithNoChoiceDrawsTheDefaultThatFollowsTheOs() {
        val (primary, background) = drawn(AppearanceValues.NONE, systemDark = true)

        assertTrue(isDark(background))
        assertTrue(hueDistance(primary, TEAL_DARK_PRIMARY) < HUE_TOLERANCE)
    }

    @Test
    fun aNameThisBuildDoesNotKnowDrawsTheDefaultsRatherThanFailing() {
        val (primary, background) = drawn(AppearanceValues(theme = "SOLARIZED", themePreset = "NEON"), systemDark = true)

        assertTrue(isDark(background), "an unknown theme is SYSTEM, which follows the dark OS")
        assertTrue(hueDistance(primary, TEAL_DARK_PRIMARY) < HUE_TOLERANCE)
    }

    @Test
    fun aClientsOwnDarkOverrideWinsOverTheHostsTheme() {
        assertTrue(isDark(drawn(AppearanceValues(theme = "LIGHT"), systemDark = false, darkOverride = true).second))
    }

    @Test
    fun aChangeOnTheHostRedrawsWithoutARestart() = runComposeUiTest {
        var background by mutableStateOf(Color.Unspecified)
        held.value = AppearanceValues(theme = "LIGHT")
        setContent {
            HostAppearanceTheme(host, systemDark = false) {
                background = MaterialTheme.colorScheme.background
                Text("drawn")
            }
        }
        waitForIdle()
        assertTrue(isLight(background))

        held.value = AppearanceValues(theme = "DARK")
        waitForIdle()

        assertTrue(isDark(background), "another device switched the theme to dark")
    }

    private fun hueDistance(color: Color, tokenArgb: Long): Float {
        val distance = abs(color.toHslColor().hue - Color(tokenArgb).toHslColor().hue)
        return minOf(distance, FULL_TURN - distance)
    }

    private class FixedHost(private val held: MutableStateFlow<AppearanceValues>) : HostAppearance {
        override val values = held
        override val current: AppearanceValues get() = held.value
        override suspend fun setTheme(name: String) = ShellStateEditOutcome.OFFLINE
        override suspend fun setThemePreset(name: String) = ShellStateEditOutcome.OFFLINE
        override suspend fun setDynamicColor(enabled: Boolean) = ShellStateEditOutcome.OFFLINE
        override suspend fun run() = Unit
        override suspend fun clearLocal() = Unit
    }

    private companion object {
        const val DARK_BELOW = 0.2f
        const val LIGHT_ABOVE = 0.8f
        const val FULL_TURN = 360f
        const val HUE_TOLERANCE = 6f
        const val OCEAN_DARK_PRIMARY = 0xFF6FD8F6
        const val SAKURA_DARK_PRIMARY = 0xFFF0A8C1
        const val TEAL_DARK_PRIMARY = 0xFF00BFA5
    }
}
