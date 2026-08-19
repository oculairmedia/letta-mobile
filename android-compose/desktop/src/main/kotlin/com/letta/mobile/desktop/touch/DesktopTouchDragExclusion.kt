package com.letta.mobile.desktop.touch

import java.awt.Rectangle
import java.awt.Window
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Registry of screen-space regions inside a window that [DesktopWindowsTouchInput]
 * must never translate from a touch drag into synthetic wheel scroll.
 *
 * The motivating case is Nucleus's title bar (`DesktopJewelWindow.kt`): its
 * drag-to-move handling — either Compose's own `pointerInput`-based
 * `windowDragHandler` (the non-native fallback) or the native path, which
 * still depends on Compose seeing an ordinary `PointerEventType.Press` before
 * it calls `nativeStartDrag` — needs a normal, immediate press+drag stream.
 * [DesktopWindowsTouchInput] withholds the press and swallows the drag for
 * every touch gesture so it can replay it as wheel events; a gesture that
 * starts inside a published region must instead pass straight through.
 *
 * Bounds are recorded in absolute screen pixels — the same space as
 * [java.awt.event.MouseEvent.getXOnScreen]/[java.awt.event.MouseEvent.getYOnScreen]
 * — via Compose's `LayoutCoordinates.positionOnScreen()`. That sidesteps two
 * traps: Compose's px space is density-scaled and need not match the pixel
 * space of whichever AWT component happens to be the touch event's source,
 * and that source component's origin need not coincide with the window's.
 * Screen coordinates are the one space both sides already agree on.
 *
 * Keyed by identity (via a [WeakHashMap], so a closed window's entry is
 * reclaimed automatically) rather than a single global rect, so a second
 * window can register its own excluded region without colliding with the
 * first. Generic in the key type so unit tests can exercise it with a plain
 * [Any] stand-in instead of a real [Window] — constructing an AWT `Window`
 * unconditionally checks [java.awt.GraphicsEnvironment.isHeadless] and throws
 * on a display-less test runner, whereas a bare `Any()` never touches AWT at
 * all. The production instance below is still keyed by the real [Window].
 */
internal class DesktopTouchDragExclusionRegistry<K : Any> {
    private val bounds: MutableMap<K, Rectangle> = Collections.synchronizedMap(WeakHashMap())

    /** Publishes the excluded region for [key], or clears it when [screenBounds] is null. */
    fun publish(key: K, screenBounds: Rectangle?) {
        if (screenBounds == null) {
            bounds.remove(key)
        } else {
            bounds[key] = screenBounds
        }
    }

    /**
     * True when ([screenX], [screenY]) falls inside [key]'s published region.
     * A window this registry has never heard from — or one whose publisher
     * hasn't composed yet — degrades to "not excluded" rather than throwing,
     * so a lookup miss never blocks a legitimate scroll.
     */
    fun contains(key: K, screenX: Int, screenY: Int): Boolean =
        bounds[key]?.contains(screenX, screenY) ?: false
}

/** Process-wide registry shared by the title bar (publisher) and the touch shim (reader). */
internal val DesktopTouchDragExclusion = DesktopTouchDragExclusionRegistry<Window>()

/**
 * Builds the screen-space [Rectangle] to publish for a title-bar-shaped
 * region, or null when [screenX]/[screenY] are not finite.
 *
 * The non-finite case is real, not defensive paranoia: Compose's
 * `LayoutCoordinates.positionOnScreen()` returns `Offset.Unspecified` — NaN
 * in both components — while a layout is not yet attached to a screen,
 * which happens during a window's very first composition pass. Rounding a
 * NaN throws `IllegalArgumentException` and crashed app startup outright
 * before this guard existed.
 *
 * Returning null (rather than clamping to some fallback rectangle) is
 * deliberate: the caller is expected to feed this straight into
 * [DesktopTouchDragExclusionRegistry.publish], where null *clears* any
 * previously published bounds. A stale rectangle left over content is worse
 * than the title bar briefly reporting "not excluded" for one frame — a
 * missed touch-drag-to-move is recoverable the moment the next layout pass
 * republishes real bounds, whereas a wrong exclusion rectangle sitting over
 * ordinary content would silently and durably break scrolling there.
 */
internal fun screenExclusionRectOrNull(screenX: Float, screenY: Float, width: Int, height: Int): Rectangle? {
    if (!screenX.isFinite() || !screenY.isFinite()) return null
    return Rectangle(screenX.roundToInt(), screenY.roundToInt(), width, height)
}
