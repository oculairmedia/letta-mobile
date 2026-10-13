package com.letta.mobile.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf

/**
 * What a held row does when the finger then moves on (letta-mobile-ckmub.3.25): a touch row menu
 * opens on a long-press, and a list that reorders (RosterList) lets the same hold become a drag.
 * The touch [LettaHost.rowMenu] reads [LocalRowHoldDrag]: once the held pointer travels past the
 * touch slop it closes its menu and reports the drag here, in pixels along the list.
 */
@Stable
interface RowHoldDrag {
    fun onStart()

    fun onDrag(deltaY: Float)

    fun onEnd()
}

/** The drag the row under this point offers; null for rows that do not move. */
val LocalRowHoldDrag = compositionLocalOf<RowHoldDrag?> { null }
