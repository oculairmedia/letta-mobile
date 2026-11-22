package com.letta.mobile.ui.theme

import com.letta.mobile.data.model.ThemePreset

object LettaColorTokens {
    // Baken brand (letta-mobile-0sb6o, 2026-11-22): Buoy Vermilion primary over the "North Sea Buoy"
    // deep-sea neutrals from the Baken brand kit. Text on the vermilion primary is white in both themes.
    // Single source of truth for every client (default preset).
    const val DARK_SURFACE = 0xFF13212D
    const val DARK_SURFACE_VARIANT = 0xFF1A2B39
    const val DARK_SURFACE_CONTAINER = 0xFF1A2B39
    const val DARK_PRIMARY = 0xFFEE5A3F
    const val DARK_PRIMARY_VARIANT = 0xFFB8432E
    const val DARK_ON_PRIMARY = 0xFFFFFFFF
    const val DARK_ON_SURFACE = 0xFFE8EFF4
    const val DARK_ON_SURFACE_VARIANT = 0xFFA9BAC8
    const val DARK_ON_SURFACE_MUTED = 0xFF7F96A8
    const val DARK_ERROR = 0xFFCF6679
    const val DARK_ON_ERROR = 0xFF000000
    const val DARK_BACKGROUND = 0xFF0D1821
    const val DARK_OUTLINE = 0xFF3A4E5E

    const val LIGHT_SURFACE = 0xFFF7FAFC
    const val LIGHT_SURFACE_VARIANT = 0xFFE8EEF2
    const val LIGHT_SURFACE_CONTAINER = 0xFFD7E0E7
    const val LIGHT_PRIMARY = 0xFFE04A32
    const val LIGHT_ON_PRIMARY = 0xFFFFFFFF
    const val LIGHT_ON_SURFACE = 0xFF10202C
    const val LIGHT_ON_SURFACE_VARIANT = 0xFF44586A
    const val LIGHT_ON_SURFACE_MUTED = 0xFF586E7F
    const val LIGHT_ERROR = 0xFFB00020
    const val LIGHT_ON_ERROR = 0xFFFFFFFF
    const val LIGHT_BACKGROUND = 0xFFEFF3F6
    const val LIGHT_OUTLINE = 0xFFADBCC8

    // The Baken mark's stones: the pale/petrol stone and the vermilion pebble, per theme (kit svg/baken-mark-*.svg).
    const val DARK_MARK_STONE = 0xFFDCE6EE
    const val LIGHT_MARK_STONE = 0xFF163044

    // Voice mode's two voices: the person in a cool blue, the agent in a warm rose, far apart in hue
    // from each other and from the teal the run's thinking glow uses. The light values are deepened
    // so a hairline of either still reads on a light surface.
    const val DARK_VOICE_YOU = 0xFF6AA6FF
    const val DARK_VOICE_AGENT = 0xFFF08FA8
    const val LIGHT_VOICE_YOU = 0xFF2F6BD9
    const val LIGHT_VOICE_AGENT = 0xFFC0436B

    // Warning: an amber, so a warning never reads as one of the teal/cyan accents. The ink reads at
    // 5:1 or more on every surface and on the container of its own theme.
    const val LIGHT_WARNING = 0xFF8F5200
    const val LIGHT_WARNING_CONTAINER = 0xFFFFE8C7
    const val DARK_WARNING = 0xFFE0A458
    const val DARK_WARNING_CONTAINER = 0xFF3B2A12

    const val TEAL_ACCENT = 0xFF1DE9B6
    const val CYAN_ACCENT = 0xFF00E5FF

    // Surface levels (dark) used by the desktop scheme + template chrome (North Sea Buoy deep-sea family).
    const val DARK_SURFACE_CONTAINER_LOWEST = 0xFF0A131B
    const val DARK_SURFACE_CONTAINER_LOW = 0xFF13212D
    const val DARK_SURFACE_CONTAINER_DEFAULT = 0xFF1A2B39
    const val DARK_SURFACE_CONTAINER_HIGH = 0xFF233645
    const val DARK_SURFACE_CONTAINER_HIGHEST = 0xFF2C4152
    const val DARK_OUTLINE_VARIANT = 0xFF263A4A

    // Memory-block category tokens (Phase 6 / §1.1b). Distinct from agentA/B/C
    // (subagent identity) — these color-code memory blocks by category.
    const val DARK_CATEGORY_PERSONA = 0xFF00BFA5
    const val DARK_CATEGORY_HUMAN = 0xFF5C9BD6
    const val DARK_CATEGORY_ONBOARDING = 0xFFD1A05A
    const val DARK_CATEGORY_PROJECT = 0xFF9B8AE0
    const val DARK_CATEGORY_ARCHIVAL = 0xFF828B98
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
            primaryArgb = LettaColorTokens.LIGHT_PRIMARY,
            primaryContainerArgb = 0xFFFAD5CD,
            secondaryArgb = 0xFF00ACC1,
            tertiaryArgb = 0xFF0091EA,
            backgroundArgb = LettaColorTokens.LIGHT_BACKGROUND,
            surfaceArgb = LettaColorTokens.LIGHT_SURFACE,
            surfaceVariantArgb = LettaColorTokens.LIGHT_SURFACE_VARIANT,
            outlineArgb = LettaColorTokens.LIGHT_OUTLINE,
        ),
        dark = LettaThemePaletteTokens(
            primaryArgb = LettaColorTokens.DARK_PRIMARY,
            primaryContainerArgb = LettaColorTokens.DARK_PRIMARY_VARIANT,
            secondaryArgb = LettaColorTokens.TEAL_ACCENT,
            tertiaryArgb = LettaColorTokens.CYAN_ACCENT,
            backgroundArgb = LettaColorTokens.DARK_BACKGROUND,
            surfaceArgb = LettaColorTokens.DARK_SURFACE,
            surfaceVariantArgb = LettaColorTokens.DARK_SURFACE_VARIANT,
            outlineArgb = LettaColorTokens.DARK_OUTLINE,
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
