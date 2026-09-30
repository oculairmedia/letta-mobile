package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ak1.drawbox.presentation.PanFling
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One or two fingers on the board.
 *
 * The contact-down sample is not where the finger is. The first move places it.
 * One finger is the mouse: past the slop, and once a second finger has had time to
 * land, it holds the button where it was placed and drags from there, so the current
 * tool draws, selects, or moves. Two fingers pan with their midpoint and pinch about it.
 * A finger that never leaves the slop is a tap. A second tap close by opens the text of
 * the shape under it, or zooms in on open board.
 * A finger held still is a long press: it adds what it is on to the selection, and a
 * drag after it draws a selection box, whatever the tool.
 */
internal class CanvasFingerGesture(
    private val slopPx: Float = SLOP_PX,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val contacts = mutableMapOf<Int, Contact>()
    private var cursorId: Int? = null
    private var marqueeId: Int? = null
    private var pinching = false
    private var gesturePinched = false
    private var prevDistance = 0f
    private var prevCentroid = Offset.Zero
    private var centroidOrigin = Offset.Zero
    private var centroidLast = Offset.Zero
    private var zoomProduct = 1f
    private var lastTapMillis: Long? = null
    private var lastTapAt = Offset.Zero

    fun offer(contact: Int, phase: CanvasBoardTouchSample.Phase, x: Float, y: Float, onBoard: Boolean): CanvasBoardTouchOutcome =
        when (phase) {
            CanvasBoardTouchSample.Phase.DOWN -> down(contact)
            CanvasBoardTouchSample.Phase.MOVE -> move(contact, x, y, onBoard)
            CanvasBoardTouchSample.Phase.UP -> up(contact)
            CanvasBoardTouchSample.Phase.CANCEL -> cancel(contact)
        }

    private fun down(id: Int): CanvasBoardTouchOutcome {
        contacts[id] = Contact()
        // A finger landing while another one holds the mouse or a selection box is not a new gesture.
        if (cursorId != null || marqueeId != null) {
            return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, emptyList())
        }
        return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, listOf(CanvasFingerEffect.Catch))
    }

    private fun move(id: Int, x: Float, y: Float, onBoard: Boolean): CanvasBoardTouchOutcome {
        val contact = contacts[id] ?: return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, emptyList())
        val point = Offset(x, y)
        if (!contact.placed) {
            contact.placed = true
            contact.onBoard = onBoard
            contact.anchor = point
            contact.last = point
            contact.previous = point
            contact.placedMillis = clock()
            // The mouse finger keeps the board; a second finger on it only rides along.
            if (cursorId != null || marqueeId != null) contact.spent = true
            return CanvasBoardTouchOutcome(
                if (onBoard) CanvasBoardTouchResult.Consumed else CanvasBoardTouchResult.Ignored,
                emptyList(),
            )
        }
        if (!contact.onBoard) return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, emptyList())
        contact.previous = contact.last
        contact.last = point
        val cursor = cursorId
        if (cursor == id) return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Drag(x, y), emptyList())
        if (cursor != null) return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, emptyList())
        val marquee = marqueeId
        if (marquee == id) return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, marqueeTo(contact))
        if (marquee != null) return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, emptyList())
        val fingers = onBoardFingers()
        if (fingers.size >= 2) return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, twoFingers(fingers))
        return oneFinger(id, contact)
    }

    /** Past the slop and the wait for a second finger, one finger holds the mouse. */
    private fun oneFinger(id: Int, finger: Contact): CanvasBoardTouchOutcome {
        if (pinching) {
            // A pinch that lost a finger is over. The finger left behind must not start drawing.
            pinching = false
            finger.spent = true
        }
        val fromAnchor = finger.last - finger.anchor
        val ready = !finger.spent && !finger.longPressed &&
            hypot(fromAnchor.x, fromAnchor.y) > slopPx &&
            clock() - finger.placedMillis >= SECOND_FINGER_WAIT_MILLIS
        if (!ready) return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, emptyList())
        cursorId = id
        lastTapMillis = null
        return CanvasBoardTouchOutcome(
            CanvasBoardTouchResult.Press(finger.anchor.x, finger.anchor.y, finger.last.x, finger.last.y),
            emptyList(),
        )
    }

    /**
     * The one finger on the board has been still for [LONG_PRESS_MILLIS]. Called on a timer,
     * because a finger that does not move sends nothing. Null when it moved, lifted, or is not alone.
     */
    fun longPress(): List<CanvasFingerEffect>? {
        if (cursorId != null || marqueeId != null) return null
        val entry = contacts.entries.singleOrNull() ?: return null
        val finger = entry.value
        if (!finger.placed || !finger.onBoard || finger.spent || finger.longPressed) return null
        if (clock() - finger.placedMillis < LONG_PRESS_MILLIS) return null
        val moved = finger.last - finger.anchor
        if (hypot(moved.x, moved.y) > slopPx) return null
        finger.longPressed = true
        marqueeId = entry.key
        lastTapMillis = null
        return listOf(CanvasFingerEffect.LongPress(finger.anchor.x, finger.anchor.y))
    }

    /** True while one finger is down, still, and could yet become a long press. */
    fun mayLongPress(): Boolean {
        if (cursorId != null || marqueeId != null) return false
        val finger = contacts.values.singleOrNull() ?: return false
        if (finger.spent || finger.longPressed) return false
        if (!finger.placed) return true
        if (!finger.onBoard) return false
        val moved = finger.last - finger.anchor
        return hypot(moved.x, moved.y) <= slopPx
    }

    /** Past the slop, a long-pressed finger draws the selection box from where it was held. */
    private fun marqueeTo(finger: Contact): List<CanvasFingerEffect> {
        val moved = finger.last - finger.anchor
        if (!finger.boxing && hypot(moved.x, moved.y) <= slopPx) return emptyList()
        finger.boxing = true
        return listOf(
            CanvasFingerEffect.Marquee(finger.anchor.x, finger.anchor.y, finger.last.x, finger.last.y, commit = false),
        )
    }

    /** The Ink copy of a finger left. It must not tap or coast; the pointer frame is the finger. */
    private fun cancel(id: Int): CanvasBoardTouchOutcome {
        val contact = contacts.remove(id) ?: return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, emptyList())
        if (marqueeId == id) {
            marqueeId = null
            contacts.values.forEach { it.spent = true }
            val clear = if (contact.boxing) listOf(CanvasFingerEffect.ClearMarquee) else emptyList()
            return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, clear)
        }
        if (cursorId == id) {
            cursorId = null
            contacts.values.forEach { it.spent = true }
            return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Release, emptyList())
        }
        return afterLift()
    }

    private fun up(id: Int): CanvasBoardTouchOutcome {
        val contact = contacts.remove(id) ?: return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, emptyList())
        if (cursorId == id) {
            cursorId = null
            contacts.values.forEach { it.spent = true }
            val travel = contact.last - contact.anchor
            return CanvasBoardTouchOutcome(
                CanvasBoardTouchResult.Release,
                listOf(CanvasFingerEffect.End("cursor", travel.x, travel.y, 1f, fling = false)),
            )
        }
        if (marqueeId == id) return liftLongPress(contact)
        if (!contact.placed || !contact.onBoard) {
            return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, emptyList())
        }
        if (onBoardFingers().isNotEmpty() || cursorId != null || marqueeId != null) return afterLift()
        val travel = if (gesturePinched) centroidLast - centroidOrigin else contact.last - contact.anchor
        val kind = when {
            gesturePinched -> "pinch"
            contact.spent -> "rest"
            else -> "tap"
        }
        val fling = gesturePinched && pinchWasAPan()
        val end = CanvasFingerEffect.End(kind, travel.x, travel.y, if (gesturePinched) zoomProduct else 1f, fling)
        pinching = false
        gesturePinched = false
        zoomProduct = 1f
        if (kind != "tap") {
            val effects = if (fling) listOf(CanvasFingerEffect.Release, end) else listOf(end)
            return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, effects)
        }
        return tap(contact.anchor, end)
    }

    /** A held finger lifted: the box it drew selects what it covers. A plain long press already selected. */
    private fun liftLongPress(contact: Contact): CanvasBoardTouchOutcome {
        marqueeId = null
        contacts.values.forEach { it.spent = true }
        val travel = contact.last - contact.anchor
        val box = if (contact.boxing) {
            listOf(
                CanvasFingerEffect.Marquee(contact.anchor.x, contact.anchor.y, contact.last.x, contact.last.y, commit = true),
            )
        } else {
            emptyList()
        }
        val kind = if (contact.boxing) "select-box" else "long-press"
        val end = CanvasFingerEffect.End(kind, travel.x, travel.y, 1f, fling = false)
        return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, box + end)
    }

    /** A second tap near the first, soon after it, is a double tap there instead of another click. */
    private fun tap(at: Offset, end: CanvasFingerEffect.End): CanvasBoardTouchOutcome {
        val now = clock()
        val previous = lastTapMillis
        val near = hypot(at.x - lastTapAt.x, at.y - lastTapAt.y) <= DOUBLE_TAP_SLOP_PX
        if (previous != null && now - previous <= DOUBLE_TAP_MILLIS && near) {
            lastTapMillis = null
            return CanvasBoardTouchOutcome(
                CanvasBoardTouchResult.Consumed,
                listOf(CanvasFingerEffect.DoubleTap(at.x, at.y), end.copy(kind = "double-tap")),
            )
        }
        lastTapMillis = now
        lastTapAt = at
        return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Tap(at.x, at.y), listOf(end))
    }

    /** Fingers are still down after one left. Two keep pinching; one left alone does nothing. */
    private fun afterLift(): CanvasBoardTouchOutcome {
        val still = onBoardFingers()
        if (still.size >= 2) {
            rebase(still)
            return CanvasBoardTouchOutcome(
                CanvasBoardTouchResult.Consumed,
                listOf(CanvasFingerEffect.Arm(prevCentroid.x, prevCentroid.y)),
            )
        }
        pinching = false
        still.forEach { it.spent = true }
        if (contacts.isNotEmpty()) return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, emptyList())
        gesturePinched = false
        zoomProduct = 1f
        return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, listOf(CanvasFingerEffect.Catch))
    }

    private fun twoFingers(fingers: List<Contact>): List<CanvasFingerEffect> {
        fingers.forEach { it.spent = true }
        if (!pinching) {
            gesturePinched = true
            beginPinch(fingers)
            return listOf(CanvasFingerEffect.Arm(prevCentroid.x, prevCentroid.y))
        }
        return pinch(fingers[0], fingers[1])
    }

    private fun pinch(first: Contact, second: Contact): List<CanvasFingerEffect> {
        val a = first.last
        val b = second.last
        val distance = hypot(a.x - b.x, a.y - b.y)
        val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val pan = centroid - prevCentroid
        val effects = mutableListOf<CanvasFingerEffect>(
            CanvasFingerEffect.Pan(pan.x, pan.y),
            CanvasFingerEffect.Track(centroid.x, centroid.y),
        )
        centroidLast = centroid
        if (prevDistance > 1f && distance > 1f) {
            val factor = distance / prevDistance
            zoomProduct *= factor
            effects.add(CanvasFingerEffect.Zoom(factor, centroid.x, centroid.y))
        }
        prevDistance = distance
        prevCentroid = centroid
        return effects
    }

    /** Two fingers that moved together without spreading coast like a pan. */
    private fun pinchWasAPan(): Boolean = abs(zoomProduct - 1f) < PAN_ZOOM_TOLERANCE

    private fun beginPinch(fingers: List<Contact>) {
        rebase(fingers)
        centroidOrigin = prevCentroid
        centroidLast = prevCentroid
        zoomProduct = 1f
    }

    private fun rebase(fingers: List<Contact>) {
        val a = fingers[0].last
        val b = fingers[1].last
        prevDistance = hypot(a.x - b.x, a.y - b.y)
        prevCentroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        pinching = true
    }

    private fun onBoardFingers(): List<Contact> = contacts.values.filter { it.onBoard && it.placed }

    private class Contact {
        var placed = false
        var onBoard = false

        /** Part of a two-finger gesture, or left over from one. It never becomes the mouse. */
        var spent = false
        var longPressed = false

        /** A long-pressed finger that has moved past the slop and is drawing the selection box. */
        var boxing = false
        var placedMillis = 0L
        var anchor = Offset.Zero
        var last = Offset.Zero
        var previous = Offset.Zero
    }

    internal companion object {
        const val SLOP_PX = 12f

        /** Two fingers rarely land together. Waiting this long before holding the mouse lets the second one count. */
        const val SECOND_FINGER_WAIT_MILLIS = 100L
        const val DOUBLE_TAP_MILLIS = 300L
        const val LONG_PRESS_MILLIS = 500L
        const val DOUBLE_TAP_SLOP_PX = 40f
        const val DOUBLE_TAP_ZOOM = 2f
        const val PAN_ZOOM_TOLERANCE = 0.05f
    }
}

