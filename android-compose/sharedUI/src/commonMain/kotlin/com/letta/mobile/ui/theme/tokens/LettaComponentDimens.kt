package com.letta.mobile.ui.theme.tokens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes owned by one shared component each, so call sites pass none of their own. They sit on the
 * LettaDimens grid; they are named here because "the size of a status dot" is a fact about the dot,
 * not about spacing.
 */
object LettaComponentDimens {
    /** A status dot's diameter (LettaStatusDot): every hand-built dot used 8 dp. */
    val statusDot: Dp = 8.dp

    /** A colour swatch's default diameter (LettaColorSwatch). */
    val swatch: Dp = 24.dp

    /** The ring a selected swatch draws (LettaColorSwatch); an unselected one draws a hairline. */
    val swatchSelectedStroke: Dp = 2.dp

    /** The glyph above an empty state's words (LettaEmptyState). */
    val emptyStateIcon: Dp = 40.dp

    /** The working status glyph's ring and spinner stroke (StatusGlyph). */
    val statusGlyphStroke: Dp = 1.5.dp

    /** The shadow an elevated notice casts over the page (the overlay host's banner, LettaInlineNotice). */
    val floatingNoticeElevation: Dp = 4.dp

    /** How far a border beam's soft glow reaches inward from the edge (lettaBorderBeam, Orbit style). */
    val beamGlowOrbit: Dp = 20.dp

    /** The beam's glow reach on small controls (OrbitCompact): icon buttons, avatars, chips. */
    val beamGlowCompact: Dp = 4.dp

    /** The beam's glow reach when it sweeps along the bottom edge (Sweep): search and input bars. */
    val beamGlowSweep: Dp = 10.dp

    /** The beam's crisp core line, drawn where no shader runs (the Canvas fallback). */
    val beamCoreStroke: Dp = 2.dp

    /** The tight halo around the beam's core line in the Canvas fallback. */
    val beamHaloStroke: Dp = 4.dp

    /** The narrowest soft glow the Canvas fallback draws, so a compact beam still reads. */
    val beamMinGlowStroke: Dp = 6.dp
}
