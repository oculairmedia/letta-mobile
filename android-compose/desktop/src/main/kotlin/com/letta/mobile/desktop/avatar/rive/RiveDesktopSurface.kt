package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Draws a [RiveDesktopScene] as an ordinary Compose node: native GPU render, pixel readback, Skia
 * bitmap. It sits in the layout like any other composable - no heavyweight window, no browser.
 *
 * Rendered at the node's physical pixel size, so a HiDPI display gets native resolution rather
 * than an upscaled texture.
 */
@Composable
fun RiveDesktopSurface(
    scene: RiveDesktopScene,
    modifier: Modifier = Modifier,
    /** False renders the scene once as it stands and never advances it here - a still. */
    playing: Boolean = true,
    onFrameStats: ((nativeMs: Double) -> Unit)? = null,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    // The frame images are owned here and closed by hand: the one on screen, and the one it
    // replaced, which is retired a frame later once the draw that used it has finished. Leaving
    // them to Skiko's cleaner races the render thread and crashes inside skiko when the size
    // changes quickly (a resize drag allocates and frees one every frame).
    val images = remember { arrayOfNulls<Image>(2) }
    DisposableEffect(Unit) { onDispose { images.forEach { it?.close() }; images.fill(null) } }

    LaunchedEffect(scene, size, playing) {
        // Pin the size for this loop: `size` is state and can change under a frame callback before
        // the effect restarts, and a buffer of one size in an ImageInfo of another is a crash.
        val (w, h) = size
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        if (!playing) {
            // One frame of the scene as it stands; whichever live surface shares it advances it.
            val image = Image.makeRaster(info, scene.render(w, h), w * 4)
            images[1]?.close()
            images[1] = images[0]
            images[0] = image
            frame = image.toComposeImageBitmap()
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { now ->
                // Once per frame even when several surfaces share this scene.
                scene.advanceTo(now)
                val started = System.nanoTime()
                val pixels = scene.render(w, h)
                onFrameStats?.invoke((System.nanoTime() - started) / 1e6)
                val image = Image.makeRaster(info, pixels, w * 4)
                images[1]?.close()
                images[1] = images[0]
                images[0] = image
                frame = image.toComposeImageBitmap()
            }
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(scene) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull()?.position ?: continue
                        val kind = when (event.type) {
                            PointerEventType.Press -> RivePointer.DOWN
                            PointerEventType.Release -> RivePointer.UP
                            PointerEventType.Exit -> RivePointer.EXIT
                            else -> RivePointer.MOVE
                        }
                        scene.pointer(kind, p.x, p.y)
                    }
                }
            },
    ) {
        frame?.let { drawImage(it) }
    }
}
