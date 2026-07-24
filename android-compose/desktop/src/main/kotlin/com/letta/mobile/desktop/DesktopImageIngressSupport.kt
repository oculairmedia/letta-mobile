package com.letta.mobile.desktop

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

/** Callbacks + loader shared by clipboard paste and file-drop image ingress. */
internal data class DesktopImageIngressSink(
    val scope: CoroutineScope,
    val loader: DesktopImageAttachmentLoader,
    val onImage: (MessageContentPart.Image) -> Unit,
    val onError: (String) -> Unit,
) {
    fun launchLoad(block: suspend DesktopImageAttachmentLoader.() -> MessageContentPart.Image) {
        scope.launch {
            runCatching { loader.block() }
                .onSuccess(onImage)
                .onFailure { onError(it.message ?: it::class.simpleName ?: "Could not attach image") }
        }
    }
}

internal fun KeyEvent.isShortcutPaste(): Boolean = isControlDown || isMetaDown

internal fun java.awt.Image.toBufferedImage(): BufferedImage? {
    if (this is BufferedImage) return this
    val imageWidth = getWidth(null).takeIf { it > 0 } ?: return null
    val imageHeight = getHeight(null).takeIf { it > 0 } ?: return null
    return BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB).also { target ->
        target.createGraphics().use { graphics -> graphics.drawImage(this, 0, 0, null) }
    }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Transferable.allFiles(): List<File> =
    runCatching { getTransferData(DataFlavor.javaFileListFlavor) as? List<File> }
        .getOrNull()
        .orEmpty()
        .filter { it.isFile }

internal fun File.isImageFile(): Boolean = extension.lowercase() in IMAGE_EXTENSIONS

internal fun Transferable.imageFiles(): List<File> = allFiles().filter { it.isImageFile() }

internal fun handleClipboardImagePaste(
    transferable: Transferable,
    sink: DesktopImageIngressSink,
): Boolean {
    val rawImage = runCatching {
        transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
    }.getOrNull() ?: return false
    val image = rawImage.toBufferedImage() ?: return false
    sink.launchLoad { load(image) }
    return true
}

/**
 * Ingest cap per drop/paste, matching the composer's attachment grid; files
 * beyond it are ignored rather than erroring one by one.
 */
internal const val MAX_INGRESS_FILES = 4

internal fun handleClipboardImageFilePaste(
    transferable: Transferable,
    sink: DesktopImageIngressSink,
): Boolean {
    val paths = transferable.imageFiles().take(MAX_INGRESS_FILES).map { it.toPath() }
    if (paths.isEmpty()) return false
    paths.forEach { path -> sink.launchLoad { load(path) } }
    return true
}

internal fun createImageFileDropTarget(
    sink: DesktopImageIngressSink,
    onDragActive: (Boolean) -> Unit = {},
): DropTargetAdapter =
    object : DropTargetAdapter() {
        override fun dragEnter(event: java.awt.dnd.DropTargetDragEvent) {
            onDragActive(true)
        }

        override fun dragOver(event: java.awt.dnd.DropTargetDragEvent) {
            onDragActive(true)
        }

        override fun dragExit(event: java.awt.dnd.DropTargetEvent) {
            onDragActive(false)
        }

        override fun drop(event: DropTargetDropEvent) {
            onDragActive(false)
            // acceptDrop MUST precede transferable access: on Windows, reading
            // the file list from an unaccepted drop throws
            // InvalidDnDOperationException, which made drops look dead.
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = event.transferable.allFiles()
            val paths = files.filter { it.isImageFile() }.take(MAX_INGRESS_FILES).map { it.toPath() }
            when {
                paths.isNotEmpty() -> paths.forEach { path -> sink.launchLoad { load(path) } }
                // Give feedback instead of a silent dead drop.
                files.isNotEmpty() -> sink.onError(
                    "Only images can be attached for now (png, jpg, jpeg, webp, gif, bmp).",
                )
                else -> sink.onError("Nothing attachable in that drop — try image files.")
            }
            event.dropComplete(true)
        }
    }

internal fun installDropTargetOnComponentTree(
    component: Component,
    target: DropTargetAdapter,
    installed: java.util.WeakHashMap<Component, DropTarget?>,
) {
    if (!component.isDisplayable || component in installed) return
    installed[component] = component.dropTarget
    runCatching { component.dropTarget = DropTarget(component, DnDConstants.ACTION_COPY, target, true) }
    if (component is Container) {
        component.components.forEach { installDropTargetOnComponentTree(it, target, installed) }
    }
}

internal fun installDropTargetsOnWindows(
    target: DropTargetAdapter,
    installed: java.util.WeakHashMap<Component, DropTarget?>,
) {
    Window.getWindows().forEach { installDropTargetOnComponentTree(it, target, installed) }
}

internal fun restoreDropTargets(installed: java.util.WeakHashMap<Component, DropTarget?>) {
    installed.forEach { (component, previous) ->
        runCatching { component.dropTarget = previous }
    }
}

internal fun createWindowOpenedDropTargetInstaller(
    target: DropTargetAdapter,
    installed: java.util.WeakHashMap<Component, DropTarget?>,
): java.awt.event.AWTEventListener =
    java.awt.event.AWTEventListener { event ->
        if (event is WindowEvent && event.id == WindowEvent.WINDOW_OPENED) {
            (event.window as? Window)?.let { installDropTargetOnComponentTree(it, target, installed) }
        }
    }
