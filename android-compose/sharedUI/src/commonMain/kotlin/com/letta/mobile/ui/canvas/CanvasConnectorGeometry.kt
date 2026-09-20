package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import com.dk.kuiver.ui.EdgePathFactory
import io.ak1.drawbox.domain.model.Element

/**
 * Where a connector runs between two anchors.
 *
 * Kuiver supplies the geometry and nothing else: [EdgePathFactory] is a pure function of two
 * points, so the connector stays a DrawBox element and keeps that board's selection chrome, undo,
 * eraser and marquee. Kuiver's graph model, force layout and renderer are all unused.
 *
 * WHAT DRAWBOX CAN HOLD, AND SO WHAT WE CAN OFFER
 * ----------------------------------------------
 * `Element.Shape` stores `points` and a single `bend`, and its renderer draws the FIRST and LAST
 * point with one optional `quadraticTo` through `bend` — measured from the shipped artifact, not
 * assumed. So a two-point line and a single-arc curve are representable and nothing else is:
 * Kuiver's right-angle and orthogonal routes carry waypoints or two control points and would need
 * either several elements or a pen stroke, which costs the arrowhead. They are left out until
 * there is a reason to pay that.
 *
 * `bend` is an ABSOLUTE control point (the renderer treats `Offset.Zero` as "no bend" and passes
 * the value straight to `quadraticTo`), which is exactly what Kuiver's `controlPoint` is, so the
 * two meet without conversion.
 */
enum class CanvasConnectorShape {
    /** Two points, no bend. */
    STRAIGHT,

    /** A single arc bowing off the straight line, Kuiver's curved edge. */
    CURVED,
}

/** A connector's endpoints and its control point; [bend] is [Offset.Zero] for a straight run. */
data class CanvasConnectorGeometry(val points: List<Offset>, val bend: Offset)

/**
 * The geometry for a connector running [from] to [to].
 *
 * The two trailing arguments of Kuiver's factory inset the path's end so an arrowhead can sit
 * clear of a node — measured, since they are unnamed in the published artifact. Our connectors
 * already stop on the note's edge anchor, so they are off: no inset, no shortened line.
 */
fun connectorGeometry(from: Offset, to: Offset, shape: CanvasConnectorShape): CanvasConnectorGeometry =
    when (shape) {
        CanvasConnectorShape.STRAIGHT -> CanvasConnectorGeometry(listOf(from, to), Offset.Zero)
        CanvasConnectorShape.CURVED -> CanvasConnectorGeometry(
            points = listOf(from, to),
            bend = EdgePathFactory.createCurvedPath(from, to, false, 0f).controlPoint,
        )
    }

/**
 * How this connector is currently drawn. A bend of [Offset.Zero] is DrawBox's own "straight",
 * so the shape is read off the element rather than stored beside it.
 */
fun Element.Shape.connectorShape(): CanvasConnectorShape =
    if (bend == Offset.Zero) CanvasConnectorShape.STRAIGHT else CanvasConnectorShape.CURVED
