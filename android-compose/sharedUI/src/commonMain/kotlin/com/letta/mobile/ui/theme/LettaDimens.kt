package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one dimension scale for Letta UI, on desktop and Android.
 *
 * WHY THIS EXISTS
 * ---------------
 * Before this file there were 2,632 dimension literals across 234 UI files and
 * 80 distinct dp values — 1 through 20 near-contiguously, plus 22/24/26/28/30/
 * 31/32/34/36/38/40/44/46/48. Nothing forced two controls on the same row to
 * agree, so every surface drifted independently and fixing one screen never
 * reached the next.
 *
 * HOW THE SCALE WAS CHOSEN
 * ------------------------
 * Measured, not invented. `scripts/ui-design-lint.py --rule=raw-dimension
 * --format=json` counts every literal in the tree. The 4dp grid already
 * accounted for the large majority of real usage:
 *
 *     8.dp  490      16.dp 335      12.dp 302      4.dp  264
 *     24.dp  51      32.dp  30      20.dp  60      48.dp  24
 *
 * while the off-grid values are the drift, and are what the alignment pass
 * snaps to their nearest token:
 *
 *     6.dp  183      2.dp  135      10.dp 126      14.dp 105
 *     18.dp  60      28.dp  42      3/5/7/9/13/22/34/36/44…
 *
 * So: a 4dp grid for space, named sizes for controls, and nothing else. This
 * file is deliberately small. A token set with forty entries is the same
 * free-for-all with extra steps — if a value is not here, the right move is
 * almost always to use the nearest token, not to add one.
 *
 * EXEMPTIONS
 * ----------
 * `0.dp` and `1.dp` stay legal at call sites and are not tokenised. They mean
 * "no inset" and "hairline" — structural facts rather than points on a scale.
 *
 * ENFORCEMENT
 * -----------
 * `scripts/ui-design-lint.py` (fast, no Gradle) and `MobileDesignSystemRules`
 * in :quality:detekt-rules (CI) both treat this file as the definition site, so
 * the literals below are the only ones in the codebase that are allowed to be
 * literals. If this file moves, update TOKEN_FILE_MARKERS in BOTH.
 *
 * Usage: `LettaDimens.Space.md`, `LettaDimens.Control.iconButton`.
 */
object LettaDimens {

    /**
     * The 4dp spacing grid: padding, gaps, arrangement spacing.
     *
     * [hair] is the deliberate exception — a 2dp gap that reads as "touching
     * but not overlapping", used between an icon and its label and between
     * stacked list rows. It is on the grid's half-step because at 4dp those
     * pairs visibly separate into two elements.
     */
    object Space {
        val hair: Dp = 2.dp
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 16.dp
        val xl: Dp = 24.dp
        val xxl: Dp = 32.dp
    }

    /**
     * Control sizes. These are the numbers that make two things on one row look
     * like they belong together, so they matter more than the spacing scale.
     *
     * Icon buttons come in three, and the choice is about role, not taste:
     * [iconButtonSm] is a hit target around a glyph inside another control (a
     * tab's close cross); [iconButton] is a control in its own right sitting in
     * a bar; [iconButtonLg] is a primary navigation target (the rail).
     */
    object Control {
        /** Hit target around a glyph that lives inside another control. */
        val iconButtonSm: Dp = 20.dp

        /** A control in its own right, in a toolbar or composer. */
        val iconButton: Dp = 28.dp

        /** A primary navigation target, e.g. the rail's action icons. */
        val iconButtonLg: Dp = 34.dp

        /** Glyph inside [iconButtonSm]. */
        val iconSm: Dp = 13.dp

        /** Glyph inside [iconButton] and [iconButtonLg]. */
        val icon: Dp = 18.dp

        /** Single-line text field / chip height. */
        val fieldHeight: Dp = 32.dp

        /** Send button and other emphasised round actions. */
        val actionButton: Dp = 38.dp
    }

    /** Agent orb sizes. The rail slot is wider than the orb so a selection marker fits beside it. */
    object Orb {
        val sm: Dp = 24.dp
        val md: Dp = 34.dp
        val lg: Dp = 40.dp
        val railSlotWidth: Dp = 48.dp
        val railSlotHeight: Dp = 44.dp
    }

    /**
     * Corner radii. Scaled to what they wrap: a chip and a dialog should not
     * share a radius or the dialog reads as an oversized chip.
     */
    object Radius {
        /** Chips, small badges. */
        val sm: Dp = 8.dp

        /** Cards, list rows, orbs. */
        val md: Dp = 10.dp

        /** Composer, dialogs, popups. */
        val lg: Dp = 14.dp
    }

    /**
     * Strokes.
     *
     * [hairline] is the ONLY border width. A boundary is drawn by exactly one
     * side — two adjacent hairlines read as one thick, slightly wrong border,
     * which is the bug that had the chat pane drawing its own left edge a pixel
     * from the rail divider's line.
     */
    object Stroke {
        val hairline: Dp = 1.dp
    }

    /**
     * Pane geometry for surfaces that switch between a phone sheet and a
     * side panel: at or above [wideBreakpoint] a detail pane docks beside the
     * content at [sidePanelWidth]; below it the pane overlays as a sheet.
     */
    object Pane {
        val sidePanelWidth: Dp = 380.dp
        val wideBreakpoint: Dp = 720.dp

        /** Longest line a graph node label may take before it ellipsizes. */
        val nodeLabelMaxWidth: Dp = 120.dp

        /** Smallest height an in-pane multi-line editor collapses to. */
        val editorMinHeight: Dp = 160.dp
    }

    /**
     * Content alpha floors.
     *
     * [disabled] is 0.6, not the 0.38 Material suggests, because the muted
     * content roles (`onSurfaceVariant`) are *already* dimmed relative to
     * `onSurface`. Dimming them again puts a disabled control under the
     * contrast it still needs to read as a control on a dark theme. 115 call
     * sites currently violate this; `LowContentAlpha` catches them.
     */
    object Alpha {
        const val disabled: Float = 0.6f

        /** Dividers and hairlines against a surface. */
        const val hairline: Float = 0.5f
    }

    /**
     * Type sizes, for the few places a size is set directly rather than taken
     * from a `MaterialTheme.typography` role.
     *
     * Prefer a typography role. These exist for dense, non-prose UI — timestamps,
     * badge counts, code gutters — where the type scale's smallest role is still
     * too large.
     */
    object Type {
        val micro: TextUnit = 10.sp
        val caption: TextUnit = 12.sp
    }
}
