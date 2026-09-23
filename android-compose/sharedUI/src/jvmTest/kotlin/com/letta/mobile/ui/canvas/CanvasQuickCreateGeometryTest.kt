package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.bounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where an arrow pulled out of a quick-create target puts the new shape, and which side it leaves from. */
class CanvasQuickCreateGeometryTest {
    private val box = Element.Shape(
        id = "box", shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(0f, 0f), Offset(160f, 100f)),
        strokeColor = Color.Black, strokeWidth = 2f, text = "Hello",
    )

    @Test
    fun theArrowLeavesFromTheSideFacingWhereItWasLetGo() {
        val from = Rect(0f, 0f, 100f, 100f)
        assertEquals(QuickCreateDirection.RIGHT, CanvasQuickCreate.directionToward(from, Offset(400f, 80f)))
        assertEquals(QuickCreateDirection.LEFT, CanvasQuickCreate.directionToward(from, Offset(-300f, 20f)))
        assertEquals(QuickCreateDirection.DOWN, CanvasQuickCreate.directionToward(from, Offset(60f, 500f)))
        assertEquals(QuickCreateDirection.UP, CanvasQuickCreate.directionToward(from, Offset(40f, -250f)))
    }

    @Test
    fun theNewShapeIsCentredWhereTheArrowWasLetGoInTheKindPicked() {
        val centre = Offset(600f, 300f)
        val circle = CanvasQuickCreate.shapeAt(box, centre, QuickCreateKind.CIRCLE, topZ = 3)
        assertEquals(ShapeType.CIRCLE, circle.shapeType)
        assertEquals(centre.x, circle.bounds().center.x, 0.5f)
        assertEquals(centre.y, circle.bounds().center.y, 0.5f)
        assertEquals("", circle.text, "it starts empty")
        assertEquals(4, circle.zIndex, "above everything else")
        assertTrue(circle.id != box.id)

        val rounded = CanvasQuickCreate.shapeAt(box, centre, QuickCreateKind.ROUNDED, topZ = 0)
        assertEquals(ShapeType.RECTANGLE, rounded.shapeType)
        assertTrue(rounded.cornerRadius > 0f)
        val same = CanvasQuickCreate.shapeAt(box, centre, QuickCreateKind.SAME, topZ = 0)
        assertEquals(box.bounds().size, same.bounds().size)
    }
}
