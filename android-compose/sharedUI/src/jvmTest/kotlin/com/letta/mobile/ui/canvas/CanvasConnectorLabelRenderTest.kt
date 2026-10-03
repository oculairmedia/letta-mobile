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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
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

    /** A board to render on: its colour and its size in pixels, one world unit to a pixel. */
    private data class Board(val colour: Color, val size: IntSize)

    /** One rendered pixel. */
    @JvmInline
    private value class Pixel(val argb: Int) {
        private val channels: List<Int> get() = listOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

        fun near(colour: Color, tolerance: Int = 40): Boolean =
            channels.zip(Pixel(colour.toArgb()).channels).all { (a, b) -> abs(a - b) <= tolerance }

        /** The label's amber, even blended into the board at a glyph's edge: red well above blue. */
        val amber: Boolean get() = channels.let { (r, _, b) -> r > 90 && r - b > 60 }
    }

    @Test
    fun onADarkBoardTheChipIsTheBoardNotTheStrokeOrWhite() {
        val arrow = arrow(Offset(100f, 200f), Offset(500f, 200f), text = "ship")
        val image = render(listOf(arrow), DARK_600, "dark-board.png")
        // Without a label the shaft runs through the midpoint in the stroke's colour.
        val bare = render(listOf(arrow.copy(text = "")), DARK_600, "dark-board-bare.png")
        assertTrue(bare.at(IntOffset(300, 200)).near(STROKE), "the bare shaft should cross the midpoint")

        // The chip spans about 300 +- 24 on the shaft; sample well inside it.
        val row = image.row(200, 284..316)
        assertTrue(row.none { it.near(STROKE) }, "the shaft shows through the label")
        assertTrue(row.any { it.near(DARK, tolerance = 2) }, "no board-coloured chip on the shaft")
        val label = image.pixels(around(IntOffset(300, 200), IntSize(18, 10)))
        assertTrue(label.none { it.near(Color.White, tolerance = 15) }, "a white chip")
        assertTrue(label.any { it.amber }, "the label's text is not drawn")
        // The rest of the shaft is still drawn.
        assertTrue(image.at(IntOffset(150, 200)).near(STROKE))
        assertTrue(image.at(IntOffset(440, 200)).near(STROKE))
    }

    @Test
    fun aBentArrowsLabelSitsOnTheCurveNotTheChord() {
        // The curve passes 80 above the chord's midpoint: half the bend.
        val bent = arrow(Offset(100f, 300f), Offset(500f, 300f), text = "ship").copy(bend = Offset(0f, -160f))
        val image = render(listOf(bent), DARK_600, "dark-board-bent.png")
        assertTrue(image.pixels(around(IntOffset(300, 220), IntSize(50, 16))).any { it.amber }, "no label on the curve's midpoint")
        assertTrue(image.pixels(around(IntOffset(300, 300), IntSize(50, 16))).none { it.amber }, "the label is on the chord")
        assertTrue(image.row(220, 286..314).none { it.near(STROKE) }, "the curve shows through the label")
    }

    @Test
    fun aShortArrowKeepsItsShaftAndHeadAndTakesTheLabelAboveIt() {
        // 84 units at the default font size: a chip on the shaft would leave no line, only a head.
        val short = arrow(Offset(100f, 200f), Offset(184f, 200f), text = "sort")
        val image = render(listOf(short), Board(DARK, IntSize(300, 300)), "dark-board-short.png")
        val headStart = 184 - 26
        image.row(200, 104 until headStart - 2).forEachIndexed { i, pixel ->
            assertTrue(pixel.near(STROKE), "the shaft is broken at ${104 + i}")
        }
        assertTrue(image.pixels(around(IntOffset(142, 180), IntSize(40, 14))).any { it.amber }, "no label above the shaft")
        assertTrue(image.pixels(around(IntOffset(142, 222), IntSize(40, 14))).none { it.amber }, "the label is below the shaft")
        assertTrue(image.row(200, 150..186).none { it.amber }, "the label is over the arrowhead")
    }

    /**
     * Not an assertion of looks: the gallery the visual review reads. Light and dark boards at a
     * phone's and a desktop's size, with long, bent, smoothed, short, wrapped, CJK, line and
     * vertical connectors.
     */
    @Test
    fun rendersTheReviewGallery() {
        listOf("phone" to PHONE, "desktop" to DESKTOP).forEach { (name, size) ->
            listOf("light" to (Color.White to INK), "dark" to (DARK to STROKE)).forEach { (theme, colours) ->
                val (board, ink) = colours
                val image = render(gallery(size.width.toFloat(), ink), Board(board, size), "gallery-$name-$theme.png")
                assertEquals(size.width, image.width)
            }
        }
    }

    private fun gallery(width: Float, ink: Color): List<Element> {
        val right = width - MARGIN
        val half = width / 2f
        fun a(from: Offset, to: Offset, text: String, size: Float) =
            arrow(from, to, text).copy(fontSize = size, strokeColor = ink, textColor = null)
        return listOf(
            a(Offset(MARGIN, 70f), Offset(right, 70f), "ship it", 20f),
            a(Offset(MARGIN, 220f), Offset(right, 220f), "bent label", 16f).copy(bend = Offset(0f, -120f)),
            a(Offset(MARGIN + 16f, 270f), Offset(right - 16f, 380f), "smoothed", 13f)
                .copy(startHandle = Offset(0f, 110f), endHandle = Offset(-(right - MARGIN) * 0.4f, 0f)),
            a(Offset(MARGIN, 470f), Offset(MARGIN + 84f, 470f), "sort", 13f),
            a(Offset(half, 470f), Offset(half + 90f, 470f), "next", 20f),
            a(Offset(MARGIN, 560f), Offset(MARGIN + 84f, 560f), "file", 20f),
            a(Offset(half, 560f), Offset(half + 90f, 560f), "ok", 13f),
            a(Offset(MARGIN, 660f), Offset(right, 660f), "a much longer label that keeps going until it has to wrap onto another line", 14f),
            a(Offset(MARGIN, 760f), Offset(half - 10f, 760f), "流程图", 16f),
            a(Offset(half + 10f, 760f), Offset(right, 760f), "线 line", 16f).copy(shapeType = ShapeType.LINE),
            a(Offset(right - 40f, 800f), Offset(right - 40f, 890f), "up", 13f),
            a(Offset(MARGIN + 40f, 800f), Offset(MARGIN + 40f, 890f), "down", 20f),
        )
    }

    /** A 3-unit arrow in [STROKE] with [text] in amber at the default size, 20. */
    private fun arrow(start: Offset, end: Offset, text: String) = Element.Shape(
        id = "arrow-${start.x}-${start.y}-${end.x}-${end.y}",
        shapeType = ShapeType.ARROW,
        points = listOf(start, end),
        strokeColor = STROKE,
        strokeWidth = 3f,
        text = text,
        textColor = TEXT,
        fontSize = 20f,
    )

    /** [elements] drawn by DrawingPreview on [board] at zoom 1, one world unit to a pixel. */
    private fun render(elements: List<Element>, board: Board, snapshot: String): BufferedImage {
        lateinit var image: BufferedImage
        runDesktopComposeUiTest(width = board.size.width, height = board.size.height) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    DrawingPreview(elements = elements, bgColor = board.colour)
                }
            }
            waitForIdle()
            image = onRoot().captureToImage().toAwtImage()
        }
        val file = File("build/canvas-connector-labels").apply { mkdirs() }.resolve(snapshot)
        ImageIO.write(image, "png", file)
        println("canvas-connector-labels snapshot: ${file.absolutePath} (${board.size})")
        return image
    }

    private fun around(centre: IntOffset, half: IntSize) =
        IntRect(centre.x - half.width, centre.y - half.height, centre.x + half.width, centre.y + half.height)

    private fun BufferedImage.at(point: IntOffset) = Pixel(getRGB(point.x, point.y))

    private fun BufferedImage.row(y: Int, xs: IntRange): List<Pixel> = xs.map { x -> Pixel(getRGB(x, y)) }

    private fun BufferedImage.pixels(area: IntRect): List<Pixel> =
        (area.top..area.bottom).flatMap { y -> row(y, area.left..area.right) }

    private companion object {
        val DARK = Color(0xFF101418)
        val STROKE = Color(0xFFE5E7EB)
        val TEXT = Color(0xFFF59E0B)
        val INK = Color(0xFF1F2937)
        const val MARGIN = 24f
        val PHONE = IntSize(412, 915)
        val DESKTOP = IntSize(1440, 900)
        val DARK_600 = Board(DARK, IntSize(600, 400))
    }
}
