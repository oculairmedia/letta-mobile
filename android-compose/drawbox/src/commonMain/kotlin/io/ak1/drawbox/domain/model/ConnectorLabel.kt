package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Where the text of an arrow or a line (a connector) goes. A connector has no inside to hold text
 * ([canHoldText] is false), so its text is a label on the curve, placed by one rule:
 *
 * - **On the shaft, on a chip**, when the shaft has room for it. The label is centred on the
 *   curve's own midpoint, [bezierMidpoint] (not the chord's, which is off the curve as soon as the
 *   connector is bent or smoothed), on a chip the colour of the board that knocks the shaft out
 *   behind the text. There is room when the chip, measured along the shaft, takes no more than
 *   [MAX_CHIP_SHARE] of the endpoint distance and still leaves [SHAFT_SHOWN_EMS] em of shaft
 *   visible on each side, the arrowhead's depth ([arrowHeadDepth]) not counting as shaft. So the
 *   chip never covers the arrowhead and a connector never reads as a chip with no line.
 * - **Beside the shaft, without a chip**, otherwise: the label is moved off the midpoint along
 *   the shaft's normal until its box clears the stroke by [BESIDE_GAP_EMS] em, and back from the
 *   arrowhead by half its depth, so it is centred on the shaft the head leaves; a label longer
 *   than that shaft also clears the head's barbs. It goes on the outside of a bent or smoothed
 *   curve; on a straight connector, above it when it runs mostly across and to its left when it
 *   runs mostly up or down.
 *
 * Everything is in world units, so the label scales with the board like the connector does. A
 * selected connector's bend handle is drawn at [bezierMidpoint], over an on-shaft label; that is
 * the drag target, and it is only there while the connector is selected.
 *
 * The label is not part of the connector's [bounds] or its hit test: a fit, a marquee or a capture
 * frames the curve, and the label can stand a few units outside it; a tap on the text off the
 * shaft does not pick the connector.
 */

/** Whether this shape is a connector, an arrow or a line, whose text is a label on its curve. */
val Element.Shape.isConnector: Boolean
    get() = shapeType == ShapeType.ARROW || shapeType == ShapeType.LINE

/** Side of the arrowhead drawn at an arrow's end, in world units. Grows with the stroke. */
fun Element.Shape.arrowHeadSize(): Float = max(MIN_ARROW_HEAD, strokeWidth * ARROW_HEAD_PER_STROKE)

/** How far the arrowhead reaches back along the shaft from the tip; 0 for a line, which has none. */
fun Element.Shape.arrowHeadDepth(): Float =
    if (shapeType == ShapeType.ARROW) arrowHeadSize() * COS_30 else 0f

/** Widest a connector label is laid out before it wraps, in world units: [LABEL_MAX_EMS] em. */
fun Element.Shape.connectorLabelMaxWidth(): Float = fontSize * LABEL_MAX_EMS

/**
 * Whether a [width] by [height] label sits on this connector's shaft, on a chip, rather than
 * beside it. See the rule at the top of this file.
 */
fun Element.Shape.connectorLabelOnChip(width: Float, height: Float): Boolean =
    points.size >= 2 && fitsOnShaft(linePath(), width, height)

/**
 * The centre of a [width] by [height] label on this connector: [bezierMidpoint] when it sits on
 * the shaft, otherwise beside the shaft. See the rule at the top of this file.
 */
fun Element.Shape.connectorLabelCentre(width: Float, height: Float): Offset {
    if (points.size < 2) return points.firstOrNull() ?: Offset.Zero
    val path = linePath()
    val mid = path.pointAt(MID)
    if (fitsOnShaft(path, width, height)) return mid
    val along = unitOr(path.midDirection(), ACROSS)
    val away = sideAwayFromShaft(path, mid)
    val clearance = halfExtent(away, width, height) + besideLift(path, along, width, height) + fontSize * BESIDE_GAP_EMS
    // Centred on the stretch of shaft the arrowhead leaves, so the label does not stand over the head.
    return mid - along * (arrowHeadDepth() / 2f) + away * clearance
}

private fun Element.Shape.fitsOnShaft(path: LinePath, width: Float, height: Float): Boolean {
    val chord = distance(path.start, path.end)
    val along = unitOr(path.midDirection(), ACROSS)
    val halfAlong = halfExtent(along, width + CONNECTOR_CHIP_PAD * 2, height + CONNECTOR_CHIP_PAD * 2)
    val shaftLeft = chord / 2f - arrowHeadDepth() - halfAlong
    return halfAlong * 2f <= chord * MAX_CHIP_SHARE && shaftLeft >= fontSize * SHAFT_SHOWN_EMS
}

/**
 * How far a label beside the shaft keeps from the shaft's centre line, besides its gap: half the
 * stroke, or half the arrowhead's width when the label is longer than the shaft the head leaves,
 * so it would otherwise stand over the head's barbs.
 */
private fun Element.Shape.besideLift(path: LinePath, along: Offset, width: Float, height: Float): Float {
    val head = arrowHeadDepth()
    val shaft = distance(path.start, path.end) - head
    val overHead = head > 0f && halfExtent(along, width, height) * 2f > shaft
    return if (overHead) max(strokeWidth, arrowHeadSize()) / 2f else strokeWidth / 2f
}

/** Half a [width] by [height] box's extent along the unit [direction]: its projection on it. */
private fun halfExtent(direction: Offset, width: Float, height: Float): Float =
    abs(direction.x) * width / 2f + abs(direction.y) * height / 2f

/**
 * The unit normal of the shaft at its middle on the side the label goes: the outside of the
 * curve when it bends, else above a mostly-horizontal shaft and left of a mostly-vertical one.
 */
private fun sideAwayFromShaft(path: LinePath, mid: Offset): Offset {
    val along = unitOr(path.midDirection(), ACROSS)
    val normal = Offset(-along.y, along.x)
    val bulge = mid - (path.start + path.end) / 2f
    val preferred = when {
        bulge.getDistance() > MIN_BULGE -> bulge
        abs(along.x) >= abs(along.y) -> UP
        else -> LEFT
    }
    return if (normal.x * preferred.x + normal.y * preferred.y >= 0f) normal else -normal
}

private fun unitOr(v: Offset, fallback: Offset): Offset {
    val length = v.getDistance()
    return if (length > MIN_DIRECTION) v / length else fallback
}

/** Padding of a connector label's chip around its text, in world units. */
const val CONNECTOR_CHIP_PAD: Float = 3f

private const val MID = 0.5f
private const val MIN_ARROW_HEAD = 30f
private const val ARROW_HEAD_PER_STROKE = 3f
private val COS_30 = cos(PI / 6).toFloat()
private const val LABEL_MAX_EMS = 12f
private const val MAX_CHIP_SHARE = 0.6f
private const val SHAFT_SHOWN_EMS = 1f
private const val BESIDE_GAP_EMS = 0.3f
private const val MIN_BULGE = 0.5f
private const val MIN_DIRECTION = 1e-3f
private val ACROSS = Offset(1f, 0f)
private val UP = Offset(0f, -1f)
private val LEFT = Offset(-1f, 0f)
