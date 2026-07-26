package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class DesktopImageIngressConfig(
    val enabled: Boolean,
    val scope: CoroutineScope,
    val loader: DesktopImageAttachmentLoader,
    val onImage: (MessageContentPart.Image) -> Unit,
    val onError: (String) -> Unit,
)

/**
 * Installs clipboard-paste and file-drop image ingress. Returns whether an OS
 * drag is currently hovering the window, for the drop-hint overlay.
 */
@Composable
internal fun DesktopImageIngressEffect(config: DesktopImageIngressConfig): State<Boolean> {
    DesktopClipboardImagePasteEffect(config)
    return DesktopImageFileDropEffect(config)
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
private fun DesktopImageFileDropEffect(config: DesktopImageIngressConfig): State<Boolean> {
    val currentOnImage by rememberUpdatedState(config.onImage)
    val currentOnError by rememberUpdatedState(config.onError)
    val currentScope by rememberUpdatedState(config.scope)
    val dragActive = remember { mutableStateOf(false) }
    DisposableEffect(config.enabled, config.loader) {
        if (!config.enabled) return@DisposableEffect onDispose { }
        val installed = java.util.WeakHashMap<Component, java.awt.dnd.DropTarget?>()
        val sink = DesktopImageIngressSink(currentScope, config.loader, currentOnImage, currentOnError)
        // Crossing between child components fires rapid exit/enter pairs, so
        // clears are debounced to keep the overlay from flickering.
        var clearJob: Job? = null
        val onDragActive: (Boolean) -> Unit = { active ->
            clearJob?.cancel()
            if (active) {
                if (!dragActive.value) dragActive.value = true
            } else {
                clearJob = currentScope.launch {
                    delay(DRAG_EXIT_CLEAR_DELAY_MS)
                    dragActive.value = false
                }
            }
        }
        val target = createImageFileDropTarget(sink, onDragActive)
        installDropTargetsOnWindows(target, installed)
        val listener = createWindowOpenedDropTargetInstaller(target, installed)
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, java.awt.AWTEvent.WINDOW_EVENT_MASK)
        onDispose {
            clearJob?.cancel()
            dragActive.value = false
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            restoreDropTargets(installed)
        }
    }
    return dragActive
}

private const val DRAG_EXIT_CLEAR_DELAY_MS = 120L
