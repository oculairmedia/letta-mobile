package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.ak1.drawbox.presentation.PanFling
import kotlin.math.hypot

/**
 * One or two fingers on the board.
 *
 * The contact-down sample is not where the finger is. The first move places it.
 * One finger past the slop pans in both axes, from that place, so the board stays
 * under the finger. Two fingers pinch about their midpoint and pan with it.
 * A finger that never leaves the slop is a tap.
 */
internal class CanvasFingerGesture(
    private val slopPx: Float = SLOP_PX,
) {
    private val contacts = mutableMapOf<Int, Contact>()
    private var pinching = false
    private var gesturePinched = false
    private var prevDistance = 0f
    private var prevCentroid = Offset.Zero
    private var centroidOrigin = Offset.Zero
    private var centroidLast = Offset.Zero
    private var zoomProduct = 1f

    fun offer(contact: Int, phase: CanvasBoardTouchSample.Phase, x: Float, y: Float, onBoard: Boolean): CanvasBoardTouchOutcome =
        when (phase) {
            CanvasBoardTouchSample.Phase.DOWN -> down(contact)
            CanvasBoardTouchSample.Phase.MOVE -> move(contact, x, y, onBoard)
            CanvasBoardTouchSample.Phase.UP -> up(contact)
            CanvasBoardTouchSample.Phase.CANCEL -> cancel(contact)
        }

    private fun down(id: Int): CanvasBoardTouchOutcome {
        contacts[id] = Contact()
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
            return CanvasBoardTouchOutcome(
                if (onBoard) CanvasBoardTouchResult.Consumed else CanvasBoardTouchResult.Ignored,
                emptyList(),
            )
        }
        if (!contact.onBoard) return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, emptyList())
        contact.previous = contact.last
        contact.last = point
        return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, drag())
    }

    /** The Ink copy of a finger left. It must not tap or coast; the pointer frame is the finger. */
    private fun cancel(id: Int): CanvasBoardTouchOutcome {
        contacts.remove(id) ?: return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, emptyList())
        val still = onBoardFingers()
        if (still.isEmpty()) {
            pinching = false
            return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, listOf(CanvasFingerEffect.Catch))
        }
        if (still.size >= 2) {
            rebase(still)
            return CanvasBoardTouchOutcome(
                CanvasBoardTouchResult.Consumed,
                listOf(CanvasFingerEffect.Arm(prevCentroid.x, prevCentroid.y)),
            )
        }
        pinching = false
        val finger = still[0]
        finger.anchor = finger.last
        finger.previous = finger.last
        finger.panning = true
        return CanvasBoardTouchOutcome(
            CanvasBoardTouchResult.Consumed,
            listOf(CanvasFingerEffect.Arm(finger.last.x, finger.last.y)),
        )
    }

    private fun up(id: Int): CanvasBoardTouchOutcome {
        val contact = contacts.remove(id) ?: return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, emptyList())
        if (!contact.placed || !contact.onBoard) {
            return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Ignored, emptyList())
        }
        val still = onBoardFingers()
        if (still.size >= 2) {
            rebase(still)
            return CanvasBoardTouchOutcome(
                CanvasBoardTouchResult.Consumed,
                listOf(CanvasFingerEffect.Arm(prevCentroid.x, prevCentroid.y)),
            )
        }
        if (still.isNotEmpty()) {
            pinching = false
            val finger = still[0]
            finger.anchor = finger.last
            finger.previous = finger.last
            finger.panning = true
            return CanvasBoardTouchOutcome(
                CanvasBoardTouchResult.Consumed,
                listOf(CanvasFingerEffect.Arm(finger.last.x, finger.last.y)),
            )
        }
        val fling = contact.panning && !gesturePinched
        val travel = if (gesturePinched) centroidLast - centroidOrigin else contact.last - contact.anchor
        val kind = when {
            gesturePinched -> "pinch"
            contact.panning -> "pan"
            else -> "tap"
        }
        val end = CanvasFingerEffect.End(kind, travel.x, travel.y, if (gesturePinched) zoomProduct else 1f, fling)
        pinching = false
        gesturePinched = false
        zoomProduct = 1f
        if (kind == "tap") {
            return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Tap(contact.anchor.x, contact.anchor.y), listOf(end))
        }
        val effects = if (fling) listOf(CanvasFingerEffect.Release, end) else listOf(end)
        return CanvasBoardTouchOutcome(CanvasBoardTouchResult.Consumed, effects)
    }

    private fun drag(): List<CanvasFingerEffect> {
        val fingers = onBoardFingers()
        if (fingers.size >= 2) {
            if (!pinching) {
                pinching = true
                gesturePinched = true
                beginPinch(fingers)
                return listOf(CanvasFingerEffect.Arm(prevCentroid.x, prevCentroid.y))
            }
            return pinch(fingers[0], fingers[1])
        }
        if (pinching) {
            pinching = false
            val finger = fingers.singleOrNull() ?: return emptyList()
            finger.anchor = finger.last
            finger.previous = finger.last
            finger.panning = true
            return listOf(CanvasFingerEffect.Arm(finger.last.x, finger.last.y))
        }
        val finger = fingers.singleOrNull() ?: return emptyList()
        return panOne(finger)
    }

    private fun panOne(finger: Contact): List<CanvasFingerEffect> {
        val fromAnchor = finger.last - finger.anchor
        if (!finger.panning) {
            if (hypot(fromAnchor.x, fromAnchor.y) <= slopPx) return emptyList()
            finger.panning = true
            return listOf(
                CanvasFingerEffect.Arm(finger.last.x, finger.last.y),
                CanvasFingerEffect.Pan(fromAnchor.x, fromAnchor.y),
            )
        }
        val step = finger.last - finger.previous
        return listOf(
            CanvasFingerEffect.Pan(step.x, step.y),
            CanvasFingerEffect.Track(finger.last.x, finger.last.y),
        )
    }

    private fun pinch(first: Contact, second: Contact): List<CanvasFingerEffect> {
        val a = first.last
        val b = second.last
        val distance = hypot(a.x - b.x, a.y - b.y)
        val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        if (!pinching) {
            pinching = true
            prevDistance = distance
            prevCentroid = centroid
            return listOf(CanvasFingerEffect.Arm(centroid.x, centroid.y))
        }
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
        var panning = false
        var anchor = Offset.Zero
        var last = Offset.Zero
        var previous = Offset.Zero
    }

    private companion object {
        const val SLOP_PX = 12f
    }
}

internal sealed interface CanvasFingerEffect {
    data object Catch : CanvasFingerEffect
    data class Pan(val dx: Float, val dy: Float) : CanvasFingerEffect
    data class Zoom(val factor: Float, val focalX: Float, val focalY: Float) : CanvasFingerEffect
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
