package com.letta.mobile.cli.runtime

import com.github.ajalt.clikt.core.UsageError
import com.letta.mobile.data.model.MessageContentPart
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

internal fun readImageAttachments(values: List<String>): List<MessageContentPart.Image> =
    values.map { readImageAttachment(it) }

internal fun readImageAttachment(value: String): MessageContentPart.Image {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        throw UsageError("--image must not be blank")
    }
    return if (trimmed.startsWith("data:", ignoreCase = true)) {
        parseImageDataUrl(trimmed)
    } else {
        readImageFile(Path.of(trimmed))
    }
}

private fun parseImageDataUrl(dataUrl: String): MessageContentPart.Image {
    val commaIndex = dataUrl.indexOf(',')
    if (commaIndex <= 0) {
        throw UsageError("--image data URL must include a base64 payload")
    }
    val metadata = dataUrl.substring(startIndex = "data:".length, endIndex = commaIndex)
    val parts = metadata.split(';').filter { it.isNotBlank() }
    val mediaType = parts.firstOrNull()?.lowercase()
        ?: throw UsageError("--image data URL must include an image media type")
    if (!mediaType.startsWith("image/")) {
        throw UsageError("--image data URL must use an image/* media type, got $mediaType")
    }
    if (parts.none { it.equals("base64", ignoreCase = true) }) {
        throw UsageError("--image data URL must be base64 encoded")
    }
    return MessageContentPart.Image(
        base64 = normalizeBase64(dataUrl.substring(commaIndex + 1), "--image data URL"),
        mediaType = mediaType,
    )
}

private fun readImageFile(path: Path): MessageContentPart.Image {
    val normalized = path.toAbsolutePath().normalize()
    if (!Files.isRegularFile(normalized)) {
        throw UsageError("Image attachment not found: $path")
    }
    val mediaType = detectImageMediaType(normalized)
    val bytes = Files.readAllBytes(normalized)
    if (bytes.isEmpty()) {
        throw UsageError("Image attachment is empty: $path")
    }
    return MessageContentPart.Image(
        base64 = Base64.getEncoder().encodeToString(bytes),
        mediaType = mediaType,
    )
}

private fun detectImageMediaType(path: Path): String {
    val probed = runCatching { Files.probeContentType(path) }.getOrNull()
    val fallback = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> null
    }
    val mediaType = probed ?: fallback
        ?: throw UsageError("Could not determine image media type for: $path")
    if (!mediaType.startsWith("image/")) {
        throw UsageError("Attachment must be an image: $path (detected $mediaType)")
    }
    return mediaType
}

private fun normalizeBase64(value: String, label: String): String {
    val compact = value.filterNot { it.isWhitespace() }
    val bytes = try {
        Base64.getDecoder().decode(compact)
    } catch (e: IllegalArgumentException) {
        throw UsageError("$label contains invalid base64")
    }
    if (bytes.isEmpty()) {
        throw UsageError("$label contains an empty image payload")
    }
    return Base64.getEncoder().encodeToString(bytes)
}
