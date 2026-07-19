package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowListener
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
                transferable.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
                    val image = transferable.getTransferData(DataFlavor.imageFlavor) as? BufferedImage
                        ?: return@KeyEventDispatcher false
                    scope.launchLoadImage(loader, onImage, onError) { load(image) }
                    true
                }
                transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                    val path = transferable.imageFiles().firstOrNull()?.toPath() ?: return@KeyEventDispatcher false
                    scope.launchLoadImage(loader, onImage, onError) { load(path) }
                    true
                }
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
    DisposableEffect(enabled, loader) {
        if (!enabled) return@DisposableEffect onDispose { }
        val installed = mutableMapOf<Component, DropTarget?>()
        val target = object : DropTargetAdapter() {
            override fun drop(event: DropTargetDropEvent) {
                val transferable = event.transferable
                val path = transferable.imageFiles().firstOrNull()?.toPath()
                if (path == null) {
                    event.rejectDrop()
                    return
                }
                event.acceptDrop(DnDConstants.ACTION_COPY)
                scope.launchLoadImage(loader, onImage, onError) { load(path) }
                event.dropComplete(true)
            }
        }
        fun install(component: Component) {
            if (!component.isDisplayable || component in installed) return
            installed[component] = component.dropTarget
            runCatching { component.dropTarget = DropTarget(component, DnDConstants.ACTION_COPY, target, true) }
            if (component is Container) component.components.forEach(::install)
        }
        Window.getWindows().forEach(::install)
        val listener = object : java.awt.event.AWTEventListener {
            override fun eventDispatched(event: java.awt.AWTEvent) {
                if (event is WindowEvent && event.id == WindowEvent.WINDOW_OPENED) {
                    (event.window as? Window)?.let(::install)
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, java.awt.AWTEvent.WINDOW_EVENT_MASK)
        onDispose {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            installed.forEach { (component, previous) ->
                runCatching { component.dropTarget = previous }
            }
        }
    }
}

private fun CoroutineScope.launchLoadImage(
    loader: DesktopImageAttachmentLoader,
    onImage: (MessageContentPart.Image) -> Unit,
    onError: (String) -> Unit,
    block: suspend DesktopImageAttachmentLoader.() -> MessageContentPart.Image,
) {
    launch {
        runCatching { loader.block() }
            .onSuccess(onImage)
            .onFailure { onError(it.message ?: it::class.simpleName ?: "Could not attach image") }
    }
}

private fun KeyEvent.isShortcutPaste(): Boolean = isControlDown || isMetaDown

@Suppress("UNCHECKED_CAST")
private fun java.awt.datatransfer.Transferable.imageFiles(): List<File> =
    runCatching { getTransferData(DataFlavor.javaFileListFlavor) as? List<File> }
        .getOrNull()
        .orEmpty()
        .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
