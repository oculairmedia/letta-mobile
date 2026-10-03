package io.ak1.drawbox

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.CONNECTOR_CHIP_PAD
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.arrowHeadDepth
import io.ak1.drawbox.domain.model.arrowHeadSize
import io.ak1.drawbox.domain.model.bezierMidpoint
import io.ak1.drawbox.domain.model.connectorLabelCentre
import io.ak1.drawbox.domain.model.connectorLabelOnChip
import io.ak1.drawbox.domain.usecase.SvgExporter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a connector's label goes: on the curve's own midpoint when the shaft has room for its
 * chip, beside the shaft otherwise, and never over the arrowhead. The pixels (chip colour on a
 * dark board, a bent and a short arrow) are checked by sharedUI's CanvasConnectorLabelRenderTest.
 */
class ConnectorLabelTest {
    private fun arrow(
        start: Offset,
        end: Offset,
        bend: Offset = Offset.Zero,
        startHandle: Offset? = null,
        endHandle: Offset? = null,
        type: ShapeType = ShapeType.ARROW,
        fontSize: Float = 13f,
    ) = Element.Shape(
        id = "a",
        shapeType = type,
        points = listOf(start, end),
        strokeColor = Color.Black,
        strokeWidth = 3f,
        bend = bend,
        startHandle = startHandle,
        endHandle = endHandle,
        text = "sort",
        fontSize = fontSize,
    )

    private fun assertNear(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.01f, "x of $actual, expected $expected")
        assertEquals(expected.y, actual.y, 0.01f, "y of $actual, expected $expected")
    }

    @Test
    fun aBentArrowsLabelSitsOnTheCurveNotTheChord() {
        val bent = arrow(Offset(0f, 0f), Offset(300f, 0f), bend = Offset(0f, -120f))
        assertTrue(bent.connectorLabelOnChip(30f, 16f))
        val centre = bent.connectorLabelCentre(30f, 16f)
        assertNear(bent.bezierMidpoint(), centre)
        // The curve passes through the chord midpoint plus half the bend: 60 units off the chord.
        assertNear(Offset(150f, -60f), centre)
    }

    @Test
    fun aSmoothedArrowsLabelSitsOnItsCubicMidpoint() {
        val smoothed = arrow(
            Offset(0f, 0f),
            Offset(400f, 200f),
            startHandle = Offset(0f, 180f),
            endHandle = Offset(-180f, 0f),
        )
        assertTrue(smoothed.connectorLabelOnChip(30f, 16f))
        val centre = smoothed.connectorLabelCentre(30f, 16f)
        assertNear(smoothed.bezierMidpoint(), centre)
        assertTrue(abs(centre.x - 200f) > 1f || abs(centre.y - 100f) > 1f, "the cubic midpoint is off the chord")
    }

    @Test
    fun aShortArrowsLabelGoesAboveTheShaftWithoutAChip() {
        // About 84 units: a 47-unit chip would leave no shaft before the 26-unit head.
        val short = arrow(Offset(100f, 200f), Offset(184f, 200f))
        assertFalse(short.connectorLabelOnChip(41f, 16f))
        val centre = short.connectorLabelCentre(41f, 16f)
        // Centred on the shaft the head leaves: from the tail to where the head starts.
        assertEquals((100f + 184f - short.arrowHeadDepth()) / 2f, centre.x, 0.01f)
        assertTrue(centre.x + 20.5f <= 184f - short.arrowHeadDepth(), "label stands over the head")
        // Its bottom edge is clear of the 3-unit stroke.
        assertTrue(centre.y + 8f <= 200f - 1.5f, "label bottom ${centre.y + 8f} reaches the shaft")
    }

    @Test
    fun aLabelLongerThanTheShaftClearsTheArrowheadsBarbs() {
        val stub = arrow(Offset(100f, 200f), Offset(160f, 200f))
        assertFalse(stub.connectorLabelOnChip(80f, 16f))
        val bottom = stub.connectorLabelCentre(80f, 16f).y + 8f
        assertTrue(bottom <= 200f - stub.arrowHeadSize() / 2f, "label bottom $bottom is over the head")
    }

    @Test
    fun aShortVerticalArrowsLabelGoesToItsLeft() {
        val short = arrow(Offset(100f, 100f), Offset(100f, 190f), fontSize = 20f)
        assertFalse(short.connectorLabelOnChip(62f, 24f))
        val centre = short.connectorLabelCentre(62f, 24f)
        assertTrue(centre.y + 12f <= 190f - short.arrowHeadDepth(), "label stands over the head")
        assertTrue(centre.x + 31f <= 100f - 1.5f, "label right edge ${centre.x + 31f} reaches the shaft")
    }

    @Test
    fun aShortBentArrowsLabelGoesOnTheOutsideOfTheCurve() {
        val bentDown = arrow(Offset(0f, 0f), Offset(90f, 0f), bend = Offset(0f, 40f))
        assertFalse(bentDown.connectorLabelOnChip(41f, 16f))
        assertTrue(bentDown.connectorLabelCentre(41f, 16f).y > bentDown.bezierMidpoint().y)
    }

    @Test
    fun anOnShaftChipNeverReachesTheArrowhead() {
        listOf(60f, 84f, 90f, 120f, 200f, 400f).forEach { length ->
            listOf(20f, 41f, 70f, 140f).forEach { width ->
                val a = arrow(Offset(0f, 0f), Offset(length, 0f))
                if (a.connectorLabelOnChip(width, 16f)) {
                    val chipEnd = a.connectorLabelCentre(width, 16f).x + width / 2f + CONNECTOR_CHIP_PAD
                    assertTrue(chipEnd < length - a.arrowHeadDepth(), "chip to $chipEnd on a $length arrow")
                    assertTrue(width + 2 * CONNECTOR_CHIP_PAD <= length * 0.6f + 0.01f, "chip $width on a $length arrow")
                }
            }
        }
    }

    @Test
    fun theSvgExportKeepsTheLabelAndItsChip() {
        // Channels of 0 and 1 so the exporter's hex is exact.
        val dark = Color(0xFF0000FF)
        val long = arrow(Offset(0f, 0f), Offset(400f, 0f))
        val svg = SvgExporter.exportToSvg(listOf(long), bgColor = dark)
        assertTrue(svg.contains(">sort</tspan>"), svg)
        assertTrue(svg.contains("""fill="#0000ff""""), "the chip is the board colour: $svg")
        // A short arrow's label is beside the shaft, with no chip; no board colour, no chip.
        val short = SvgExporter.exportToSvg(listOf(arrow(Offset(0f, 0f), Offset(84f, 0f))), bgColor = dark)
        assertTrue(short.contains(">sort</tspan>") && !short.contains("<rect"), short)
        assertFalse(SvgExporter.exportToSvg(listOf(long)).contains("<rect"))
    }

    @Test
    fun aLineHasNoHeadToKeepClearOf() {
        val line = arrow(Offset(0f, 0f), Offset(84f, 0f), type = ShapeType.LINE)
        assertEquals(0f, line.arrowHeadDepth())
        assertTrue(line.connectorLabelOnChip(30f, 16f))
        assertNear(line.bezierMidpoint(), line.connectorLabelCentre(30f, 16f))
    }
}
