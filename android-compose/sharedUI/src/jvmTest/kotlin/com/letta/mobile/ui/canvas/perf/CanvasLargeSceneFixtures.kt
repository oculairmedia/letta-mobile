package com.letta.mobile.ui.canvas.perf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.TextAlignment
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic large boards for the canvas performance bench (letta-mobile-24zur).
 *
 * Elements are laid on a square grid of [CELL]-unit cells, one per cell, so a board of N elements
 * spans about sqrt(N) x [CELL] world units on a side and a 1280 x 800 viewport at zoom 1 sees only
 * a dozen or so of them, the way a big board is actually looked at. The mix is the one an agent
 * and a person leave behind together: freehand strokes (some pressure-varied), shapes with text,
 * curved connectors, text blocks and images; about 1.4 KB of scene JSON an element, close to the
 * 1.2 KB of a real 61-element board.
 */
internal object CanvasLargeSceneFixtures {
    const val CELL = 320f

    /** Points in a stroke. */
    const val STROKE_POINTS = 40

    fun scene(count: Int, seed: Int = 42, withImages: Boolean = true): PayLoad {
        val random = Random(seed)
        val side = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        val image = if (withImages) pngBytes(48) else null
        val elements = (0 until count).map { i ->
            Cell(i, Offset((i % side) * CELL, (i / side) * CELL)).element(random, image)
        }
        return PayLoad(bgColor = Color.White, elements = elements)
    }

    /** Element [index]'s cell on the board, its top-left corner at [origin]. */
    private class Cell(val index: Int, val origin: Offset) {
        fun at(x: Float, y: Float) = origin + Offset(x, y)

        fun element(random: Random, image: ByteArray?): Element = when (index % 10) {
            0, 1, 2 -> stroke(random, pressure = false)
            3 -> stroke(random, pressure = true)
            4, 5 -> shape()
            6 -> connector()
            7, 8 -> text()
            else -> if (image != null) image(image) else text()
        }

        private fun stroke(random: Random, pressure: Boolean): Element.Path {
            val phase = random.nextFloat() * 6.28f
            val samples = (0 until STROKE_POINTS).map { k ->
                val t = k / STROKE_POINTS.toFloat()
                val wave = sin(phase + t * 18f) * 80f + cos(t * 7f) * 20f
                val width = if (pressure) 2f + 6f * (0.5f + 0.5f * sin(t * 9f)) else 4f
                Element.PathSample(at(20f + t * 260f, 150f + wave), width)
            }
            return Element.Path(id = "p$index", samples = samples, strokeColor = Color(0xFF1E3A8A), strokeWidth = 4f, alpha = 1f, zIndex = index)
        }

        private fun shape() = Element.Shape(
            id = "s$index",
            shapeType = if (index % 20 < 10) ShapeType.RECTANGLE else ShapeType.CIRCLE,
            points = listOf(at(30f, 30f), at(290f, 230f)),
            strokeColor = Color(0xFF0F766E),
            fillColor = Color(0xFFCCFBF1),
            strokeWidth = 3f,
            cornerRadius = 12f,
            zIndex = index,
            text = "Shape $index\nwith a label",
        )

        private fun connector() = Element.Shape(
            id = "a$index",
            shapeType = ShapeType.ARROW,
            points = listOf(at(20f, 40f), at(300f, 280f)),
            strokeColor = Color(0xFF7C2D12),
            strokeWidth = 3f,
            bend = Offset(40f, -30f),
            zIndex = index,
        )

        private fun text() = Element.Text(
            id = "t$index",
            text = "Note $index: a few words that wrap across lines so the layout cost is real, not a single glyph run.",
            fontSize = 18f,
            color = Color.Black,
            alignment = TextAlignment.LEFT,
            topLeft = at(20f, 20f),
            wrapWidth = 260f,
            measuredHeight = 80f,
            zIndex = index,
        )

        private fun image(bytes: ByteArray) = Element.Image(
            id = "i$index",
            bytes = bytes,
            intrinsicSize = Size(256f, 256f),
            points = listOf(at(30f, 30f), at(286f, 286f)),
            zIndex = index,
        )
    }

    fun pngBytes(side: Int): ByteArray {
        val img = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until side) for (x in 0 until side) img.setRGB(x, y, (0xFF shl 24) or ((x * 255 / side) shl 16) or ((y * 255 / side) shl 8) or 0x80)
        return ByteArrayOutputStream().use { out -> ImageIO.write(img, "png", out); out.toByteArray() }
    }
}
