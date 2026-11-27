package com.letta.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android draws the desktop's scheme. Without wallpaper colours (the out-of-the-box case; Material You is
 * opt-in in Settings) [LettaTheme]'s whole [ColorScheme] is the shared theme's — the exact scheme
 * [SharedMaterialTheme] builds for the same appearance — on the default palette's Baken roles and every
 * preset's, in normal contrast and in high contrast alike. Each appearance is composed once, the shared
 * theme first and Android's theme inside it, and the pins are whole-scheme equalities, so any role that
 * drifts (a reintroduced Android-only boost, a preset that misses the shared treatment) breaks the parity
 * contract, not just the roles named here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["androidx.loader.content"])
class LettaThemeParityTest {
    @get:Rule
    val compose = createComposeRule()

    private val sharedSchemes = mutableMapOf<Appearance, ColorScheme>()
    private val androidSchemes = mutableMapOf<Appearance, ColorScheme>()

    @Test
    fun theDefaultPaletteDrawsTheSharedSchemeInBothThemeModes() {
        compareWithTheSharedTheme(listOf(ThemePreset.DEFAULT))
    }

    @Test
    fun everyPresetDrawsTheSharedScheme() {
        compareWithTheSharedTheme(ThemePreset.entries.toList())
    }

    @Test
    fun highContrastDrawsTheSharedSchemeToo() {
        compareWithTheSharedTheme(listOf(ThemePreset.DEFAULT), highContrast = true)
    }

    /**
     * Composes each [Appearance]'s pair — the shared theme's scheme, then Android's inside it — in one
     * composition, then compares every role in their string representations; ColorScheme uses identity equality.
     */
    private fun compareWithTheSharedTheme(presets: List<ThemePreset>, highContrast: Boolean = false) {
        val appearances = presets.flatMap { preset -> listOf(Appearance(dark = false, preset), Appearance(dark = true, preset)) }
        val preference = HighContrastPreference().apply { set(highContrast) }
        compose.setContent {
            CompositionLocalProvider(LocalHighContrastPreference provides preference) {
                AppearancePairs(appearances)
            }
        }
        compose.runOnIdle { }
        for (appearance in appearances) {
            assertEquals(
                "preset=${appearance.preset} dark=${appearance.dark}",
                sharedSchemes.getValue(appearance).toString(),
                androidSchemes.getValue(appearance).toString(),
            )
        }
    }

    /** One appearance's pair, recursing into the next inside Android's theme so each is composed once. */
    @Composable
    private fun AppearancePairs(appearances: List<Appearance>, index: Int = 0) {
        if (index >= appearances.size) return
        val appearance = appearances[index]
        SharedMaterialTheme(darkTheme = appearance.dark, preset = appearance.preset) {
            sharedSchemes[appearance] = MaterialTheme.colorScheme
            LettaTheme(
                appTheme = if (appearance.dark) AppTheme.DARK else AppTheme.LIGHT,
                themePreset = appearance.preset,
            ) {
                androidSchemes[appearance] = MaterialTheme.colorScheme
                AppearancePairs(appearances, index + 1)
            }
        }
    }

    /** One theme mode and colour preset: the appearance the two themes are compared at. */
    private data class Appearance(val dark: Boolean, val preset: ThemePreset)
}
