@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.ak1.drawbox.DrawingPreview
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The text stored on an arrow or a line, as the canvas paints it (DrawBox's DrawingPreview, the
 * renderer the board, replays and thumbnails share): on the curve's midpoint over a chip of the
 * board's colour when the shaft has room, beside the shaft otherwise. Pixels are sampled, so
 * deleting the label, painting the chip in the stroke's colour or white, or putting it on the
 * chord instead of the curve each fail a test. Snapshots go to `build/canvas-connector-labels/`.
 */
class CanvasConnectorLabelRenderTest {

    @Test
    fun onADarkBoardTheChipIsTheBoardNotTheStrokeOrWhite() {
        val arrow = arrow(Offset(100f, 200f), Offset(500f, 200f), text = "ship")
        val image = render(listOf(arrow), DARK, 600, 400, "dark-board.png")
        // Without a label the shaft runs through the midpoint in the stroke's colour.
        val bare = render(listOf(arrow.copy(text = "")), DARK, 600, 400, "dark-board-bare.png")
        assertTrue(bare.near(300, 200, STROKE), "the bare shaft should cross the midpoint")

        // The chip spans about 300 +- 24 on the shaft; sample well inside it.
        val row = (284..316).map { x -> image.getRGB(x, 200) }
        assertTrue(row.none { near(it, STROKE) }, "the shaft shows through the label")
        assertTrue(row.any { near(it, DARK, tolerance = 2) }, "no board-coloured chip on the shaft")
        assertTrue(region(image, 300, 200, 18, 10).none { near(it, Color.White, tolerance = 15) }, "a white chip")
        assertTrue(region(image, 300, 200, 18, 10).any(::amber), "the label's text is not drawn")
        // The rest of the shaft is still drawn.
        assertTrue(image.near(150, 200, STROKE))
        assertTrue(image.near(440, 200, STROKE))
    }

    @Test
    fun aBentArrowsLabelSitsOnTheCurveNotTheChord() {
        // The curve passes 80 above the chord's midpoint: half the bend.
        val bent = arrow(Offset(100f, 300f), Offset(500f, 300f), text = "ship", bend = Offset(0f, -160f))
        val image = render(listOf(bent), DARK, 600, 400, "dark-board-bent.png")
        assertTrue(region(image, 300, 220, 50, 16).any(::amber), "no label on the curve's midpoint")
        assertTrue(region(image, 300, 300, 50, 16).none(::amber), "the label is on the chord")
        assertTrue((286..314).none { x -> image.near(x, 220, STROKE) }, "the curve shows through the label")
    }

    @Test
    fun aShortArrowKeepsItsShaftAndHeadAndTakesTheLabelAboveIt() {
        // 84 units at the default font size: a chip on the shaft would leave no line, only a head.
        val short = arrow(Offset(100f, 200f), Offset(184f, 200f), text = "sort")
        val image = render(listOf(short), DARK, 300, 300, "dark-board-short.png")
        val headStart = 184 - 26
        (104 until headStart - 2).forEach { x ->
            assertTrue(image.near(x, 200, STROKE), "the shaft is broken at $x")
        }
        assertTrue(region(image, 142, 180, 40, 14).any(::amber), "no label above the shaft")
        assertTrue(region(image, 142, 222, 40, 14).none(::amber), "the label is below the shaft")
        assertTrue((150..186).all { x -> image.getRGB(x, 200).let { !amber(it) } }, "the label is over the arrowhead")
    }

    /**
     * Not an assertion of looks: the gallery the visual review reads. Light and dark boards at a
     * phone's and a desktop's size, with long, bent, smoothed, short, wrapped, CJK, line and
     * vertical connectors.
     */
    @Test
    fun rendersTheReviewGallery() {
        listOf(PHONE, DESKTOP).forEach { (name, size) ->
            val (width, height) = size
            listOf("light" to (Color.White to INK), "dark" to (DARK to STROKE)).forEach { (theme, colours) ->
                val (board, ink) = colours
                val image = render(gallery(width.toFloat(), ink), board, width, height, "gallery-$name-$theme.png")
                assertEquals(width, image.width)
            }
        }
    }

