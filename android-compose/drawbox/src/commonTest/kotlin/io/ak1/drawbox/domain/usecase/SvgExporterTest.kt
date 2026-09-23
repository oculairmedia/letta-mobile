package io.ak1.drawbox.domain.usecase

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.StrokeStyle
import kotlin.test.Test
import kotlin.test.assertTrue

class SvgExporterTest {

    private fun viewBox(svg: String): List<Float> =
        Regex("""viewBox="([^"]+)"""").find(svg)!!.groupValues[1].split(' ').map { it.toFloat() }

    @Test
    fun aCircleDrawnSidewaysIsNotClipped() {
        // Diameter endpoints on one horizontal line: the circle still reaches 100 above and below.
        val circle = Element.Shape(
            shapeType = ShapeType.CIRCLE,
            points = listOf(Offset(0f, 0f), Offset(200f, 0f)),
            strokeColor = Color.Black,
            strokeWidth = 2f,
        )
        val (_, y, _, height) = viewBox(SvgExporter.exportToSvg(listOf(circle)))
        assertTrue(y <= -100f, "viewBox top $y")
        assertTrue(y + height >= 100f, "viewBox bottom ${y + height}")
    }

    @Test
    fun aDrawingAtNegativeCoordinatesDoesNotStretchToTheOrigin() {
        val path = Element.Path(
            samples = listOf(Offset(-500f, -500f), Offset(-400f, -450f)).map { Element.PathSample(it, 4f) },
            strokeColor = Color.Black,
            strokeWidth = 4f,
            alpha = 1f,
        )
        val (x, y, width, height) = viewBox(SvgExporter.exportToSvg(listOf(path)))
        assertTrue(x + width < 0f, "viewBox right ${x + width}")
        assertTrue(y + height < 0f, "viewBox bottom ${y + height}")
    }

    @Test
    fun aDashedRotatedPathKeepsItsDashesAndTurn() {
        val path = Element.Path(
            samples = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 50f)).map { Element.PathSample(it, 4f) },
            strokeColor = Color.Black,
            strokeWidth = 4f,
            alpha = 1f,
            rotation = 30f,
            strokeStyle = StrokeStyle.DASHED,
        )
        val svg = SvgExporter.exportToSvg(listOf(path))
        assertTrue("stroke-dasharray" in svg, svg)
        assertTrue("rotate(30.0" in svg, svg)
    }
}
