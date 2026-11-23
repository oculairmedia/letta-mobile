package com.letta.mobile.ui.theme

import com.letta.mobile.data.model.ThemePreset

object LettaColorTokens {
    // Baken brand: Buoy Vermilion primary over neutral greys with only a faint cool tint (saturation about 8-12 %),
    // so the vermilion is the one colour on screen. Text on the vermilion primary is white in both themes.
    // Single source of truth for every client (default preset).
    const val DARK_SURFACE = 0xFF15181B
    const val DARK_SURFACE_VARIANT = 0xFF1C1F23
    const val DARK_SURFACE_CONTAINER = 0xFF1C1F23
    const val DARK_PRIMARY = 0xFFEE5A3F
    const val DARK_PRIMARY_VARIANT = 0xFFB8432E
    const val DARK_ON_PRIMARY = 0xFFFFFFFF
    const val DARK_ON_SURFACE = 0xFFE8EBEE
    const val DARK_ON_SURFACE_VARIANT = 0xFFA9B1B9
    const val DARK_ON_SURFACE_MUTED = 0xFF808A94
    const val DARK_ERROR = 0xFFCF6679
    const val DARK_ON_ERROR = 0xFF000000
    const val DARK_BACKGROUND = 0xFF101214
    const val DARK_OUTLINE = 0xFF4A5158

    const val LIGHT_SURFACE = 0xFFFFFFFF
    const val LIGHT_SURFACE_VARIANT = 0xFFEEF0F2
    const val LIGHT_SURFACE_CONTAINER = 0xFFE6E9EC
    const val LIGHT_PRIMARY = 0xFFE04A32
    const val LIGHT_ON_PRIMARY = 0xFFFFFFFF
    const val LIGHT_ON_SURFACE = 0xFF15191D
    const val LIGHT_ON_SURFACE_VARIANT = 0xFF4A525A
    const val LIGHT_ON_SURFACE_MUTED = 0xFF5E6770
    const val LIGHT_ERROR = 0xFFB00020
    const val LIGHT_ON_ERROR = 0xFFFFFFFF
    const val LIGHT_BACKGROUND = 0xFFF4F5F6
    const val LIGHT_OUTLINE = 0xFFAEB4BA
    const val LIGHT_SURFACE_CONTAINER_HIGHEST = 0xFFDEE2E6

    // The Baken mark's stones: the pale/petrol stone and the vermilion pebble, per theme (kit svg/baken-mark-*.svg).
    const val DARK_MARK_STONE = 0xFFDCE6EE
    const val LIGHT_MARK_STONE = 0xFF163044

    // The vermilion's lit face: the highlight at the centre of the assistant sphere's vermilion-to-petrol radial.
    const val PRIMARY_HIGHLIGHT = 0xFFF5A28C

    // Voice mode's two voices: the person in a cool blue, the agent in a warm rose, far apart in hue
    // from each other and from the steel grey the run's thinking glow uses. The light values are deepened
    // so a hairline of either still reads on a light surface.
    const val DARK_VOICE_YOU = 0xFF6AA6FF
    const val DARK_VOICE_AGENT = 0xFFF08FA8
    const val LIGHT_VOICE_YOU = 0xFF2F6BD9
    const val LIGHT_VOICE_AGENT = 0xFFC0436B

    // Warning: an amber, so a warning never reads as one of the grey support roles. The ink reads at
    // 5:1 or more on every surface and on the container of its own theme.
    const val LIGHT_WARNING = 0xFF8F5200
    const val LIGHT_WARNING_CONTAINER = 0xFFFFE8C7
    const val DARK_WARNING = 0xFFE0A458
    const val DARK_WARNING_CONTAINER = 0xFF3B2A12

    // Secondary and tertiary: a slate grey and a lighter steel grey. Quiet support roles, so
    // Buoy Vermilion stays the only accent. Each reads at 3:1 (secondary, a graphic) or 4.5:1 (tertiary, text)
    // on its theme's surfaces; its ink and container pair read at 4.5:1 or more.
    const val DARK_SECONDARY = 0xFF8C98A3
    const val DARK_ON_SECONDARY = 0xFF101214
    // The selected surface (a chosen chip, segment or picker row): a neutral step above the surfaces, onSurface ink.
    const val DARK_SECONDARY_CONTAINER = 0xFF2B3036
    const val DARK_TERTIARY = 0xFFA9B3BC
    const val DARK_ON_TERTIARY = 0xFF101214
    const val DARK_TERTIARY_CONTAINER = 0xFF262B30
    const val DARK_ON_TERTIARY_CONTAINER = 0xFFE1E6EA
    const val LIGHT_SECONDARY = 0xFF4C5761
    const val LIGHT_ON_SECONDARY = 0xFFFFFFFF
    const val LIGHT_SECONDARY_CONTAINER = 0xFFDDE1E5
    const val LIGHT_TERTIARY = 0xFF5C6872
    const val LIGHT_ON_TERTIARY = 0xFFFFFFFF
    const val LIGHT_TERTIARY_CONTAINER = 0xFFE2E6EA
    const val LIGHT_ON_TERTIARY_CONTAINER = 0xFF15191D

    // The user's chat bubble: a neutral surface, never the vermilion. On dark a grey step above the agent's
    // bubble (surfaceContainerLow); on light a soft grey. Text on it is onSurface.
    const val DARK_USER_BUBBLE = 0xFF23272C
    const val LIGHT_USER_BUBBLE = 0xFFE4E7EA

    // Surface levels (dark) used by the desktop scheme + template chrome (the neutral grey family).
    const val DARK_SURFACE_CONTAINER_LOWEST = 0xFF0C0E10
    const val DARK_SURFACE_CONTAINER_LOW = 0xFF181B1E
    const val DARK_SURFACE_CONTAINER_DEFAULT = 0xFF1C1F23
    const val DARK_SURFACE_CONTAINER_HIGH = 0xFF22262A
    const val DARK_SURFACE_CONTAINER_HIGHEST = 0xFF2A2E33
    const val DARK_OUTLINE_VARIANT = 0xFF2C3136

    // Memory-block category tokens (Phase 6 / §1.1b). Distinct from agentA/B/C
    // (subagent identity) — these color-code memory blocks by category.
    // Persona is the agent's own voice, so it takes the brand's vermilion family (a coral on dark, a
    // deep brick on light), apart from the blue, amber, violet and grey of the other categories.
    const val DARK_CATEGORY_PERSONA = 0xFFF08A70
    const val LIGHT_CATEGORY_PERSONA = 0xFFB8432E
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
            secondaryArgb = LettaColorTokens.LIGHT_SECONDARY,
            tertiaryArgb = LettaColorTokens.LIGHT_TERTIARY,
            backgroundArgb = LettaColorTokens.LIGHT_BACKGROUND,
            surfaceArgb = LettaColorTokens.LIGHT_SURFACE,
            surfaceVariantArgb = LettaColorTokens.LIGHT_SURFACE_VARIANT,
            outlineArgb = LettaColorTokens.LIGHT_OUTLINE,
        ),
        dark = LettaThemePaletteTokens(
            primaryArgb = LettaColorTokens.DARK_PRIMARY,
            primaryContainerArgb = LettaColorTokens.DARK_PRIMARY_VARIANT,
            secondaryArgb = LettaColorTokens.DARK_SECONDARY,
            tertiaryArgb = LettaColorTokens.DARK_TERTIARY,
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
