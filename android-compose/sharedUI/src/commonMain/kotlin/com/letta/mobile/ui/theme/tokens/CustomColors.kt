package com.letta.mobile.ui.theme.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * The theme's extra colour roles. The class and its CompositionLocal live in this import-free package
 * so the shared components in ui.components.foundation can read a role (a status tone's colour)
 * without importing ui.theme; call sites keep using `MaterialTheme.customColors` from ui.theme.
 */

/** Colour roles Material's scheme has no slot for (bubbles, status, agents, categories, borders). */
@Immutable
data class CustomColors(
    // Chat bubbles
    val userBubbleBgColor: Color = Color.Unspecified,
    val agentBubbleBgColor: Color = Color.Unspecified,
    val reasoningBubbleBgColor: Color = Color.Unspecified,
    val toolBubbleBgColor: Color = Color.Unspecified,
    val systemMessageColor: Color = Color.Unspecified,
    val dateSeparatorColor: Color = Color.Unspecified,

    // Text
    val textPrimary: Color = Color.Unspecified,
    val textSecondary: Color = Color.Unspecified,
    val textDisabled: Color = Color.Unspecified,
    val textLink: Color = Color.Unspecified,
    val textOnPrimary: Color = Color.Unspecified,

    // Status
    val errorTextColor: Color = Color.Unspecified,
    val errorContainerColor: Color = Color.Unspecified,
    val harmonizedError: Color = Color.Unspecified,
    val harmonizedErrorContainer: Color = Color.Unspecified,
    val warningTextColor: Color = Color.Unspecified,
    val warningContainerColor: Color = Color.Unspecified,
    val harmonizedWarning: Color = Color.Unspecified,
    val harmonizedWarningContainer: Color = Color.Unspecified,
    val successColor: Color = Color.Unspecified,
    val successContainerColor: Color = Color.Unspecified,
    val harmonizedSuccess: Color = Color.Unspecified,
    val harmonizedSuccessContainer: Color = Color.Unspecified,

    // Agent status (fixed brand values — see deriveCustomColors)
    val runningColor: Color = Color.Unspecified,
    val onRunningColor: Color = Color.Unspecified,
    val onSuccessColor: Color = Color.Unspecified,
    val agentAColor: Color = Color.Unspecified,
    val agentBColor: Color = Color.Unspecified,
    val agentCColor: Color = Color.Unspecified,

    // Muted section captions (darker than onSurfaceVariant — Phase 6).
    val onSurfaceMutedColor: Color = Color.Unspecified,

    // Memory-block category colors (Phase 6 / §1.1b) — color-code blocks by
    // category; distinct from agentA/B/C subagent identity.
    val categoryPersonaColor: Color = Color.Unspecified,
    val categoryHumanColor: Color = Color.Unspecified,
    val categoryOnboardingColor: Color = Color.Unspecified,
    val categoryProjectColor: Color = Color.Unspecified,
    val categoryArchivalColor: Color = Color.Unspecified,

    // Connection status
    val onlineColor: Color = Color.Unspecified,
    val offlineColor: Color = Color.Unspecified,
    val reconnectingColor: Color = Color.Unspecified,

    // Icons
    val iconPrimary: Color = Color.Unspecified,
    val iconSecondary: Color = Color.Unspecified,
    val iconAccent: Color = Color.Unspecified,

    val freshAccent: Color = Color.Unspecified,
    val onFreshAccent: Color = Color.Unspecified,
    val freshAccentContainer: Color = Color.Unspecified,
    val onFreshAccentContainer: Color = Color.Unspecified,

    val listItemContainerColor: Color = Color.Unspecified,

    // Selection (complementary to primary — warm↔cool inversion)
    val selectionContainer: Color = Color.Unspecified,
    val onSelectionContainer: Color = Color.Unspecified,
    val selectionIndicator: Color = Color.Unspecified,

    // Borders
    val borderDefault: Color = Color.Unspecified,
    val borderFocused: Color = Color.Unspecified,
    val borderCritical: Color = Color.Unspecified,
)

/** The [CustomColors] in scope; read it as `MaterialTheme.customColors`. Every Letta theme provides it. */
val LocalLettaCustomColors = staticCompositionLocalOf { CustomColors() }

/**
 * Fixed beam palettes (lettaBorderBeam). The tone-mapped beams read the theme instead; this is the
 * one multicolour set, the same in light and dark because it draws over the container's own edge.
 * Ported from border-beam-android's `colorful` palette (MIT, see sharedUI/THIRD_PARTY_LICENSES).
 * Ordered tail to head.
 */
object LettaBeamPalette {
    /** Green, blue, violet, pink, amber: the multicolour beam ([LettaBeamTone.Spectrum]). */
    val spectrum: List<Color> = listOf(
        Color(red = 0.25f, green = 0.85f, blue = 0.45f),
        Color(red = 0.20f, green = 0.60f, blue = 0.95f),
        Color(red = 0.70f, green = 0.35f, blue = 0.95f),
        Color(red = 1.00f, green = 0.35f, blue = 0.65f),
        Color(red = 1.00f, green = 0.65f, blue = 0.25f),
    )

    /** How faint a single-colour beam's two ends are, so it fades in and out along its length. */
    const val TAIL_ALPHA: Float = 0.3f
}

