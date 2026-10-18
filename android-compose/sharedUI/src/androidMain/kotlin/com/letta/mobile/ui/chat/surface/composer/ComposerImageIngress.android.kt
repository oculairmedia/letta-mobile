package com.letta.mobile.ui.chat.surface.composer

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
import java.io.ByteArrayOutputStream

/**
 * Gboard paste and cross-app image drop land on the view's rich-content receiver; Ctrl/Cmd+V
 * reads the clipboard URI. Bytes go through [ComposerImageAttacher] so they share the picker's
 * downscale (letta-mobile-k5nbq.7).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun Modifier.composerImageIngress(attacher: ComposerImageAttacher): Modifier {
    val context = LocalContext.current
    val latest = rememberUpdatedState(attacher)
    val resolver = context.contentResolver
    return contentReceiver(
        receiveContentListener = ReceiveContentListener { content ->
            if (!content.hasMediaType(MediaType.Image)) return@ReceiveContentListener content
            val payloads = mutableListOf<ByteArray>()
            val remaining = content.consume { item ->
                val bytes = item.uri?.readImageBytes(resolver) ?: return@consume false
                payloads += bytes
                true
            }
            if (payloads.isNotEmpty()) latest.value.attach(imageBytesAsSources(payloads))
            remaining
        },
    ).onPreviewKeyEvent { event ->
        if (!event.isImagePasteChord()) return@onPreviewKeyEvent false
        val payloads = clipboardImageBytes(context)
        if (payloads.isEmpty()) return@onPreviewKeyEvent false
        latest.value.attach(imageBytesAsSources(payloads))
        true
    }
}

private fun androidx.compose.ui.input.key.KeyEvent.isImagePasteChord(): Boolean {
    if (type != KeyEventType.KeyDown || key != Key.V) return false
    return isCtrlPressed || isMetaPressed
}

private fun clipboardImageBytes(context: Context): List<ByteArray> {
    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { index ->
        clip.getItemAt(index).uri?.readImageBytes(context.contentResolver)
    }
}

private fun Uri.readImageBytes(resolver: ContentResolver): ByteArray? {
    val mime = resolver.getType(this).orEmpty()
    if (mime.isNotEmpty() && !mime.startsWith("image/")) return null
    return runCatching {
        resolver.openInputStream(this)?.use { stream ->
            val out = ByteArrayOutputStream()
            stream.copyTo(out)
            out.toByteArray()
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
