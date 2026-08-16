package com.letta.mobile.web

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.attachment.ImageIngressPolicy
import com.letta.mobile.data.model.MessageContentPart
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlin.io.encoding.Base64

internal suspend fun pickWebImages(): List<MessageContentPart.Image> {
    val files: List<PlatformFile>? = FileKit.openFilePicker(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(maxItems = ImageIngressPolicy.MAX_FILES),
    )
    return files.orEmpty().map { file ->
        require(file.size() <= AttachmentLimits.Default.maxRawBytesPerImage) {
            "Image exceeds the ${AttachmentLimits.Default.maxRawBytesPerImage / (1024 * 1024)} MB browser upload limit."
        }
        encodeWebImage(file.name, file.readBytes())
    }
}

internal fun encodeWebImage(
    fileName: String,
    bytes: ByteArray,
    limits: AttachmentLimits = AttachmentLimits.Default,
): MessageContentPart.Image {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    require(ImageIngressPolicy.isSupportedExtension(extension)) {
        "Choose a ${ImageIngressPolicy.supportedFormatsLabel()} image."
    }
    require(bytes.size <= limits.maxRawBytesPerImage) {
        "Image exceeds the ${limits.maxRawBytesPerImage / (1024 * 1024)} MB browser upload limit."
    }
    val mediaType = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/$extension"
    }
    return MessageContentPart.Image(base64 = Base64.Default.encode(bytes), mediaType = mediaType)
}
