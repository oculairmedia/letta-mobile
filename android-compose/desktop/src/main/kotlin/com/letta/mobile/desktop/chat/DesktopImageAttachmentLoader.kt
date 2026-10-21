package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.ui.chat.surface.composer.encodeAttachment
import com.letta.mobile.ui.chat.surface.composer.readImageSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop paste/drop still reads AWT images; encoding is the shared composer path
 * (letta-mobile-k5nbq.7) so Android paste and the picker share the same downscale and byte cap.
 * A dropped or pasted file is refused by its size before it is read, and read capped, at the shared
 * ImageIngressPolicy.MAX_SOURCE_BYTES (letta-mobile-k5nbq.18).
 */
class DesktopImageAttachmentLoader(
    private val limits: AttachmentLimits = AttachmentLimits.Default,
) {
    suspend fun load(path: Path): MessageContentPart.Image = withContext(Dispatchers.IO) {
        encode(Files.newInputStream(path).use { readImageSource(knownSize = Files.size(path), read = it::read) })
    }

    suspend fun load(image: BufferedImage): MessageContentPart.Image = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        encode(bytes)
    }

    private fun encode(bytes: ByteArray): MessageContentPart.Image =
        encodeAttachment(bytes, limits) ?: error("Could not decode image")
}
