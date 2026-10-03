package com.letta.mobile.ui.chat.surface.timeline

/** One row of the list as laid out in a frame, in dp. [top] is measured from the viewport's top edge. */
internal data class RowBounds(val key: String, val top: Float, val height: Float)

/**
 * Everything the harness observed after one Compose frame of the shared paged timeline
 * (letta-mobile-29sxj): what is laid out, whether a spinner is up, where the list is scrolled to
 * and how much of the rows' content was composed to get here.
 */
internal data class UiFrame(
    val index: Int,
    /** The user-visible phase the frame belongs to: open, send, stream, settle. */
    val step: String,
    val rows: List<RowBounds>,
    /** The opening skeleton / mascot, or the older-history footer, is on screen. */
    val spinnerVisible: Boolean,
    val firstVisibleItemIndex: Int,
    val scrollOffset: Int,
    /** Scroll commands the list was given since the previous frame (even ones that moved nothing). */
    val scrollCommands: Int,
    /** Times a timeline row's content was composed (first composition or recomposition) since the previous frame. */
    val rowCompositions: Int,
) {
    val keys: List<String> get() = rows.map { it.key }
    val atNewestEdge: Boolean get() = firstVisibleItemIndex == 0 && scrollOffset == 0

    /** The bottom-most row: the list is reversed, so this is the conversation's newest. */
    val newestKey: String? get() = rows.lastOrNull()?.key

    override fun toString() =
        "#$index [$step] edge=$atNewestEdge($firstVisibleItemIndex/$scrollOffset) spinner=$spinnerVisible " +
            "scrolls=$scrollCommands composed=$rowCompositions rows=" + rows.joinToString { "${it.key}@${it.top}+${it.height}" }
}
