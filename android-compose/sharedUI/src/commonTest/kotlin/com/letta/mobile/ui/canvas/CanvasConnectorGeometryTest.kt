package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.canvas.CanvasArrowBinding
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasEndBinding
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Kuiver supplies connector geometry; DrawBox still owns the element, so a connector keeps the
 * board's selection, undo and eraser (letta-mobile-4i2z9.21).
 */
class CanvasConnectorGeometryTest {

    private fun connector(points: List<Offset>, bend: Offset) = Element.Shape(
        id = "conn-1",
        shapeType = ShapeType.LINE,
        points = points,
        strokeColor = Color.Black,
        strokeWidth = 2f,
        bend = bend,
    )

    private val movedFrame = CanvasDocumentFrame(x = 300f, y = 200f, width = 100f, height = 80f)

    @Test
    fun straightConnectorKeepsBendAtZero() {
        val geometry = connectorGeometry(Offset(0f, 0f), Offset(100f, 0f), CanvasConnectorShape.STRAIGHT)
        assertEquals(listOf(Offset(0f, 0f), Offset(100f, 0f)), geometry.points)
        assertEquals(Offset.Zero, geometry.bend, "Zero is DrawBox's own 'no bend'; anything else draws a curve")
    }

    @Test
    fun curvedConnectorLeavesTheStraightLine() {
        val geometry = connectorGeometry(Offset(0f, 0f), Offset(100f, 0f), CanvasConnectorShape.CURVED)
        assertEquals(50f, geometry.bend.x, absoluteTolerance = 0.01f)
        assertTrue(geometry.bend.y != 0f, "a curved connector must bow off the line, got ${geometry.bend}")
    }

    @Test
    fun followRecomputesTheBendWhenTheNoteMoves() {
        val start = connectorGeometry(Offset(0f, 0f), Offset(100f, 0f), CanvasConnectorShape.CURVED)
        val moved = CanvasSnapping.follow(
            connector = connector(start.points, start.bend),
            binding = CanvasArrowBinding(end = CanvasEndBinding(documentId = "note-1", side = "left")),
            documentId = "note-1",
            frame = movedFrame,
        )
        requireNotNull(moved)
        assertNotEquals(start.bend, moved.bend, "a bend is an absolute point, so it distorts unless it is recomputed")
        assertEquals(
            connectorGeometry(moved.points.first(), moved.points.last(), CanvasConnectorShape.CURVED).bend,
            moved.bend,
        )
    }

    @Test
    fun followLeavesAStraightConnectorStraight() {
        val moved = CanvasSnapping.follow(
            connector = connector(listOf(Offset(0f, 0f), Offset(100f, 0f)), Offset.Zero),
            binding = CanvasArrowBinding(end = CanvasEndBinding(documentId = "note-1", side = "top")),
            documentId = "note-1",
            frame = movedFrame,
        )
        requireNotNull(moved)
        assertEquals(Offset.Zero, moved.bend)
    }
}
