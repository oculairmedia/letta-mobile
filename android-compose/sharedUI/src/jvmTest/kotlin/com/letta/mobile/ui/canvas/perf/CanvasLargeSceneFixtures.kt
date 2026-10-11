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
 * Synthetic large boards for the canvas performance bench (letta-mobile canvas-perf epic).
 *
 * Elements are laid on a square grid of [CELL]-unit cells, one per cell, so a board of N elements
 * spans about sqrt(N) x [CELL] world units on a side and a 1280 x 800 viewport at zoom 1 sees only
 * a dozen or so of them, the way a big board is actually looked at. The mix is the one an agent
 * and a person leave behind together: long freehand strokes (some pressure-varied), shapes with
 * text, connectors, text blocks and images.
 */
internal object CanvasLargeSceneFixtures {
    const val CELL = 320f

    /** Points in a long stroke. */
    const val STROKE_POINTS = 40

    fun scene(count: Int, seed: Int = 42, withImages: Boolean = true): PayLoad {
        val random = Random(seed)
        val side = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        val image = if (withImages) pngBytes(48) else null
        val elements = (0 until count).map { i ->
            val origin = Offset((i % side) * CELL, (i / side) * CELL)
            element(i, origin, random, image)
        }
        return PayLoad(bgColor = Color.White, elements = elements)
    }

    private fun element(i: Int, origin: Offset, random: Random, image: ByteArray?): Element = when (i % 10) {
        0, 1, 2 -> stroke(i, origin, random, pressure = false)
        3 -> stroke(i, origin, random, pressure = true)
        4, 5 -> shape(i, origin, if (i % 20 < 10) ShapeType.RECTANGLE else ShapeType.CIRCLE, text = "Shape $i\nwith a label")
        6 -> connector(i, origin)
        7, 8 -> text(i, origin)
        else -> if (image != null) image(i, origin, image) else text(i, origin)
    }

    private fun stroke(i: Int, origin: Offset, random: Random, pressure: Boolean): Element.Path {
        val phase = random.nextFloat() * 6.28f
        val samples = (0 until STROKE_POINTS).map { k ->
            val t = k / STROKE_POINTS.toFloat()
            val x = origin.x + 20f + t * 260f
            val y = origin.y + 150f + sin(phase + t * 18f) * 80f + cos(t * 7f) * 20f
            val width = if (pressure) 2f + 6f * (0.5f + 0.5f * sin(t * 9f)) else 4f
            Element.PathSample(Offset(x, y), width)
        }
        return Element.Path(id = "p$i", samples = samples, strokeColor = Color(0xFF1E3A8A), strokeWidth = 4f, alpha = 1f, zIndex = i)
    }

    private fun shape(i: Int, origin: Offset, type: ShapeType, text: String) = Element.Shape(
        id = "s$i",
        shapeType = type,
        points = listOf(origin + Offset(30f, 30f), origin + Offset(290f, 230f)),
        strokeColor = Color(0xFF0F766E),
        fillColor = Color(0xFFCCFBF1),
        strokeWidth = 3f,
        cornerRadius = 12f,
        zIndex = i,
        text = text,
    )

    private fun connector(i: Int, origin: Offset) = Element.Shape(
        id = "a$i",
        shapeType = ShapeType.ARROW,
        points = listOf(origin + Offset(20f, 40f), origin + Offset(300f, 280f)),
        strokeColor = Color(0xFF7C2D12),
        strokeWidth = 3f,
        bend = Offset(40f, -30f),
        zIndex = i,
    )

    private fun text(i: Int, origin: Offset) = Element.Text(
        id = "t$i",
        text = "Note $i: a few words that wrap across lines so the layout cost is real, not a single glyph run.",
        fontSize = 18f,
        color = Color.Black,
        alignment = TextAlignment.LEFT,
        topLeft = origin + Offset(20f, 20f),
        wrapWidth = 260f,
        measuredHeight = 80f,
        zIndex = i,
    )

    private fun image(i: Int, origin: Offset, bytes: ByteArray) = Element.Image(
        id = "i$i",
        bytes = bytes,
        intrinsicSize = Size(256f, 256f),
        points = listOf(origin + Offset(30f, 30f), origin + Offset(286f, 286f)),
        zIndex = i,
    )

    fun pngBytes(side: Int): ByteArray {
        val img = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until side) for (x in 0 until side) img.setRGB(x, y, (0xFF shl 24) or ((x * 255 / side) shl 16) or ((y * 255 / side) shl 8) or 0x80)
        return ByteArrayOutputStream().use { out -> ImageIO.write(img, "png", out); out.toByteArray() }
    }
}
