package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * letta-mobile-bglj6.1: the few sizes the shared chat composer needs that are not on the
 * [LettaDimens] scale. Each is a layout fact of the composer (a breakpoint, a popover width,
 * a gesture threshold), not a spacing choice; spacing still comes from [LettaDimens.Space].
 */
object ChatComposerDimens {
    /** The prompt field grows with its text up to this height, then scrolls. */
    val promptMaxHeight: Dp = 120.dp

    /** The docked bar's single-line field never grows past this. */
    val dockedFieldMaxHeight: Dp = 40.dp

    /** Below this width the control row keeps only its primary controls. */
    val controlsWrapBreakpoint: Dp = 540.dp

    /** A staged image's thumbnail in the prompt card. */
    val attachmentThumbnail: Dp = 64.dp

    /** How far above its chip a composer popover sits. */
    val popoverGap: Dp = 6.dp

    val effortPopoverWidth: Dp = 230.dp
    val contextPopoverWidth: Dp = 330.dp

    /** The share column in the context breakdown, wide enough for "100%". */
    val contextShareColumn: Dp = 52.dp

    val modelSheetWidth: Dp = 560.dp
    val modelSheetMaxHeight: Dp = 540.dp
    val modelListMaxHeight: Dp = 420.dp

    /** Swipe-up-to-canvas: commit past this distance... */
    val swipeDistanceThreshold: Dp = 32.dp

    /** ...or this upward velocity per second at release. */
    val swipeVelocityThresholdPerSecond: Dp = 800.dp

    /** Upward travel before the swipe claims the gesture from the card. */
    val swipeSlop: Dp = 12.dp

    /** The model sheet's dimming scrim over the page. */
    const val scrimAlpha: Float = 0.45f

    /** The attachment preview's scrim. */
    const val previewScrimAlpha: Float = 0.72f

    /** Hint copy under the composer reads one step quieter than labels. */
    const val hintAlpha: Float = 0.92f
}

/**
 * Fixed hues for the context-window breakdown: it needs one distinguishable colour per
 * section, and the M3 scheme only offers a handful of accents. Mid-saturation so they hold
 * up on light and dark surfaces. Lifted from desktop's DesktopComposerContextUsage.
 */
object ChatComposerColors {
    val contextSystem: Color = Color(0xFFF0A030)
    val contextToolDefinitions: Color = Color(0xFF4C8DFF)
    val contextToolRules: Color = Color(0xFF7C6CF0)
    val contextCoreMemory: Color = Color(0xFF34C08A)
    val contextMemoryFiles: Color = Color(0xFF2FB0C7)
    val contextDirectories: Color = Color(0xFFB07CF0)
    val contextSummaryMemory: Color = Color(0xFFE06CB0)
    val contextExternalSummary: Color = Color(0xFFD9534F)
    val contextMessages: Color = Color(0xFFE8622F)
    val contextUnitemised: Color = Color(0xFF8A93A6)
}