/** How often the board checks whether a still finger has become a long press. */
internal const val LONG_PRESS_POLL_MILLIS = 50L

/** How long a double tap takes to ease into its zoom. */
internal const val DOUBLE_TAP_ZOOM_MILLIS = 220

/** A fingertip covers far more than a line is wide. The mouse and pen keep DrawBox's 12dp. */
internal val FINGER_PICK_TOLERANCE = 24.dp
private val POINTER_PICK_TOLERANCE = 12.dp

/**
 * Whether the press DrawBox is picking for came from a finger.
 *
 * A finger reaches DrawBox as a synthetic mouse click, so DrawBox cannot tell. The board
 * sees every finger sample first and notes it here, and the click follows within the same
 * dispatch, well inside [FINGER_WINDOW_MILLIS].
 */
internal class CanvasFingerRecency {
    private var lastFingerMillis = Long.MIN_VALUE

    fun touched(atMillis: Long) {
        lastFingerMillis = atMillis
    }

    fun pickTolerance(nowMillis: Long): Dp =
        if (lastFingerMillis != Long.MIN_VALUE && nowMillis - lastFingerMillis <= FINGER_WINDOW_MILLIS) {
            FINGER_PICK_TOLERANCE
        } else {
            POINTER_PICK_TOLERANCE
        }

