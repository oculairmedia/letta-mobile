package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController

/** An image made ready for the board: re-encoded, no larger than it needs to be, with its size. */
class CanvasImage(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * [bytes] (any format the platform decodes) made ready for the board, or null if they are not an
 * image. An image on the board is stored inside the drawing itself and travels with every save
 * and sync, so a phone photo of several megabytes is scaled to at most [maxEdge] pixels on its
 * longer side and re-encoded - JPEG, or PNG when it has transparency - before it is placed.
 * Photos are turned upright by their EXIF orientation where the platform reads it.
 *
 * Blocking; call it off the main thread.
 */
internal expect fun prepareCanvasImage(bytes: ByteArray, maxEdge: Int = CANVAS_IMAGE_MAX_EDGE): CanvasImage?

/** Longest side, in pixels, an image is stored at on the board. */
internal const val CANVAS_IMAGE_MAX_EDGE = 2048

/** Putting images on the board, however they arrived: picked, pasted or dropped. */
internal object CanvasImages {
    /** How far apart, in board units, images placed together are fanned out. */
    const val STAGGER = 48f

    /** Most images one pick can add. */
    const val MAX_PICK = 20

    /** Places [images] centred on [at] in board coordinates, fanned out so none hides another. */
    fun insert(controller: DrawBoxController, images: List<CanvasImage>, at: Offset) {
        images.forEachIndexed { index, image ->
            controller.insertImage(
                bytes = image.bytes,
                intrinsicSize = Size(image.width.toFloat(), image.height.toFloat()),
                position = at + Offset(STAGGER * index, STAGGER * index),
            )
        }
    }
}
