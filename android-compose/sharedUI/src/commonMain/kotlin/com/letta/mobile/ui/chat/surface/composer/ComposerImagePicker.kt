package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_attach_failed
import com.letta.mobile.sharedui.resources.composer_attach_limit_reached
import com.letta.mobile.sharedui.resources.composer_attach_not_an_image
import com.letta.mobile.sharedui.resources.composer_attach_too_many
import com.letta.mobile.ui.canvas.prepareCanvasImage
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/** What the image picker attaches to and reports to. */
internal class ComposerImagePickerTarget(
    val maxAttachments: Int,
    val pendingCount: Int,
    val onPicked: (MessageContentPart.Image) -> Unit,
    val onError: (String) -> Unit,
)

/**
 * The composer's image picker: the system photo picker on Android, a file dialog on desktop
 * (FileKit). Each picked image is scaled to [AttachmentLimits.maxLongestEdgePx], turned upright
 * where the platform reads EXIF, re-encoded (JPEG, or PNG with transparency) and attached as
 * base64. Picks are limited to the room left beside the images already on the draft.
 * Lifted from Android's rememberImageAttachmentPicker; the normalisation is the canvas's.
 *
 * Returns a launcher; with no room left it reports the limit instead of opening the picker.
 */
@Composable
internal fun rememberComposerImagePicker(target: ComposerImagePickerTarget): () -> Unit {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(target)
    val room = (target.maxAttachments - target.pendingCount).coerceAtLeast(0)
    // Android's multi-select photo picker needs a limit of at least two; extras are dropped below.
    val launcher = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(maxItems = room.coerceAtLeast(MinPickerItems)),
    ) { files: List<PlatformFile>? ->
        if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
        scope.launch { attachPicked(files, current) }
    }
    return remember(launcher, room) {
        {
            if (room == 0) {
                scope.launch { current.onError(getString(Res.string.composer_attach_limit_reached, current.maxAttachments)) }
            } else {
                launcher.launch()
            }
        }
    }
}

private const val MinPickerItems = 2

private suspend fun attachPicked(files: List<PlatformFile>, target: ComposerImagePickerTarget) {
    val room = (target.maxAttachments - target.pendingCount).coerceAtLeast(0)
    if (files.size > room) {
        target.onError(getString(Res.string.composer_attach_too_many, target.maxAttachments, files.size - room))
    }
    // One at a time, in the order picked: each is attached as soon as it is ready.
    files.take(room).forEach { file -> attachOne(file, target) }
}

private suspend fun attachOne(file: PlatformFile, target: ComposerImagePickerTarget) {
    val result = runCatching { withContext(Dispatchers.Default) { encodeAttachment(file.readBytes()) } }
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    val image = result.getOrNull()
    if (image != null) {
        target.onPicked(image)
    } else {
        val reason = result.exceptionOrNull()?.message ?: getString(Res.string.composer_attach_not_an_image)
        target.onError(getString(Res.string.composer_attach_failed, reason))
    }
}

/** [bytes] as an attachment, or null when they are not an image the platform decodes. */
@OptIn(ExperimentalEncodingApi::class)
internal fun encodeAttachment(
    bytes: ByteArray,
    limits: AttachmentLimits = AttachmentLimits.Default,
): MessageContentPart.Image? {
    val prepared = prepareCanvasImage(bytes, limits.maxLongestEdgePx) ?: return null
    return MessageContentPart.Image(
        base64 = Base64.Default.encode(prepared.bytes),
        mediaType = if (prepared.bytes.isPng()) "image/png" else "image/jpeg",
    )
}

private val PngSignature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

private fun ByteArray.isPng(): Boolean =
    size >= PngSignature.size && PngSignature.indices.all { this[it] == PngSignature[it] }
