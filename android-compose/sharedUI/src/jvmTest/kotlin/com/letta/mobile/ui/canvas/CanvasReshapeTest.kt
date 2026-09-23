package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CanvasReshapeTest {
    private fun box(id: String = "box", type: ShapeType = ShapeType.RECTANGLE) = Element.Shape(
        id = id,
        shapeType = type,
        points = listOf(Offset(100f, 100f), Offset(300f, 200f)),
        strokeColor = Color.Black,
        fillColor = Color.Yellow,
        strokeWidth = 3f,
        text = "Label",
    )

    @Test
    fun aBoxBecomesTheWidestCircleInsideItAboutTheSameCentre() {
        val circle = CanvasReshape.reshaped(box(), ShapeType.CIRCLE)
        assertEquals(ShapeType.CIRCLE, circle.shapeType)
        val b = circle.bounds()
        assertEquals(200f, b.center.x, 0.01f)
        assertEquals(150f, b.center.y, 0.01f)
        assertEquals(100f, b.width, 0.01f)
        assertEquals(100f, b.height, 0.01f)
        assertEquals("Label", circle.text)
        assertEquals(Color.Yellow, circle.fillColor)
    }

    @Test
    fun aBoxBecomesATriangleOverTheSameBounds() {
        val triangle = CanvasReshape.reshaped(box(), ShapeType.TRIANGLE)
        assertEquals(ShapeType.TRIANGLE, triangle.shapeType)
        assertEquals(box().bounds(), triangle.bounds())
    }

    @Test
    fun aCircleBecomesTheSquareAroundIt() {
        val circle = CanvasReshape.reshaped(box(), ShapeType.CIRCLE)
        val square = CanvasReshape.reshaped(circle, ShapeType.RECTANGLE)
        assertEquals(circle.bounds(), square.bounds())
    }

    @Test
    fun linesArrowsAndTheSameTypeAreLeftAlone() {
        val same = box()
        assertSame(same, CanvasReshape.reshaped(same, ShapeType.RECTANGLE))
        val arrow = box(type = ShapeType.ARROW)
        assertSame(arrow, CanvasReshape.reshaped(arrow, ShapeType.CIRCLE))
        assertSame(same, CanvasReshape.reshaped(same, ShapeType.LINE))
    }

    @Test
    fun reshapingTheSelectionIsOneUndoStep() {
        val controller = DrawBoxController(Reducer(UseCase()))
        controller.onIntent(Intent.AddElement(box("a")))
        controller.onIntent(Intent.AddElement(box("b")))
        controller.onIntent(Intent.SelectIds(setOf("a", "b")))
        CanvasReshape.apply(controller, ShapeType.CIRCLE)
        fun types() = controller.state.value.elements.filterIsInstance<Element.Shape>().map { it.shapeType }
        assertEquals(listOf(ShapeType.CIRCLE, ShapeType.CIRCLE), types())
        controller.onIntent(Intent.Undo)
        assertEquals(listOf(ShapeType.RECTANGLE, ShapeType.RECTANGLE), types())
    }
}
