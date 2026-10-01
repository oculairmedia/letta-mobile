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
 * letta-mobile-bglj6.1.9: the Touch composer bar, lifted from the legacy Android composer
 * (feature-chat ChatComposer + designsystem LettaInputBar) so the shared page draws the same bar.
 */
object TouchComposerDimens {
    /** The "+" and send/stop slots: the rail's touch target. */
    val actionTarget: Dp = LettaDimens.Orb.railSlotWidth

    /** The "+" disc inside its target. */
    val attachButton: Dp = LettaSpacingTokens.COMPOSER_ATTACH_BUTTON_SIZE.dp
    val attachIcon: Dp = LettaSpacingTokens.COMPOSER_ATTACH_ICON_SIZE.dp

    /** The send/stop glyph. */
    val actionIcon: Dp = LettaDimens.Space.xl

    /** The bar's top corners at rest, and once engaged (focused, typed in, holding an image). */
    val restingCorner: Dp = LettaDimens.Space.xxl
    val engagedCorner: Dp = LettaDimens.Space.xl

    /**
     * How far the bar's rounded top reaches above its straight sides: its larger corner. What is
     * behind the bar (the page, or the canvas on the canvas page) runs on under this band, so the
     * corners show it.
     */
    val cornerReach: Dp = maxOf(restingCorner, engagedCorner)

    /** A hair of tonal lift once engaged. */
    val engagedElevation: Dp = LettaDimens.Space.hair
    val restingElevation: Dp = 0.dp

    /** The bar's vertical padding at rest; it eases to [compactVerticalPadding] as the keyboard rises. */
    val restingVerticalPadding: Dp = LettaDimens.Space.xl
    val compactVerticalPadding: Dp = LettaDimens.Space.md

    /** Keyboard inset, in px, at which the bar is fully compact. */
    const val imeInsetForCompactPx: Float = 96f

    val horizontalPadding: Dp = LettaSpacingTokens.SM.dp
    val itemSpacing: Dp = LettaSpacingTokens.XS.dp

    /** The field's own inner padding (Material's TextField: 16 dp on each side). */
    val fieldPadding: Dp = LettaDimens.Space.lg

    /** The "+" shrinks a touch while pressed. */
    const val pressedScale: Float = 0.96f

    /** Stop draws smaller than Send and beats gently while the run is live. */
    const val stopScale: Float = 0.7f
    const val stopPulseScale: Float = 1.04f
    const val stopPulseMillis: Int = 800

    /** The page's companion above the bar while the agent works (legacy ChatComposerCompanion). */
    val companion: Dp = 64.dp

    /** The action sheet's list caps at this height and scrolls. */
    val sheetListMaxHeight: Dp = 320.dp

    /** The leading icon of an action sheet row. */
    val sheetIcon: Dp = LettaDimens.Space.xl

    /** An action sheet row's tonal lift at rest and pressed. */
    val sheetItemElevation: Dp = LettaElevationTokens.ACTION_SHEET_ITEM_RESTING.dp
    val sheetItemCorner: Dp = LettaShapeTokens.ACTION_RADIUS.dp
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
