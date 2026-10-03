package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/** What the image picker attaches to and reports to. */
internal class ComposerImagePickerTarget(
    val maxAttachments: Int,
    val pendingCount: Int,
    val limits: AttachmentLimits,
    val onPicked: (MessageContentPart.Image) -> Unit,
    val onError: (String) -> Unit,
)

/** One picked image's bytes, read when its turn comes. */
internal typealias ComposerImageSource = suspend () -> ByteArray

/**
 * letta-mobile-bglj6.1: reads and encodes picked images in a scope owned by the page, not by one
 * composer panel. Docking and expanding compose a different panel instance; an encode running in
 * the old panel's scope would be cancelled with it and the image silently lost.
 *
 * Results go to the most recently composed panel's [ComposerImagePickerTarget] (every panel of a
 * page binds the same session, so any of them is the right one).
 */
internal class ComposerImageAttacher(private val scope: CoroutineScope) {
    private var target: ComposerImagePickerTarget? = null

    /** Called by each composed panel; the latest one receives results. */
    fun bind(target: ComposerImagePickerTarget) {
        this.target = target
    }

    /** Attaches [sources] in order, each as soon as it is encoded. */
    fun attach(sources: List<ComposerImageSource>): Job? {
        val current = target ?: return null
        return scope.launch { attachPicked(sources, current, latest = { target ?: current }) }
    }

    fun reportLimitReached() {
        val current = target ?: return
        scope.launch { current.onError(getString(Res.string.composer_attach_limit_reached, current.maxAttachments)) }
    }
}

/** The page's [ComposerImageAttacher]; null outside a chat page (the panel then owns one). */
internal val LocalComposerImageAttacher = staticCompositionLocalOf<ComposerImageAttacher?> { null }

/** An attacher living as long as the calling composable (the page). */
@Composable
internal fun rememberComposerImageAttacher(): ComposerImageAttacher {
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposerImageAttacher(scope) }
}

/**
 * The composer's image picker: the system photo picker on Android, a file dialog on desktop
 * (FileKit). Each picked image is scaled to the owner's [AttachmentLimits.maxLongestEdgePx],
 * turned upright where the platform reads EXIF, re-encoded (JPEG, or PNG with transparency) and
 * attached as base64. Picks are limited to the room left beside the images already on the draft.
 * Lifted from Android's rememberImageAttachmentPicker; the normalisation is the canvas's.
 *
 * The encode runs in the page's [LocalComposerImageAttacher], so switching mode mid-encode
 * still attaches. Returns a launcher; with no room left it reports the limit instead of
 * opening the picker.
 */
@Composable
internal fun rememberComposerImagePicker(target: ComposerImagePickerTarget): () -> Unit {
    val attacher = LocalComposerImageAttacher.current ?: rememberComposerImageAttacher()
    SideEffect { attacher.bind(target) }
    val room = (target.maxAttachments - target.pendingCount).coerceAtLeast(0)
    // Android's multi-select photo picker needs a limit of at least two; extras are dropped below.
    val launcher = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(maxItems = room.coerceAtLeast(MinPickerItems)),
    ) { files: List<PlatformFile>? ->
        if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
        attacher.attach(files.map(PlatformFile::asImageSource))
    }
    return remember(attacher, launcher, room) {
        {
            if (room == 0) attacher.reportLimitReached() else launcher.launch()
        }
    }
}

private const val MinPickerItems = 2

private fun PlatformFile.asImageSource(): ComposerImageSource = { readBytes() }

/**
 * [target] fixes the room (as it was when the person picked); results and errors go to [latest],
 * the panel on screen when each image is ready.
 */
private suspend fun attachPicked(
    sources: List<ComposerImageSource>,
    target: ComposerImagePickerTarget,
    latest: () -> ComposerImagePickerTarget,
) {
    val room = (target.maxAttachments - target.pendingCount).coerceAtLeast(0)
    if (sources.size > room) {
        target.onError(getString(Res.string.composer_attach_too_many, target.maxAttachments, sources.size - room))
    }
    // One at a time, in the order picked: each is attached as soon as it is ready.
    sources.take(room).forEach { source -> attachOne(source, target.limits, latest) }
}

private suspend fun attachOne(
    source: ComposerImageSource,
    limits: AttachmentLimits,
    latest: () -> ComposerImagePickerTarget,
) {
    val result = runCatching { withContext(Dispatchers.Default) { encodeAttachment(source(), limits) } }
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    val image = result.getOrNull()
    if (image != null) {
        latest().onPicked(image)
    } else {
        val reason = result.exceptionOrNull()?.message ?: getString(Res.string.composer_attach_not_an_image)
        latest().onError(getString(Res.string.composer_attach_failed, reason))
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
