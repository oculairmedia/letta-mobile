package com.letta.mobile.ui.theme

import com.letta.mobile.data.model.ThemePreset

object LettaColorTokens {
    // Neutrals — "cool slate" retune (2026-06-23): a subtle cool blue-grey
    // undertone over the older true-greys. Teal primary #00BFA5 unchanged.
    // Single source of truth for both desktop and Android (default preset).
    const val darkSurface = 0xFF13161B
    const val darkSurfaceVariant = 0xFF1A1E25
    const val darkSurfaceContainer = 0xFF1A1E25
    const val darkPrimary = 0xFF00BFA5
    const val darkPrimaryVariant = 0xFF0E8C7E
    const val darkOnSurface = 0xFFE6E9EF
    const val darkOnSurfaceVariant = 0xFFAEB6C2
    const val darkOnSurfaceMuted = 0xFF717A87
    const val darkError = 0xFFCF6679
    const val darkOnError = 0xFF000000
    const val darkBackground = 0xFF0B0D11
    const val darkOutline = 0xFF3A414E

    const val lightSurface = 0xFFF5F7FA
    const val lightSurfaceVariant = 0xFFEAEEF3
    const val lightSurfaceContainer = 0xFFDDE3EA
    const val lightPrimary = 0xFF00897B
    const val lightPrimaryVariant = 0xFF00695C
    const val lightOnSurface = 0xFF161A20
    const val lightOnSurfaceVariant = 0xFF4A5360
    const val lightOnSurfaceMuted = 0xFF6B7480
    const val lightError = 0xFFB00020
    const val lightOnError = 0xFFFFFFFF
    const val lightBackground = 0xFFFBFCFD
    const val lightOutline = 0xFFB4BCC8

    const val tealAccent = 0xFF1DE9B6
    const val cyanAccent = 0xFF00E5FF
    const val amberAccent = 0xFFFFD740

    // Surface levels (dark) used by the desktop scheme + template chrome
    // (cool-slate retune).
    const val darkSurfaceContainerLowest = 0xFF0D0F13
    const val darkSurfaceContainerLow = 0xFF13161B
    const val darkSurfaceContainerDefault = 0xFF1A1E25
    const val darkSurfaceContainerHigh = 0xFF242A33
    const val darkSurfaceContainerHighest = 0xFF2E343F
    const val darkOutlineVariant = 0xFF2A2F39

    // Agent status tokens (fixed brand values — not derived from the scheme).
    const val darkRunning = 0xFFE0A458
    const val lightRunning = 0xFFB26A00
    const val darkOnRunning = 0xFF2B1B00
    const val lightOnRunning = 0xFFFFFFFF
    const val darkSuccess = 0xFF46C08F
    const val lightSuccess = 0xFF2E9E73
    const val darkOnSuccess = 0xFF06302B
    const val lightOnSuccess = 0xFFFFFFFF
    const val darkAgentA = 0xFF8B7CF0
    const val lightAgentA = 0xFF6B4EE6
    const val darkAgentB = 0xFF4C9AFF
    const val lightAgentB = 0xFF1A73E8
    const val darkAgentC = 0xFFE36FB3
    const val lightAgentC = 0xFFC03D8E

    // Memory-block category tokens (Phase 6 / §1.1b). Distinct from agentA/B/C
    // (subagent identity) — these color-code memory blocks by category.
    const val darkCategoryPersona = 0xFF00BFA5
    const val lightCategoryPersona = 0xFF00BFA5
    const val darkCategoryHuman = 0xFF5C9BD6
    const val lightCategoryHuman = 0xFF2F6FB0
    const val darkCategoryOnboarding = 0xFFD1A05A
    const val lightCategoryOnboarding = 0xFF9A6A1F
    const val darkCategoryProject = 0xFF9B8AE0
    const val lightCategoryProject = 0xFF6E5BB8
    const val darkCategoryArchival = 0xFF828B98
    const val lightCategoryArchival = 0xFF5F6469
}

data class LettaThemePaletteTokens(
    val primaryArgb: Long,
    val primaryContainerArgb: Long,
    val secondaryArgb: Long,
    val tertiaryArgb: Long,
    val backgroundArgb: Long,
    val surfaceArgb: Long,
    val surfaceVariantArgb: Long,
    val outlineArgb: Long,
)

data class LettaThemePresetTokens(
    val light: LettaThemePaletteTokens,
    val dark: LettaThemePaletteTokens,
)

object LettaThemeTokens {
    val default = LettaThemePresetTokens(
        light = LettaThemePaletteTokens(
            primaryArgb = LettaColorTokens.lightPrimary,
            primaryContainerArgb = 0xFFB2DFDB,
            secondaryArgb = 0xFF00ACC1,
            tertiaryArgb = 0xFF0091EA,
            backgroundArgb = LettaColorTokens.lightBackground,
            surfaceArgb = LettaColorTokens.lightSurface,
            surfaceVariantArgb = LettaColorTokens.lightSurfaceVariant,
            outlineArgb = LettaColorTokens.lightOutline,
        ),
        dark = LettaThemePaletteTokens(
            primaryArgb = LettaColorTokens.darkPrimary,
            primaryContainerArgb = LettaColorTokens.darkPrimaryVariant,
            secondaryArgb = LettaColorTokens.tealAccent,
            tertiaryArgb = LettaColorTokens.cyanAccent,
            backgroundArgb = LettaColorTokens.darkBackground,
            surfaceArgb = LettaColorTokens.darkSurface,
            surfaceVariantArgb = LettaColorTokens.darkSurfaceVariant,
            outlineArgb = LettaColorTokens.darkOutline,
        ),
    )

