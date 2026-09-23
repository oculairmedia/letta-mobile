package com.letta.mobile.ui.canvas

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

internal actual fun prepareCanvasImage(bytes: ByteArray, maxEdge: Int): CanvasImage? {
    val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
    val longest = max(source.width, source.height)
    val scale = if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)
    val alpha = source.colorModel.hasAlpha()
    val target = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    target.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        drawImage(source, 0, 0, width, height, null)
        dispose()
    }
    val encoded = if (alpha) png(target) else jpeg(target)
    return CanvasImage(encoded, width, height)
}

private fun png(image: BufferedImage): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

private fun jpeg(image: BufferedImage): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val out = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(out).use { stream ->
        writer.output = stream
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = JPEG_QUALITY
        }
        writer.write(null, IIOImage(image, null, null), params)
        writer.dispose()
    }
    return out.toByteArray()
}

private const val JPEG_QUALITY = 0.85f
