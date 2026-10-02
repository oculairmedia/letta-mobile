package com.letta.mobile.ui.canvas

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Web: decoded and scaled with Skia (the renderer the page already runs on), then re-encoded as
 * desktop does - JPEG, or PNG when the image has transparency. Browsers hand the bytes over
 * already oriented, so there is no EXIF step.
 */
internal actual fun prepareCanvasImage(bytes: ByteArray, maxEdge: Int): CanvasImage? {
    val source = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    val longest = max(source.width, source.height)
    val scale = if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(
        source,
        Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()),
    )
    val scaled = surface.makeImageSnapshot()
    surface.close()
    val opaque = source.imageInfo.colorAlphaType == ColorAlphaType.OPAQUE
    val format = if (opaque) EncodedImageFormat.JPEG else EncodedImageFormat.PNG
    val encoded = scaled.encodeToData(format, JPEG_QUALITY)?.bytes ?: return null
    return CanvasImage(encoded, width, height)
}

private const val JPEG_QUALITY = 85
