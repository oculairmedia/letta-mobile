package com.letta.mobile.ui.search

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The search surfaces' panel geometry: reading-width and list-height decisions for each container a
 * search can be drawn in (an anchored dropdown, a centred popover, the header's anchored pane), not
 * points on the spacing scale.
 */
internal object LettaSearchDimens {
    /** A dropdown's narrowest and widest: one result row with its meta, no wider than a menu. */
    val dropdownMinWidth: Dp = 280.dp
    val dropdownMaxWidth: Dp = 360.dp

    /** How tall a dropdown's results grow before they scroll. */
    val dropdownMaxResultsHeight: Dp = 320.dp

    /** A centred popover's narrowest and widest: a field and its results as a reading column. */
    val popoverMinWidth: Dp = 520.dp
    val popoverMaxWidth: Dp = 640.dp

    /** How tall a popover's results grow before they scroll. */
    val popoverMaxResultsHeight: Dp = 420.dp

    /** A recent entry's tile: an orb over a one-line name. */
    val recentTileWidth: Dp = 64.dp

    /** The header's anchored search pane: its narrowest and widest, its results' and its own tallest. */
    val anchoredMinWidth: Dp = 280.dp
    val anchoredMaxWidth: Dp = 380.dp
    val anchoredMaxResultsHeight: Dp = 280.dp
    val anchoredMaxPaneHeight: Dp = 480.dp
}