    private companion object {
        const val FINGER_WINDOW_MILLIS = 250L
    }
}

/** The rectangle with [a] and [b] as opposite corners, whichever way the finger dragged. */
internal fun boxOf(a: Offset, b: Offset): Rect =
    Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))

internal sealed interface CanvasFingerEffect {
    data object Catch : CanvasFingerEffect
    data class Pan(val dx: Float, val dy: Float) : CanvasFingerEffect
    data class Zoom(val factor: Float, val focalX: Float, val focalY: Float) : CanvasFingerEffect

    /** A zoom the board eases into rather than jumping to, as a double tap asks for. */
    data class AnimatedZoom(val factor: Float, val focalX: Float, val focalY: Float) : CanvasFingerEffect

    /** Two quick taps in one place: type into the shape there, or zoom in on open board. */
    data class DoubleTap(val x: Float, val y: Float) : CanvasFingerEffect

    /** A finger held still: add what is under it to the selection. */
    data class LongPress(val x: Float, val y: Float) : CanvasFingerEffect

    /** The selection box from ([fromX], [fromY]) to ([toX], [toY]); [commit] selects what it covers. */
    data class Marquee(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val commit: Boolean,
    ) : CanvasFingerEffect

    data object ClearMarquee : CanvasFingerEffect
    data class Arm(val x: Float, val y: Float) : CanvasFingerEffect
    data class Track(val x: Float, val y: Float) : CanvasFingerEffect
    data object Release : CanvasFingerEffect
    data class End(
        val kind: String,
        val travelX: Float,
        val travelY: Float,
        val zoomProduct: Float,
        val fling: Boolean,
    ) : CanvasFingerEffect
}

