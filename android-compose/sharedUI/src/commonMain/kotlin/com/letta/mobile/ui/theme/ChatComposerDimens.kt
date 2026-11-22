package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The few sizes the shared chat composer needs that are not on the
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

    val permissionModePopoverWidth: Dp = 280.dp
    val contextPopoverWidth: Dp = 330.dp

    /** The share column in the context breakdown, wide enough for "100%". */
    val contextShareColumn: Dp = 52.dp

    /** The `@` mention suggestions cap at this height and scroll. */
    val mentionPickerMaxHeight: Dp = 320.dp

    /** Swipe-up-to-canvas: commit past this distance... */
    val swipeDistanceThreshold: Dp = 32.dp

    /** ...or this upward velocity per second at release. */
    val swipeVelocityThresholdPerSecond: Dp = 800.dp

    /** Upward travel before the swipe claims the gesture from the card. */
    val swipeSlop: Dp = 12.dp

    /** The attachment preview's scrim. */
    const val previewScrimAlpha: Float = 0.72f

    /** Hint copy under the composer reads one step quieter than labels. */
    const val hintAlpha: Float = 0.92f
}

/**
 * The Touch composer bar, lifted from the legacy Android composer
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

    /**
     * The narrowest the prompt field may get beside the bar's trailing actions; a bar that would
     * squeeze it further moves the voice-mode button into the "+" sheet.
     */
    val minFieldWidth: Dp = 150.dp

    /** The "+" shrinks a touch while pressed. */
    const val pressedScale: Float = 0.96f

    /** The page's companion above the bar while the agent works (legacy ChatComposerCompanion). */
    val companion: Dp = 64.dp

    /** The action sheet's list caps at this height and scrolls. */
    val sheetListMaxHeight: Dp = 320.dp

    /** The leading icon of an action sheet row. */
    val sheetIcon: Dp = LettaDimens.Space.xl

    /** An action sheet row's tonal lift and corner at rest, and pressed (legacy ActionSheetItem). */
    val sheetItemElevation: Dp = LettaElevationTokens.ACTION_SHEET_ITEM_RESTING.dp
    val sheetItemCorner: Dp = LettaShapeTokens.ACTION_RADIUS.dp
    val sheetItemPressedElevation: Dp = LettaElevationTokens.ACTION_SHEET_ITEM_PRESSED.dp
    val sheetItemPressedCorner: Dp = LettaShapeTokens.LIST_RADIUS.dp
}
