package com.letta.mobile.ui.canvas

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Screen regions that own Ctrl/Cmd + wheel. A host that turns that gesture into UI zoom (the
 * desktop chat font scale) asks here first and leaves the event alone inside a registered region,
 * so a board underneath can zoom itself instead of the whole window growing.
 *
 * Regions are reported as providers of their current bounds in the host's coordinate space, so
 * a board that moves or resizes stays accurate without re-registering.
 */
class WheelZoomRegions {
    private val regions = mutableListOf<() -> Rect?>()

    /** Registers [bounds]; call the returned function to remove it again. */
    fun register(bounds: () -> Rect?): () -> Unit {
        regions += bounds
        return { regions -= bounds }
    }

    fun contains(position: Offset): Boolean = regions.any { it()?.contains(position) == true }
}

/** The host's registry, or null where no host turns Ctrl + wheel into UI zoom. */
val LocalWheelZoomRegions = compositionLocalOf<WheelZoomRegions?> { null }
