package com.letta.mobile.feature.chat.parity

import android.graphics.Bitmap
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** A captured side as ARGB pixels. */
internal class Pixels(val width: Int, val height: Int, val argb: IntArray) {
    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    fun toBitmap(): Bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

    companion object {
        fun of(bitmap: Bitmap): Pixels {
            val out = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return Pixels(bitmap.width, bitmap.height, out)
        }

        fun filled(width: Int, height: Int, argb: Int) = Pixels(width, height, IntArray(width * height) { argb })
    }
}

/** What one comparison found. */
internal data class ParityDiff(
    val legacyHeightPx: Int,
    val sharedHeightPx: Int,
    val differingPixels: Long,
    val comparedPixels: Long,
) {
    val percent: Double get() = if (comparedPixels == 0L) 0.0 else differingPixels * PERCENT / comparedPixels

    private companion object {
        const val PERCENT = 100.0
    }
}

/**
 * The pixel maths of the parity harness. Each side is cropped vertically to its own content (rows
 * that differ from the side's background, the corner pixel), so a list anchored at the bottom and
 * one anchored at the top still compare row against row. Both are then laid top-aligned on the
 * taller height; a pixel differs when any channel moves by more than [CHANNEL_TOLERANCE], which
 * absorbs anti-aliasing but not a colour, a weight or a shift.
 */
internal object RowParityImages {
    const val CHANNEL_TOLERANCE = 24
    private const val CONTENT_PAD_PX = 8
    private const val GUTTER_PX = 8
    private const val DIFF_RED = 0xFFFF1744.toInt()
    private const val GUTTER = 0xFF7F7F7F.toInt()
    private const val FADE_ALPHA = 0.25

    fun differs(a: Int, b: Int): Boolean =
        abs(channel(a, 16) - channel(b, 16)) > CHANNEL_TOLERANCE ||
            abs(channel(a, 8) - channel(b, 8)) > CHANNEL_TOLERANCE ||
            abs(channel(a, 0) - channel(b, 0)) > CHANNEL_TOLERANCE ||
            abs(channel(a, 24) - channel(b, 24)) > CHANNEL_TOLERANCE

    private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and 0xFF

    /** [p] cut to the rows that hold content, padded; one background row when there is none. */
    fun cropToContent(p: Pixels): Pixels {
        val bg = p[0, 0]
        var top = -1
        var bottom = -1
        for (y in 0 until p.height) {
            val row = y * p.width
            val hasContent = (0 until p.width).any { x -> differs(p.argb[row + x], bg) }
            if (hasContent) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) return Pixels.filled(p.width, 1, bg)
        val from = max(0, top - CONTENT_PAD_PX)
        val to = minOf(p.height - 1, bottom + CONTENT_PAD_PX)
        val height = to - from + 1
        return Pixels(p.width, height, p.argb.copyOfRange(from * p.width, (to + 1) * p.width))
    }

    /** [p] on a [width] x [height] canvas of its background, top-left aligned. */
    private fun padTo(p: Pixels, width: Int, height: Int): Pixels {
        if (p.width == width && p.height == height) return p
        val out = IntArray(width * height) { p[0, 0] }
        for (y in 0 until p.height) for (x in 0 until p.width) out[y * width + x] = p[x, y]
        return Pixels(width, height, out)
    }

    class Result(val legacy: Pixels, val shared: Pixels, val diff: Pixels, val summary: ParityDiff)

    fun compare(legacyRaw: Pixels, sharedRaw: Pixels): Result {
        val legacyCrop = cropToContent(legacyRaw)
        val sharedCrop = cropToContent(sharedRaw)
        val width = max(legacyCrop.width, sharedCrop.width)
        val height = max(legacyCrop.height, sharedCrop.height)
        val legacy = padTo(legacyCrop, width, height)
        val shared = padTo(sharedCrop, width, height)
        val diff = IntArray(width * height)
        var differing = 0L
        for (i in diff.indices) {
            if (differs(legacy.argb[i], shared.argb[i])) {
                differing++
                diff[i] = DIFF_RED
            } else {
                diff[i] = faded(legacy.argb[i])
            }
        }
        val summary = ParityDiff(legacyCrop.height, sharedCrop.height, differing, diff.size.toLong())
        return Result(legacy, shared, Pixels(width, height, diff), summary)
    }

    /** The unchanged pixel as a faint grey, so the red reads against the row's outline. */
    private fun faded(argb: Int): Int {
        val luma = (channel(argb, 16) * 3 + channel(argb, 8) * 6 + channel(argb, 0)) / 10
        val v = (0xFF - (0xFF - luma) * FADE_ALPHA).toInt().coerceIn(0, 0xFF)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** legacy | shared | diff, separated by grey gutters. */
    fun sideBySide(result: Result): Pixels {
        val panes = listOf(result.legacy, result.shared, result.diff)
        val width = panes.sumOf { it.width } + GUTTER_PX * (panes.size - 1)
        val height = panes.maxOf { it.height }
        val out = IntArray(width * height) { GUTTER }
        var left = 0
        for (pane in panes) {
            for (y in 0 until pane.height) for (x in 0 until pane.width) out[y * width + left + x] = pane[x, y]
            left += pane.width + GUTTER_PX
        }
        return Pixels(width, height, out)
    }

    fun write(p: Pixels, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { p.toBitmap().compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    private const val PNG_QUALITY = 100
}