internal data class CanvasBoardTouchOutcome(
    val result: CanvasBoardTouchResult,
    val effects: List<CanvasFingerEffect>,
)

/**
 * Turns a finger gesture into board pans and pinches.
 *
 * Positions arriving here are window-logical. The board's layout is in pixels, denser than
 * that on a scaled display, which is the same conversion the pen uses.
 */
internal class CanvasBoardTouchBinding {
    var board: Rect? = null
    var density: Float = 1f
    var chrome: CanvasChromeRegions? = null
    var pan: (Offset) -> Unit = {}
    var zoom: (Float, Offset) -> Unit = { _, _ -> }
    var animateZoom: (Float, Offset) -> Unit = { factor, focal -> zoom(factor, focal) }

    /** Board-local pixels. True when it opened something to type into; otherwise the board zooms. */
    var doubleTap: (Offset) -> Boolean = { false }

    /** Board-local pixels. Adds what is there to the selection. */
    var longPress: (Offset) -> Unit = {}

    /** Board-local pixels, or a null end to clear. A commit selects what the box covers. */
    var marquee: (Offset, Offset?, Boolean) -> Unit = { _, _, _ -> }
    private var appliedPanX = 0f
    private var appliedPanY = 0f
    private var appliedZoom = 1f

    fun hits(x: Float, y: Float): Boolean {
        val bounds = board ?: return false
        val pixel = Offset(x * density, y * density)
        if (!bounds.contains(pixel)) return false
        // Controls are registered in root space, the same space as [pixel]. Subtracting the
        // board origin shifts that map, so open board reads as a control and a control reads
        // as board.
        return chrome?.contains(pixel) != true
    }

