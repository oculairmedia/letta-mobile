@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.letta.mobile.data.attachment.ImageIngressPolicy
import kotlin.js.Promise
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get

/**
 * The browser's image paste and file drop. Pasting takes the clipboard's
 * image files while the composer has focus (so a paste meant for the canvas stays there); a drop
 * anywhere on the page attaches, as desktop's window drop does. Files past
 * [ImageIngressPolicy.MAX_SOURCE_BYTES] are refused by their reported size, before being read.
 */
internal actual val platformComposerImageIngress: (@Composable (ComposerImageAttacher) -> Modifier)? =
    { attacher -> webImageIngress(attacher) }

@Composable
private fun webImageIngress(attacher: ComposerImageAttacher): Modifier {
    val latest = rememberUpdatedState(attacher)
    val focus = remember { ComposerFocus() }
    DisposableEffect(Unit) {
        val listeners = installImageIngress(
            acceptPaste = { focus.focused },
            onFiles = { files ->
                latest.value.attach((0 until files.length).mapNotNull { index -> files[index]?.asImageSource() })
            },
        )
        onDispose { listeners.dispose() }
    }
    return Modifier.onFocusChanged { focus.focused = it.hasFocus }
}

/** Whether the composer holds focus, read by the page's paste listener outside composition. */
private class ComposerFocus {
    var focused: Boolean = false
}

private fun BrowserImageFile.asImageSource(): ComposerImageSource = {
    if (size > ImageIngressPolicy.MAX_SOURCE_BYTES) imageTooLarge()
    arrayBuffer().await().toByteArray()
}

private fun ArrayBuffer.toByteArray(): ByteArray {
    val bytes = Int8Array(this)
    return ByteArray(bytes.length) { index -> bytes[index] }
}

/** A `File` from a paste or a drop; only images are handed over. */
private external interface BrowserImageFile : JsAny {
    val size: Double

    fun arrayBuffer(): Promise<ArrayBuffer>
}

/** The page listeners [installImageIngress] added; [dispose] removes them. */
private external interface ImageIngressListeners : JsAny {
    fun dispose()
}

@JsFun(
    """(acceptPaste, onFiles) => {
        const images = (list) => {
            const out = [];
            for (let i = 0; i < (list ? list.length : 0); i++) {
                if (list[i].type.indexOf('image/') === 0) out.push(list[i]);
            }
            return out;
        };
        const hasFiles = (transfer) => !!transfer && Array.prototype.indexOf.call(transfer.types, 'Files') >= 0;
        const paste = (event) => {
            if (!event.clipboardData || !acceptPaste()) return;
            const files = images(event.clipboardData.files);
            if (files.length === 0) return;
            event.preventDefault();
            onFiles(files);
        };
        const over = (event) => { if (hasFiles(event.dataTransfer)) event.preventDefault(); };
        const drop = (event) => {
            if (!hasFiles(event.dataTransfer)) return;
            event.preventDefault();
            const files = images(event.dataTransfer.files);
            if (files.length > 0) onFiles(files);
        };
        document.addEventListener('paste', paste);
        document.addEventListener('dragover', over);
        document.addEventListener('drop', drop);
        return {
            dispose: () => {
                document.removeEventListener('paste', paste);
                document.removeEventListener('dragover', over);
                document.removeEventListener('drop', drop);
            },
        };
    }""",
)
private external fun installImageIngress(
    acceptPaste: () -> Boolean,
    onFiles: (JsArray<BrowserImageFile>) -> Unit,
): ImageIngressListeners
