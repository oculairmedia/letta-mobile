package com.letta.mobile.feature.chat.parity

import android.graphics.Bitmap
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** One ARGB pixel, compared and faded channel by channel. */
@JvmInline
internal value class Argb(val packed: Int) {
    private val channels: List<Int> get() = SHIFTS.map { (packed ushr it) and BYTE }

    /** True when any channel moves by more than [RowParityImages.CHANNEL_TOLERANCE]. */
    fun differsFrom(other: Argb): Boolean =
        channels.zip(other.channels).any { (a, b) -> abs(a - b) > RowParityImages.CHANNEL_TOLERANCE }

    /** This pixel as a faint grey, so a diff's red reads against the row's outline. */
    fun faded(): Argb {
        val (_, r, g, b) = channels
        val luma = (r * LUMA_R + g * LUMA_G + b * LUMA_B) / LUMA_SUM
        val v = (BYTE - (BYTE - luma) * FADE_ALPHA).toInt().coerceIn(0, BYTE)
        return Argb((BYTE shl ALPHA_SHIFT) or (v shl RED_SHIFT) or (v shl GREEN_SHIFT) or v)
    }

    companion object {
        private const val BYTE = 0xFF
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private val SHIFTS = listOf(ALPHA_SHIFT, RED_SHIFT, GREEN_SHIFT, 0)
        private const val LUMA_R = 3
        private const val LUMA_G = 6
        private const val LUMA_B = 1
        private const val LUMA_SUM = 10
        private const val FADE_ALPHA = 0.25

        val DIFF_RED = Argb(0xFFFF1744.toInt())
        val GUTTER = Argb(0xFF7F7F7F.toInt())
    }
}

/** A capture's pixel size. */
internal data class PixelSize(val width: Int, val height: Int) {
    val area: Int get() = width * height

    fun union(other: PixelSize) = PixelSize(max(width, other.width), max(height, other.height))
}

/** A captured side as ARGB pixels, row-major. */
internal class Pixels(val size: PixelSize, val argb: IntArray) {
    val width: Int get() = size.width
    val height: Int get() = size.height

    fun at(x: Int, y: Int): Argb = Argb(argb[y * width + x])

    val background: Argb get() = at(0, 0)

    fun rowHasContent(y: Int): Boolean {
        val bg = background
        return (0 until width).any { x -> at(x, y).differsFrom(bg) }
    }

    /** Rows [rows] of this image, full width. */
    fun rows(rows: IntRange): Pixels =
        Pixels(PixelSize(width, rows.last - rows.first + 1), argb.copyOfRange(rows.first * width, (rows.last + 1) * width))

    /** This image drawn top-left onto a [target] canvas of its own background. */
    fun paddedTo(target: PixelSize): Pixels {
        if (size == target) return this
        val out = IntArray(target.area) { background.packed }
        for (y in 0 until height) argb.copyInto(out, y * target.width, y * width, (y + 1) * width)
        return Pixels(target, out)
    }

    fun toBitmap(): Bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

    companion object {
        fun of(bitmap: Bitmap): Pixels {
            val out = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return Pixels(PixelSize(bitmap.width, bitmap.height), out)
        }

        fun filled(size: PixelSize, color: Argb) = Pixels(size, IntArray(size.area) { color.packed })
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
    private const val PNG_QUALITY = 100

    /** [p] cut to the rows that hold content, padded; one background row when there is none. */
    fun cropToContent(p: Pixels): Pixels {
        val contentRows = (0 until p.height).filter(p::rowHasContent)
        if (contentRows.isEmpty()) return Pixels.filled(PixelSize(p.width, 1), p.background)
        val from = max(0, contentRows.first() - CONTENT_PAD_PX)
        val to = minOf(p.height - 1, contentRows.last() + CONTENT_PAD_PX)
        return p.rows(from..to)
    }

    class Result(val legacy: Pixels, val shared: Pixels, val diff: Pixels, val summary: ParityDiff)

    fun compare(legacyRaw: Pixels, sharedRaw: Pixels): Result {
        val legacyCrop = cropToContent(legacyRaw)
        val sharedCrop = cropToContent(sharedRaw)
        val size = legacyCrop.size.union(sharedCrop.size)
        val legacy = legacyCrop.paddedTo(size)
        val shared = sharedCrop.paddedTo(size)
        var differing = 0L
        val diff = IntArray(size.area) { i ->
            val before = Argb(legacy.argb[i])
            if (before.differsFrom(Argb(shared.argb[i]))) {
                differing++
                Argb.DIFF_RED.packed
            } else {
                before.faded().packed
            }
        }
        val summary = ParityDiff(legacyCrop.height, sharedCrop.height, differing, size.area.toLong())
        return Result(legacy, shared, Pixels(size, diff), summary)
    }

    /** legacy | shared | diff, separated by grey gutters. */
    fun sideBySide(result: Result): Pixels {
        val panes = listOf(result.legacy, result.shared, result.diff)
        val size = PixelSize(panes.sumOf { it.width } + GUTTER_PX * (panes.size - 1), panes.maxOf { it.height })
        val out = IntArray(size.area) { Argb.GUTTER.packed }
        var left = 0
        for (pane in panes) {
            for (y in 0 until pane.height) pane.argb.copyInto(out, y * size.width + left, y * pane.width, (y + 1) * pane.width)
            left += pane.width + GUTTER_PX
        }
        return Pixels(size, out)
    }

    fun write(p: Pixels, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { p.toBitmap().compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }
}
