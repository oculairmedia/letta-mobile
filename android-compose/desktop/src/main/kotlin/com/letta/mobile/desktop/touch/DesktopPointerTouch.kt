package com.letta.mobile.desktop.touch

import java.awt.Window
import java.util.Collections
import java.util.WeakHashMap

/**
 * Windows whose fingers are owned by pointer or touch frames.
 *
 * The touch-pan shim and those frames would otherwise both scroll the same swipe,
 * and AWT would also turn the touch into mouse clicks. A window in this set has
 * its touch pan and its touch-caused mouse swallowed. A mouse or a pen is left alone.
 * Ownership is per window: a finger on the canvas must not swallow the other window.
 */
internal object DesktopPointerTouch {
    private val owners = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Window, Boolean>()))

    fun markOwned(window: Window) {
        owners.add(window)
    }

    fun ownsFingers(window: Window): Boolean = window in owners

    fun release(window: Window) {
        owners.remove(window)
    }
}
