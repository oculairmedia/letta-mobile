package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import kotlinx.coroutines.CoroutineScope

internal data class DesktopImageIngressConfig(
    val enabled: Boolean,
    val scope: CoroutineScope,
    val loader: DesktopImageAttachmentLoader,
    val onImage: (MessageContentPart.Image) -> Unit,
    val onError: (String) -> Unit,
)

@Composable
internal fun DesktopImageIngressEffect(config: DesktopImageIngressConfig) {
    DesktopClipboardImagePasteEffect(config)
    DesktopImageFileDropEffect(config)
}

@Composable
private fun DesktopClipboardImagePasteEffect(config: DesktopImageIngressConfig) {
    val currentOnImage by rememberUpdatedState(config.onImage)
    val currentOnError by rememberUpdatedState(config.onError)
    val currentScope by rememberUpdatedState(config.scope)
    DisposableEffect(config.enabled, config.loader) {
        if (!config.enabled) return@DisposableEffect onDispose { }
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = clipboardImagePasteDispatcher(
            sinkProvider = {
                DesktopImageIngressSink(currentScope, config.loader, currentOnImage, currentOnError)
            },
        )
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }
}

private fun clipboardImagePasteDispatcher(
    sinkProvider: () -> DesktopImageIngressSink,
): java.awt.KeyEventDispatcher =
    java.awt.KeyEventDispatcher { event ->
        if (!isClipboardPasteShortcut(event)) return@KeyEventDispatcher false
        val transferable = clipboardTransferable() ?: return@KeyEventDispatcher false
        dispatchClipboardImagePaste(transferable, sinkProvider())
    }

private fun isClipboardPasteShortcut(event: KeyEvent): Boolean =
    event.id == KeyEvent.KEY_PRESSED &&
        event.keyCode == KeyEvent.VK_V &&
        event.isShortcutPaste()

private fun clipboardTransferable(): Transferable? =
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()

private fun dispatchClipboardImagePaste(
    transferable: Transferable,
    sink: DesktopImageIngressSink,
): Boolean = when {
    transferable.isDataFlavorSupported(DataFlavor.imageFlavor) ->
        handleClipboardImagePaste(transferable, sink)
    transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
        handleClipboardImageFilePaste(transferable, sink)
    else -> false
}

@Composable
private fun DesktopImageFileDropEffect(config: DesktopImageIngressConfig) {
    val currentOnImage by rememberUpdatedState(config.onImage)
    val currentOnError by rememberUpdatedState(config.onError)
    val currentScope by rememberUpdatedState(config.scope)
    DisposableEffect(config.enabled, config.loader) {
        if (!config.enabled) return@DisposableEffect onDispose { }
        val installed = java.util.WeakHashMap<Component, java.awt.dnd.DropTarget?>()
        val sink = DesktopImageIngressSink(currentScope, config.loader, currentOnImage, currentOnError)
        val target = createImageFileDropTarget(sink)
        installDropTargetsOnWindows(target, installed)
        val listener = createWindowOpenedDropTargetInstaller(target, installed)
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, java.awt.AWTEvent.WINDOW_EVENT_MASK)
        onDispose {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            restoreDropTargets(installed)
        }
    }
}
