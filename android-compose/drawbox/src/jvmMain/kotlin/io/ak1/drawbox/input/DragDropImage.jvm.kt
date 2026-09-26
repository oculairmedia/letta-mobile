package io.ak1.drawbox.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDropEvent
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.imageDragAndDropTarget(
    onImagesDropped: (drops: List<DroppedImage>) -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    // Where this target sits in the window, in pixels: AWT reports the drop in the window's
    // logical (unscaled) coordinates, and the host expects pixels local to the canvas.
    val originInRoot = remember { OriginHolder() }
    // Cache the target across recompositions so AWT doesn't re-register a
    // new DropTarget for every recomp (each registration walks the parent
    // window's hierarchy on Linux + Windows — non-trivial cost).
    val target = remember(onImagesDropped, density) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transferable = event.awtTransferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    return false
                }
                @Suppress("UNCHECKED_CAST")
                val files = (transferable.getTransferData(DataFlavor.javaFileListFlavor)
                    as? List<File>)?.take(MAX_DROPPED_FILES) ?: return false

                val location = (event.nativeEvent as? DropTargetDropEvent)?.location ?: return false
                val dropPos = Offset(location.x * density, location.y * density) - originInRoot.value

                // Reading and sizing files is disk work: off the AWT event thread, then back.
                scope.launch {
                    val drops = withContext(Dispatchers.IO) {
                        files.mapNotNull { file -> file.toDroppedImage(dropPos) }
                    }
                    if (drops.isNotEmpty()) onImagesDropped(drops)
                }
                return true
            }
        }
    }
    return this
        .onGloballyPositioned { originInRoot.value = it.positionInRoot() }
        .then(
            Modifier.dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                },
                target = target,
            ),
        )
}

private class OriginHolder(var value: Offset = Offset.Zero)

/**
 * Read the file's bytes and its intrinsic dimensions (from the image header only; the host
 * decodes the pixels once, when it prepares the image). Returns null for a file that is too large
 * or isn't an image ImageIO can read — the user dragging a PDF or a folder onto the canvas
 * shouldn't crash, just skip.
 */
private fun File.toDroppedImage(dropPos: Offset): DroppedImage? {
    if (!isFile || length() > MAX_DROPPED_FILE_BYTES) return null
    val bytes = try {
        readBytes()
    } catch (e: Exception) {
        return null
    }
    val size = imageSize(bytes) ?: return null
    return DroppedImage(bytes = bytes, intrinsicSize = size, dropPositionScreen = dropPos)
}

private fun imageSize(bytes: ByteArray): Size? = try {
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
        try {
            reader.input = stream
            Size(reader.getWidth(0).toFloat(), reader.getHeight(0).toFloat())
        } finally {
            reader.dispose()
        }
    }
} catch (e: Exception) {
    null
}

private const val MAX_DROPPED_FILES = 20
private const val MAX_DROPPED_FILE_BYTES = 50L * 1024 * 1024