    val sakura = LettaThemePresetTokens(
        light = LettaThemePaletteTokens(
            primaryArgb = 0xFFB45C7B,
            primaryContainerArgb = 0xFFF4D7E3,
            secondaryArgb = 0xFF9C6B9D,
            tertiaryArgb = 0xFF6E8CCB,
            backgroundArgb = 0xFFFFF8FB,
            surfaceArgb = 0xFFFFFBFD,
            surfaceVariantArgb = 0xFFF8EAF0,
            outlineArgb = 0xFFD8B7C4,
        ),
        dark = LettaThemePaletteTokens(
            primaryArgb = 0xFFF0A8C1,
            primaryContainerArgb = 0xFF6F3A50,
            secondaryArgb = 0xFFD7A8D9,
            tertiaryArgb = 0xFFA8C1FF,
            backgroundArgb = 0xFF171014,
            surfaceArgb = 0xFF21161C,
            surfaceVariantArgb = 0xFF35232D,
            outlineArgb = 0xFF76505F,
        ),
    )

    val ocean = LettaThemePresetTokens(
        light = LettaThemePaletteTokens(
            primaryArgb = 0xFF006D8F,
            primaryContainerArgb = 0xFFC8ECF6,
            secondaryArgb = 0xFF007C91,
            tertiaryArgb = 0xFF4F6EF7,
            backgroundArgb = 0xFFF6FCFE,
            surfaceArgb = 0xFFFBFEFF,
            surfaceVariantArgb = 0xFFE4F3F8,
            outlineArgb = 0xFFA6C9D4,
        ),
        dark = LettaThemePaletteTokens(
            primaryArgb = 0xFF6FD8F6,
            primaryContainerArgb = 0xFF004C66,
            secondaryArgb = 0xFF66D9E8,
            tertiaryArgb = 0xFF98AEFF,
            backgroundArgb = 0xFF08141A,
            surfaceArgb = 0xFF0E1D24,
            surfaceVariantArgb = 0xFF14313A,
            outlineArgb = 0xFF41616B,
        ),
    )

    val amoledBlack = LettaThemePresetTokens(
        light = LettaThemePaletteTokens(
            primaryArgb = 0xFF2E2E2E,
            primaryContainerArgb = 0xFFE4E4E4,
            secondaryArgb = 0xFF5B5B5B,
            tertiaryArgb = 0xFF767676,
            backgroundArgb = 0xFFFAFAFA,
            surfaceArgb = 0xFFFFFFFF,
            surfaceVariantArgb = 0xFFF0F0F0,
            outlineArgb = 0xFFC8C8C8,
        ),
        dark = LettaThemePaletteTokens(
            primaryArgb = 0xFFE6E6E6,
            primaryContainerArgb = 0xFF000000,
            secondaryArgb = 0xFFCFCFCF,
            tertiaryArgb = 0xFFB8B8B8,
            backgroundArgb = 0xFF000000,
            surfaceArgb = 0xFF000000,
            surfaceVariantArgb = 0xFF000000,
            outlineArgb = 0xFF3F3F3F,
        ),
    )

    val autumn = LettaThemePresetTokens(
        light = LettaThemePaletteTokens(
            primaryArgb = 0xFF9A4F1A,
            primaryContainerArgb = 0xFFF4D7C4,
            secondaryArgb = 0xFFC16A2A,
            tertiaryArgb = 0xFF7D5A2F,
            backgroundArgb = 0xFFFFFBF7,
            surfaceArgb = 0xFFFFFCFA,
            surfaceVariantArgb = 0xFFF7EBDD,
            outlineArgb = 0xFFD3B59B,
        ),
        dark = LettaThemePaletteTokens(
            primaryArgb = 0xFFFFB689,
            primaryContainerArgb = 0xFF6D3209,
            secondaryArgb = 0xFFFFA763,
            tertiaryArgb = 0xFFE1C287,
            backgroundArgb = 0xFF17110D,
            surfaceArgb = 0xFF221915,
            surfaceVariantArgb = 0xFF362720,
            outlineArgb = 0xFF725746,
        ),
    )

    val spring = LettaThemePresetTokens(
        light = LettaThemePaletteTokens(
            primaryArgb = 0xFF2D7D46,
            primaryContainerArgb = 0xFFD8F2DC,
            secondaryArgb = 0xFF4E9D64,
            tertiaryArgb = 0xFF5C8C34,
            backgroundArgb = 0xFFF8FFF8,
            surfaceArgb = 0xFFFBFFFB,
            surfaceVariantArgb = 0xFFEAF6EA,
            outlineArgb = 0xFFB2D0B1,
        ),
        dark = LettaThemePaletteTokens(
            primaryArgb = 0xFF8FDC9B,
            primaryContainerArgb = 0xFF1F5630,
            secondaryArgb = 0xFF9FE0A8,
            tertiaryArgb = 0xFFC0D98A,
            backgroundArgb = 0xFF0E140E,
            surfaceArgb = 0xFF162016,
            surfaceVariantArgb = 0xFF243124,
            outlineArgb = 0xFF4C674D,
        ),
    )

    fun preset(preset: ThemePreset): LettaThemePresetTokens = when (preset) {
        ThemePreset.DEFAULT -> default
        ThemePreset.OCEAN -> ocean
        ThemePreset.AMOLED_BLACK -> amoledBlack
        ThemePreset.SAKURA -> sakura
        ThemePreset.AUTUMN -> autumn
        ThemePreset.SPRING -> spring
    }
}
