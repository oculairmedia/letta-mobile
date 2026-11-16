package com.letta.mobile.ui.components.beam

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BorderBeamGeometryTest {
    @Test
    fun eachRoundedCornerKeepsItsOwnRadius() {
        val corners = cornersOf(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        assertEquals(listOf(24f, 0f, 0f, 24f), corners.toList(), "top-right, bottom-right, bottom-left, top-left")
    }

    @Test
    fun cornersAreClampedToHalfTheShorterSide() {
        assertEquals(List(CORNERS) { 50f }, cornersOf(RoundedCornerShape(percent = 50)).toList())
        assertEquals(List(CORNERS) { 50f }, cornersOf(RoundedCornerShape(80.dp)).toList())
    }

    @Test
    fun aRightToLeftShapeMirrorsItsCorners() {
        val corners = cornersOf(RoundedCornerShape(topStart = 24.dp), LayoutDirection.Rtl)
        assertEquals(listOf(24f, 0f, 0f, 0f), corners.toList(), "the start corner is on the right")
    }

    @Test
    fun anyOtherOutlineIsARectangle() {
        assertEquals(List(CORNERS) { 0f }, cornersOf(RectangleShape).toList())
    }

    @Test
    fun theTrackStartsAtTheRightEdgesMiddleAndRunsClockwise() {
        val measure = PathMeasure().apply { setPath(track(floatArrayOf(10f, 20f, 0f, 30f)), false) }
        val start = measure.getPosition(0f)
        assertEquals(200f, start.x, 0.01f)
        assertEquals(50f, start.y, 0.01f)
        assertTrue(measure.getPosition(5f).y > start.y, "it heads down the right edge first, as the shader does")
        val bottom = measure.getPosition(30f + 10f * PI.toFloat() + 5f)
        assertEquals(100f, bottom.y, 0.01f, "then along the bottom")
    }

    @Test
    fun theTrackIsAsLongAsTheShaderThinksTheBorderIs() {
        val corners = floatArrayOf(10f, 20f, 0f, 30f)
        val measure = PathMeasure().apply { setPath(track(corners), false) }
        val sum = corners.sum()
        val expected = 4f * (100f + 50f) - 2f * sum + 0.5f * PI.toFloat() * sum
        assertEquals(expected, measure.length, 0.5f)
    }

    private fun track(corners: FloatArray) = Path().apply { addBeamTrack(Rect(0f, 0f, 200f, 100f), corners, inset = 0f) }

    private fun cornersOf(
        shape: androidx.compose.ui.graphics.Shape,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ): FloatArray {
        val out = FloatArray(CORNERS)
        CanvasDrawScope().draw(Density(1f), direction, Canvas(ImageBitmap(1, 1)), Size(200f, 100f)) {
            cornersFor(shape, this, out)
        }
        return out
    }
}
