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
import java.awt.event.KeyEvent
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun DesktopImageIngressEffect(
    enabled: Boolean,
    scope: CoroutineScope,
    loader: DesktopImageAttachmentLoader,
    onImage: (MessageContentPart.Image) -> Unit,
    onError: (String) -> Unit,
) {
    DesktopClipboardImagePasteEffect(enabled, scope, loader, onImage, onError)
    DesktopImageFileDropEffect(enabled, scope, loader, onImage, onError)
}

@Composable
private fun DesktopClipboardImagePasteEffect(
    enabled: Boolean,
    scope: CoroutineScope,
    loader: DesktopImageAttachmentLoader,
    onImage: (MessageContentPart.Image) -> Unit,
    onError: (String) -> Unit,
) {
    val currentOnImage by rememberUpdatedState(onImage)
    val currentOnError by rememberUpdatedState(onError)
    val currentScope by rememberUpdatedState(scope)
    DisposableEffect(enabled, loader) {
        if (!enabled) return@DisposableEffect onDispose { }
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = java.awt.KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED || event.keyCode != KeyEvent.VK_V || !event.isShortcutPaste()) {
                return@KeyEventDispatcher false
            }
            val transferable = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()
                ?: return@KeyEventDispatcher false
            when {
                transferable.isDataFlavorSupported(DataFlavor.imageFlavor) ->
                    handleClipboardImagePaste(transferable, currentScope, loader, currentOnImage, currentOnError)
                transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
                    handleClipboardImageFilePaste(transferable, currentScope, loader, currentOnImage, currentOnError)
                else -> false
            }
        }
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }
}

@Composable
private fun DesktopImageFileDropEffect(
    enabled: Boolean,
    scope: CoroutineScope,
    loader: DesktopImageAttachmentLoader,
    onImage: (MessageContentPart.Image) -> Unit,
    onError: (String) -> Unit,
) {
    val currentOnImage by rememberUpdatedState(onImage)
    val currentOnError by rememberUpdatedState(onError)
    val currentScope by rememberUpdatedState(scope)
    DisposableEffect(enabled, loader) {
        if (!enabled) return@DisposableEffect onDispose { }
        val installed = java.util.WeakHashMap<Component, java.awt.dnd.DropTarget?>()
        val target = createImageFileDropTarget(currentScope, loader, currentOnImage, currentOnError)
        installDropTargetsOnWindows(target, installed)
        val listener = createWindowOpenedDropTargetInstaller(target, installed)
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, java.awt.AWTEvent.WINDOW_EVENT_MASK)
        onDispose {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            restoreDropTargets(installed)
        }
    }
}