    fun apply(effects: List<CanvasFingerEffect>, fling: PanFling, now: Long) {
        effects.forEach { effect ->
            when (effect) {
                CanvasFingerEffect.Catch -> {
                    fling.stop()
                    appliedPanX = 0f
                    appliedPanY = 0f
                    appliedZoom = 1f
                }
                is CanvasFingerEffect.Pan -> {
                    val delta = Offset(effect.dx * density, effect.dy * density)
                    appliedPanX += delta.x
                    appliedPanY += delta.y
                    pan(delta)
                }
                is CanvasFingerEffect.Zoom -> {
                    appliedZoom *= effect.factor
                    zoom(effect.factor, toBoard(effect.focalX, effect.focalY))
                }
                is CanvasFingerEffect.AnimatedZoom -> {
                    appliedZoom *= effect.factor
                    animateZoom(effect.factor, toBoard(effect.focalX, effect.focalY))
                }
                is CanvasFingerEffect.DoubleTap -> {
                    val at = toBoard(effect.x, effect.y)
                    if (!doubleTap(at)) {
                        appliedZoom *= CanvasFingerGesture.DOUBLE_TAP_ZOOM
                        animateZoom(CanvasFingerGesture.DOUBLE_TAP_ZOOM, at)
                    }
                }
                is CanvasFingerEffect.LongPress -> longPress(toBoard(effect.x, effect.y))
                is CanvasFingerEffect.Marquee ->
                    marquee(toBoard(effect.fromX, effect.fromY), toBoard(effect.toX, effect.toY), effect.commit)
                CanvasFingerEffect.ClearMarquee -> marquee(Offset.Zero, null, false)
                is CanvasFingerEffect.Arm -> fling.begin(now, toPixel(effect.x, effect.y))
                is CanvasFingerEffect.Track -> fling.track(now, toPixel(effect.x, effect.y))
                CanvasFingerEffect.Release -> fling.release(now)
                is CanvasFingerEffect.End -> {
                    println(
                        "CANVAS: finger gesture end kind=${effect.kind} " +
                            "fingerTravel=(${effect.travelX * density},${effect.travelY * density})px " +
                            "boardPan=($appliedPanX,$appliedPanY)px zoom=$appliedZoom fling=${effect.fling} density=$density",
                    )
                    appliedPanX = 0f
                    appliedPanY = 0f
                    appliedZoom = 1f
                }
            }
        }
    }

    private fun toBoard(x: Float, y: Float): Offset {
        val bounds = board ?: return Offset(x * density, y * density)
        return toPixel(x, y) - bounds.topLeft
    }

    private fun toPixel(x: Float, y: Float): Offset = Offset(x * density, y * density)
}
