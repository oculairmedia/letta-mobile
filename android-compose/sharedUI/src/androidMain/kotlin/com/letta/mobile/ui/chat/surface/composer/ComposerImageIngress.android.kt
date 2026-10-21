package com.letta.mobile.ui.chat.surface.composer

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import com.letta.mobile.data.attachment.ImageIngressPolicy
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_attach_not_an_image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * Gboard paste and cross-app image drop land on the view's rich-content receiver; Ctrl/Cmd+V
 * reads the clipboard. Only the URIs are taken on the main thread: each image is read later on the
 * IO dispatcher, capped at [ImageIngressPolicy.MAX_SOURCE_BYTES], then shares the picker's
 * downscale through [ComposerImageAttacher] (.15). The attacher starts
 * reading as soon as the gesture lands, while the sender's read grant is still held.
 */
internal actual val platformComposerImageIngress: (@Composable (ComposerImageAttacher) -> Modifier)? =
    { attacher -> Modifier.androidImageIngress(attacher) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.androidImageIngress(attacher: ComposerImageAttacher): Modifier {
    val context = LocalContext.current
    val latest = rememberUpdatedState(attacher)
    val resolver = context.contentResolver
    return contentReceiver(
        receiveContentListener = ReceiveContentListener { content ->
            if (!content.hasMediaType(MediaType.Image)) return@ReceiveContentListener content
            val uris = mutableListOf<Uri>()
            val remaining = content.consume { item -> item.uri?.also(uris::add) != null }
            if (uris.isNotEmpty()) latest.value.attach(uris.map { it.asImageSource(resolver) })
            remaining
        },
    ).onPreviewKeyEvent { event ->
        if (!event.isImagePasteChord()) return@onPreviewKeyEvent false
        val uris = clipboardImageUris(context)
        if (uris.isEmpty()) return@onPreviewKeyEvent false
        latest.value.attach(uris.map { it.asImageSource(resolver) })
        true
    }
}

private fun androidx.compose.ui.input.key.KeyEvent.isImagePasteChord(): Boolean {
    if (type != KeyEventType.KeyDown || key != Key.V) return false
    return isCtrlPressed || isMetaPressed
}

/** The clipboard's image URIs, decided from its description alone (no provider round trip). */
private fun clipboardImageUris(context: Context): List<Uri> {
    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        ?: return emptyList()
    if (!clip.description.hasMimeType(ImageMimeTypes)) return emptyList()
    return (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }
}

/** The image behind this URI, read on the IO dispatcher when the attacher gets to it. */
private fun Uri.asImageSource(resolver: ContentResolver): ComposerImageSource = {
    withContext(Dispatchers.IO) {
        val mime = resolver.getType(this@asImageSource).orEmpty()
        require(mime.isEmpty() || ClipDescription.compareMimeTypes(mime, ImageMimeTypes)) {
            getString(Res.string.composer_attach_not_an_image)
        }
        val stream = checkNotNull(resolver.openInputStream(this@asImageSource)) {
            getString(Res.string.composer_attach_not_an_image)
        }
        stream.use { readImageSource(knownSize = null, read = it::read) }
    }
}

private const val ImageMimeTypes = "image/*"