    private fun gallery(width: Float, ink: Color): List<Element> {
        val right = width - MARGIN
        val half = width / 2f
        fun a(start: Offset, end: Offset, text: String, size: Float, block: Element.Shape.() -> Element.Shape = { this }) =
            arrow(start, end, text = text, fontSize = size, stroke = ink, textColor = null).block()
        return listOf(
            a(Offset(MARGIN, 70f), Offset(right, 70f), "ship it", 20f),
            a(Offset(MARGIN, 220f), Offset(right, 220f), "bent label", 16f) { copy(bend = Offset(0f, -120f)) },
            a(Offset(MARGIN + 16f, 270f), Offset(right - 16f, 380f), "smoothed", 13f) {
                copy(startHandle = Offset(0f, 110f), endHandle = Offset(-(right - MARGIN) * 0.4f, 0f))
            },
            a(Offset(MARGIN, 470f), Offset(MARGIN + 84f, 470f), "sort", 13f),
            a(Offset(half, 470f), Offset(half + 90f, 470f), "next", 20f),
            a(Offset(MARGIN, 470f + 90f), Offset(MARGIN + 84f, 470f + 90f), "file", 20f),
            a(Offset(half, 470f + 90f), Offset(half + 90f, 470f + 90f), "ok", 13f),
            a(Offset(MARGIN, 660f), Offset(right, 660f), "a much longer label that keeps going until it has to wrap onto another line", 14f),
            a(Offset(MARGIN, 760f), Offset(half - 10f, 760f), "流程图", 16f),
            a(Offset(half + 10f, 760f), Offset(right, 760f), "线 line", 16f) { copy(shapeType = ShapeType.LINE) },
            a(Offset(right - 40f, 800f), Offset(right - 40f, 890f), "up", 13f),
            a(Offset(MARGIN + 40f, 800f), Offset(MARGIN + 40f, 890f), "down", 20f),
        )
    }

    private fun arrow(
        start: Offset,
        end: Offset,
        text: String,
        bend: Offset = Offset.Zero,
        fontSize: Float = 20f,
        stroke: Color = STROKE,
        textColor: Color? = TEXT,
    ) = Element.Shape(
        id = "arrow-${start.x}-${start.y}-${end.x}-${end.y}",
        shapeType = ShapeType.ARROW,
        points = listOf(start, end),
        strokeColor = stroke,
        strokeWidth = 3f,
        bend = bend,
        text = text,
        textColor = textColor,
        fontSize = fontSize,
    )

    /** [elements] drawn by DrawingPreview on [board] at zoom 1, one world unit to a pixel. */
    private fun render(elements: List<Element>, board: Color, width: Int, height: Int, snapshot: String): BufferedImage {
        lateinit var image: BufferedImage
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    DrawingPreview(elements = elements, bgColor = board)
                }
            }
            waitForIdle()
            image = onRoot().captureToImage().toAwtImage()
        }
        val file = File("build/canvas-connector-labels").apply { mkdirs() }.resolve(snapshot)
        ImageIO.write(image, "png", file)
        println("canvas-connector-labels snapshot: ${file.absolutePath} (${width}x$height)")
        return image
    }

    private fun region(image: BufferedImage, cx: Int, cy: Int, halfWidth: Int, halfHeight: Int): List<Int> =
        (cy - halfHeight..cy + halfHeight).flatMap { y -> (cx - halfWidth..cx + halfWidth).map { x -> image.getRGB(x, y) } }

    private fun BufferedImage.near(x: Int, y: Int, colour: Color): Boolean = near(getRGB(x, y), colour)

    private fun near(argb: Int, colour: Color, tolerance: Int = 40): Boolean {
        val want = colour.toArgb()
        return channels(argb).zip(channels(want)).all { (a, b) -> abs(a - b) <= tolerance }
    }

    private fun channels(argb: Int) = listOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /** The label's amber, even blended into the board at a glyph's edge: red well above blue. */
    private fun amber(argb: Int): Boolean {
        val (r, _, b) = channels(argb)
        return r > 90 && r - b > 60
    }

    private companion object {
        val DARK = Color(0xFF101418)
        val STROKE = Color(0xFFE5E7EB)
        val TEXT = Color(0xFFF59E0B)
        val INK = Color(0xFF1F2937)
        const val MARGIN = 24f
        val PHONE = "phone" to (412 to 915)
        val DESKTOP = "desktop" to (1440 to 900)
    }
}
