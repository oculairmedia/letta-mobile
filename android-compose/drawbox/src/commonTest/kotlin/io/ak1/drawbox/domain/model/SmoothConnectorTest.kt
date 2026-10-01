package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.usecase.UseCase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A connector that leaves one shape and meets the other square to their sides. */
class SmoothConnectorTest {
    private val useCase = UseCase()

    private fun box(id: String, area: Rect) = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(area.topLeft, area.bottomRight),
        strokeColor = Color.Red,
        strokeWidth = 2f,
    )

    private fun arrow(start: Offset, end: Offset, from: String?, to: String?) = Element.Shape(
        id = "arrow",
        shapeType = ShapeType.ARROW,
        points = listOf(start, end),
        strokeColor = Color.Red,
        strokeWidth = 2f,
        startBinding = from,
        endBinding = to,
    )

    // The circle-ish source up-left, the box down-right: the case the curve jammed into the side.
    private val source = box("source", Rect(0f, 0f, 200f, 200f))
    private val target = box("target", Rect(500f, 400f, 700f, 600f))

    private fun smoothed(): Element.Shape {
        val elements = useCase.propagateBindings(listOf(source, target, arrow(Offset(200f, 100f), Offset(500f, 500f), "source", "target")))
        return useCase.smoothConnector(elements, "arrow").first { it.id == "arrow" } as Element.Shape
    }

    private fun direction(o: Offset): Offset = o / o.getDistance()

    @Test
    fun eachHandlePointsFromItsShapesCentreOutThroughThatEnd() {
        val arrow = smoothed()
        val start = arrow.points[0]
        val end = arrow.points.last()
        val out = assertNotNull(arrow.startHandle)
        val inward = assertNotNull(arrow.endHandle)
        val expectedOut = direction(start - source.bounds().center)
        val expectedIn = direction(end - target.bounds().center)
        assertEquals(expectedOut.x, direction(out).x, 0.001f)
        assertEquals(expectedOut.y, direction(out).y, 0.001f)
        assertEquals(expectedIn.x, direction(inward).x, 0.001f)
        assertEquals(expectedIn.y, direction(inward).y, 0.001f)
        assertEquals(Offset.Zero, arrow.bend, "a smooth connector is not also an arc")
    }

    @Test
    fun theArrowArrivesSquareToTheSideItMeets() {
        val path = assertIs<LinePath.Cubic>(smoothed().linePath())
        // The target's facing side is its left or top edge; either way the arrival is along its normal.
        val heading = direction(path.endDirection())
        assertTrue(abs(heading.x) < 0.001f || abs(heading.y) < 0.001f, "arrives along an axis: $heading")
    }

    @Test
    fun movingAShapeReAimsTheHandles() {
        val arrow = smoothed()
        val moved = target.copy(points = target.points.map { it + Offset(-600f, 0f) })
        val after = useCase.propagateBindings(listOf(source, moved, arrow)).first { it.id == "arrow" } as Element.Shape
        val end = after.points.last()
        val expected = direction(end - moved.bounds().center)
        val handle = direction(assertNotNull(after.endHandle))
        assertEquals(expected.x, handle.x, 0.001f)
        assertEquals(expected.y, handle.y, 0.001f)
    }

    @Test
    fun bendingByHandTurnsItBackIntoOneArc() {
        val bent = useCase.setLineBend(listOf(smoothed()), "arrow", Offset(10f, 20f)).single() as Element.Shape
        assertNull(bent.startHandle)
        assertNull(bent.endHandle)
        assertIs<LinePath.Quadratic>(bent.linePath())
    }

    @Test
    fun theCurveIsHitAndBoundedAlongItsLength() {
        val arrow = smoothed()
        val path = arrow.linePath()
        val mid = path.pointAt(0.5f)
        assertTrue(arrow.hitTest(mid, tolerance = 2f), "the middle of the curve picks it")
        assertTrue(arrow.bounds().inflate(0.5f).contains(mid))
    }

    @Test
    fun handlesSurviveASaveAndLoad() {
        val arrow = smoothed()
        val back = arrow.toDto().toElement() as Element.Shape
        assertEquals(arrow.startHandle, back.startHandle)
        assertEquals(arrow.endHandle, back.endHandle)
        val plain = arrow.copy(startHandle = null, endHandle = null)
        val plainBack = plain.toDto().toElement() as Element.Shape
        assertNull(plainBack.startHandle)
        assertNull(plainBack.endHandle)
    }
}
