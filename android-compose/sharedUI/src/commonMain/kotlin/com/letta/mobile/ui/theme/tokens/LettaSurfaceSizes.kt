package com.letta.mobile.ui.theme.tokens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes owned by one shared surface each (a side pane, the overlay layer, a roster row), named so the
 * surface's own file carries no literal. Like LettaComponentDimens, each is a fact about that surface,
 * not a point on the spacing scale; a page that needs one of these sizes reads it from here.
 */
object LettaSurfaceSizes {
    /** The tallest an inline image or video in the timeline draws; it fits whole inside, letterboxed. */
    val inlineMediaMaxHeight: Dp = 400.dp

    /** A video's frame before its own size is known (its poster): 16:9. */
    const val inlineVideoAspectRatio: Float = 16f / 9f

    /** A side pane's width when it first opens (LettaSidePane): wide enough for a form beside the chat. */
    val sidePaneWidth: Dp = 540.dp

    /** The narrowest a person can drag a side pane: one readable column. */
    val sidePaneMinWidth: Dp = 320.dp

    /** The widest a person can drag a side pane before it would crowd out the content beside it. */
    val sidePaneMaxWidth: Dp = 1100.dp

    /** How far a roster row slides before a swipe commits its action (RosterListRow). */
    val rosterSwipeThreshold: Dp = 72.dp

    /**
     * The widest a wide block in a chat reply (a table) grows when it breaks out of the reading column
     * into the spare width beside it on a wide window. Past this it scrolls sideways instead.
     */
    val wideContentMaxWidth: Dp = 1440.dp

    /** The margin a broken-out block keeps from each side of the timeline, beyond the list's own gutter. */
    val wideContentGutter: Dp = 16.dp

    /** The widest a snackbar grows on a wide window (the overlay layer). */
    val snackbarMaxWidth: Dp = 600.dp

    /** The widest a top banner grows on a wide window (the overlay layer). */
    val bannerMaxWidth: Dp = 720.dp

    /** The narrowest a text-selection toolbar's action (Copy, Quote) draws, as the platform's own toolbar does. */
    val selectionActionMinWidth: Dp = 64.dp

    /** A shape choice in the mascot picker: the paused character, large enough to read its pose. */
    val mascotPickerTile: Dp = 52.dp

    /**
     * The least distance a mascot's gaze treats a target as away from it, so a pointer resting on the
     * mascot itself still reads as "looking at you" rather than spinning the eyes.
     */
    val mascotGazeMinReach: Dp = 360.dp

    /** The drop target's picture glyph (ImageDropOverlay). */
    val dropGlyph: Dp = 44.dp

    /** How strongly a side pane's resize strip tints `outlineVariant`, so the grab area reads as a band. */
    const val resizeHandleAlpha: Float = 0.35f

    /** The glyph that heads a pairing result (paired, failed, accepted). */
    val pairingResultGlyph: Dp = 24.dp

    /** The tallest the "Choose agents" match list grows before it scrolls: about five rows. */
    val agentPickerListMaxHeight: Dp = 240.dp

    /** A mascot shape tile's `surfaceVariant` fill when chosen and when not. */
    const val mascotTileSelectedAlpha: Float = 0.9f
    const val mascotTileAlpha: Float = 0.4f

    /** The mascot loading ring's track: `primary` at this strength under the moving arc. */
    const val mascotLoadingTrackAlpha: Float = 0.18f

    /** The text-selection toolbar's tonal tint and shadow, as the platform's own floating toolbar draws them. */
    val selectionToolbarTonalElevation: Dp = 3.dp
    val selectionToolbarShadow: Dp = 6.dp

    /** How strongly a full-window drop target dims the content under it (ImageDropOverlay). */
    const val dropScrimAlpha: Float = 0.55f
}
