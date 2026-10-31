package com.letta.mobile.ui.theme.graph

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tokens for node-link graphs (the memory graph today; any future graph view reuses them): strokes, rings and label chips are drawn
 * on a Canvas, so they live here rather than as literals in the draw pass. The alphas are the
 * emphasis steps a graph uses to focus one node and dim the rest.
 */
object GraphDimens {
    /** A resting edge; a focused one is [edgeFocused]. */
    val edge: Dp = 1.25.dp
    val edgeFocused: Dp = 2.25.dp

    /** The soft glow around the focused node, beyond its radius. */
    val halo: Dp = 7.dp

    /** The hub (agent) node's rim. */
    val rootRim: Dp = 2.5.dp

    /** A block's fill-against-limit arc: its gap from the node and its stroke. */
    val usageGap: Dp = 4.dp
    val usageStroke: Dp = 2.dp

    /** A channel's live-status dot. */
    val statusDot: Dp = 3.5.dp

    /** The selected node's ring: its gap from the node and its stroke. */
    val selectionGap: Dp = 8.dp
    val selectionRing: Dp = 2.dp

    /** A node label: its gap from the node and its chip's padding. */
    val labelGap: Dp = 5.dp
    val labelChipPadX: Dp = 6.dp
    val labelChipPadY: Dp = 2.dp

    /** A resting label's width cap; the focused node's label may run to [labelFocusedWidth]. */
    val labelWidth: Dp = 168.dp
    val labelFocusedWidth: Dp = 280.dp

    /** Room around a fitted graph for labels hung outside nodes and the overlays. */
    val fitPadding: Dp = 88.dp

    /** An unfocused node or label while another node has focus. */
    const val DIMMED_ALPHA: Float = 0.22f

    /** An edge not touching the focused node. */
    const val DIMMED_EDGE_ALPHA: Float = 0.25f

    /** An edge touching the focused node, over the far node's colour. */
    const val FOCUSED_EDGE_ALPHA: Float = 0.85f

    /** The focused node's glow. */
    const val HALO_ALPHA: Float = 0.2f

    /** A resting edge over the outline colour. */
    const val EDGE_ALPHA: Float = 0.45f

    /** A label chip over the surface colour, so edges passing under it stay quiet. */
    const val LABEL_CHIP_ALPHA: Float = 0.82f
}
