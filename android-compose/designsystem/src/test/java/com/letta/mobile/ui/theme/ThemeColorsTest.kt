package com.letta.mobile.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.model.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class ThemeColorsTest {

    // ── presetThemeColors ────────────────────────────────────

    @Test
    fun `presetThemeColors returns all 6 presets`() {
        for (preset in ThemePreset.entries) {
            val colors = presetThemeColors(preset)
            assertNotNull("Null for $preset", colors)
            assertNotNull("Null lightScheme for $preset", colors.lightScheme)
            assertNotNull("Null darkScheme for $preset", colors.darkScheme)
        }
    }

    @Test
    fun `presetThemeColors DEFAULT is lightColorScheme based`() {
        val scheme = presetThemeColors(ThemePreset.DEFAULT).lightScheme
        assertEquals(Color(0xFF00897B), scheme.primary)
    }

    @Test
    fun `presetThemeColors AMOLED_BLACK darkScheme has black surface`() {
        val scheme = presetThemeColors(ThemePreset.AMOLED_BLACK).darkScheme
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color.Black, scheme.background)
    }

    // ── deriveCustomColors ────────────────────────────────────

    @Test
    fun `deriveCustomColors maps primaryContainer to userBubbleBgColor`() {
        val scheme = lightColorScheme(primaryContainer = Color(0xFFBBCCDD))
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFFBBCCDD), custom.userBubbleBgColor)
    }

    @Test
    fun `deriveCustomColors maps primary to textLink and successColor`() {
        val scheme = lightColorScheme(primary = Color(0xFF123456))
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFF123456), custom.textLink)
        assertEquals(Color(0xFF123456), custom.successColor)
    }

    @Test
    fun `deriveCustomColors maps error to offlineColor`() {
        val scheme = lightColorScheme(error = Color(0xFFAA0000))
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFFAA0000), custom.offlineColor)
    }

    @Test
    fun `deriveCustomColors maps secondaryContainer to toolBubbleBgColor`() {
        val scheme = lightColorScheme(secondaryContainer = Color(0xFFAABBCC))
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFFAABBCC), custom.toolBubbleBgColor)
    }

    @Test
    fun `deriveCustomColors has non-unspecified freshAccent`() {
        val scheme = lightColorScheme(primary = Color(0xFF00897B))
        val custom = deriveCustomColors(scheme)
        assertTrue(custom.freshAccent != Color.Unspecified)
        assertTrue(custom.freshAccent.alpha > 0.9f)
    }

    @Test
    fun `deriveCustomColors light theme maps text colors correctly`() {
        val scheme = lightColorScheme(
            onSurface = Color(0xFF111111),
            onSurfaceVariant = Color(0xFF444444),
            onPrimaryContainer = Color(0xFF555555),
        )
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFF111111), custom.textPrimary)
        assertEquals(Color(0xFF444444), custom.textSecondary)
        assertEquals(Color(0xFF555555), custom.textOnPrimary)
    }

    @Test
    fun `deriveCustomColors dark theme detects luminance below 0_5f`() {
        val scheme = lightColorScheme(background = Color(0xFF111111))
        val custom = deriveCustomColors(scheme)
        // Dark theme should use higher lightness range for freshAccent
        assertTrue(custom.freshAccent != Color.Unspecified)
    }

    @Test
    fun `deriveCustomColors warning colors map to tertiary`() {
        val scheme = lightColorScheme(
            tertiaryContainer = Color(0xFFEEDDCC),
            onTertiaryContainer = Color(0xFF332211),
        )
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFF332211), custom.warningTextColor)
        assertEquals(Color(0xFFEEDDCC), custom.warningContainerColor)
    }

    @Test
    fun `deriveCustomColors border colors mapped correctly`() {
        val scheme = lightColorScheme(
            outlineVariant = Color(0xFFCCCCCC),
            primary = Color(0xFF00897B),
            error = Color(0xFFB00020),
        )
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFFCCCCCC), custom.borderDefault)
        assertEquals(Color(0xFF00897B), custom.borderFocused)
        assertEquals(Color(0xFFB00020), custom.borderCritical)
    }

    @Test
    fun `deriveCustomColors icon colors mapped correctly`() {
        val scheme = lightColorScheme(
            onSurface = Color(0xFF222222),
            onSurfaceVariant = Color(0xFF555555),
            primary = Color(0xFF00897B),
        )
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFF222222), custom.iconPrimary)
        assertEquals(Color(0xFF555555), custom.iconSecondary)
        assertEquals(Color(0xFF00897B), custom.iconAccent)
    }

    @Test
    fun `deriveCustomColors agentBubbleBgColor is surfaceContainerLow`() {
        val scheme = lightColorScheme(surfaceContainerLow = Color(0xFFF0F0F0))
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFFF0F0F0), custom.agentBubbleBgColor)
    }

    @Test
    fun `deriveCustomColors status colors mapped correctly`() {
        val scheme = lightColorScheme(
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            primary = Color(0xFF00897B),
            primaryContainer = Color(0xFFB2DFDB),
        )
        val custom = deriveCustomColors(scheme)
        assertEquals(Color(0xFF410002), custom.errorTextColor)
        assertEquals(Color(0xFFFFDAD6), custom.errorContainerColor)
        assertEquals(Color(0xFF00897B), custom.successColor)
        assertEquals(Color(0xFFB2DFDB), custom.successContainerColor)
    }

    // ── ThemePreset enum ─────────────────────────────────────

    @Test
    fun `ThemePreset has exactly 6 values`() {
        assertEquals(6, ThemePreset.entries.size)
    }
}
