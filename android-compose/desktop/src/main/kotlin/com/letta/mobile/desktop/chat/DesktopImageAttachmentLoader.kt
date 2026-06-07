package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopImageAttachmentLoader(
    private val limits: AttachmentLimits = AttachmentLimits.Default,
) {
    suspend fun load(path: Path): MessageContentPart.Image = withContext(Dispatchers.IO) {
        val source = ImageIO.read(path.toFile()) ?: error("Could not decode image")
        val scaled = source.scaleToMaxEdge(limits.maxLongestEdgePx)
        val encoded = scaled.encodeJpegUnderByteCap(limits)
        MessageContentPart.Image(
            base64 = Base64.getEncoder().encodeToString(encoded),
            mediaType = "image/jpeg",
        )
    }
}

private fun BufferedImage.scaleToMaxEdge(maxEdge: Int): BufferedImage {
    val longest = maxOf(width, height)
    if (longest <= maxEdge && type == BufferedImage.TYPE_INT_RGB) return this

    val scale = if (longest <= maxEdge) 1.0 else maxEdge.toDouble() / longest.toDouble()
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.drawImage(this, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    return target
}

private fun BufferedImage.encodeJpegUnderByteCap(limits: AttachmentLimits): ByteArray {
    var lastBytes = ByteArray(0)
    for (quality in limits.jpegQualityFallbackLadder()) {
        lastBytes = encodeJpeg(quality)
        if (lastBytes.size <= limits.maxRawBytesPerImage) return lastBytes
    }
    return lastBytes
}

private fun BufferedImage.encodeJpeg(quality: Int): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
        ?: error("No JPEG writer available")
    val output = ByteArrayOutputStream()
    try {
        MemoryCacheImageOutputStream(output).use { imageOutput ->
            writer.output = imageOutput
            val params = writer.defaultWriteParam
            if (params.canWriteCompressed()) {
                params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                params.compressionQuality = quality.coerceIn(1, 100) / 100f
            }
            writer.write(null, IIOImage(this, null, null), params)
        }
    } finally {
        writer.dispose()
    }
    return output.toByteArray()
}
