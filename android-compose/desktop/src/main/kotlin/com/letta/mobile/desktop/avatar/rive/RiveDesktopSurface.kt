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

    LaunchedEffect(scene, size) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                scene.advance(((now - last) / 1e9).toFloat())
                last = now
                val started = System.nanoTime()
                val pixels = scene.render(size.width, size.height)
                onFrameStats?.invoke((System.nanoTime() - started) / 1e6)
                val image = Image.makeRaster(info, pixels, size.width * 4)
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
