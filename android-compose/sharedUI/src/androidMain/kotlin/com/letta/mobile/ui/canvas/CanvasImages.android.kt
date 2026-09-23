package com.letta.mobile.ui.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal actual fun prepareCanvasImage(bytes: ByteArray, maxEdge: Int): CanvasImage? {
    // Measure first, then decode at the smallest power-of-two sample that still covers maxEdge,
    // so a 12-megapixel photo never has to fit in memory at full size.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val upright = rotateUpright(decoded, bytes)
    val longest = max(upright.width, upright.height)
    val scaled = if (longest > maxEdge) {
        val scale = maxEdge.toFloat() / longest
        Bitmap.createScaledBitmap(
            upright,
            (upright.width * scale).roundToInt().coerceAtLeast(1),
            (upright.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        upright
    }
    val out = ByteArrayOutputStream()
    val format = if (scaled.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    scaled.compress(format, JPEG_QUALITY, out)
    return CanvasImage(out.toByteArray(), scaled.width, scaled.height)
}

/**
 * A phone photo's pixels are stored as the sensor saw them; EXIF says which way is up, and for
 * some front cameras that it is mirrored too (all eight orientations).
 */
private fun rotateUpright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
    val exif = runCatching { ExifInterface(bytes.inputStream()) }.getOrNull() ?: return bitmap
    val degrees = exif.rotationDegrees.toFloat()
    val flipped = exif.isFlipped
    if (degrees == 0f && !flipped) return bitmap
    val matrix = Matrix().apply {
        if (flipped) postScale(-1f, 1f)
        postRotate(degrees)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private const val JPEG_QUALITY = 85
