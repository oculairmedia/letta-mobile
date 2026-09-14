package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * A live onion skin over the surface: the last [frames] rendered frames, sampled every [stride]th
 * frame, drawn under the live one. The ramp is the one `rive/mascot/onion.py` uses so the bench and
 * the CLI tool read the same - alpha [minAlpha]..[maxAlpha] oldest to newest, and with [tint] a
 * cool-to-warm paper tint that fades out as the frames approach now.
 *
 * Passing null (the default, and what every production call site does) allocates no ring at all.
 */
data class RiveOnionSkin(
    val frames: Int = 8,
    val stride: Int = 3,
    val tint: Boolean = true,
    val minAlpha: Float = 0.15f,
    val maxAlpha: Float = 0.6f,
)

/** onion.py's `COOL` and `WARM`: the oldest frame's paper is blue, the newest is nearly untinted. */
private val ONION_COOL = Color(0.353f, 0.588f, 1f)
private val ONION_WARM = Color(1f, 0.588f, 0.235f)

/**
 * Holds the ghost frames. The Skia images are owned here and closed on eviction: a ghost outlives
 * the two-slot rotation the live frame uses, so it cannot borrow one of those images.
 */
private class RiveGhostRing : AutoCloseable {
    private val owned = ArrayDeque<Image>()
    var ghosts: List<ImageBitmap> by mutableStateOf(emptyList())
        private set

    fun push(image: Image, capacity: Int) {
        owned.addLast(image)
        while (owned.size > capacity.coerceAtLeast(1)) owned.removeFirst().close()
        ghosts = owned.map { it.toComposeImageBitmap() }
    }

    fun clear() {
        while (owned.isNotEmpty()) owned.removeFirst().close()
        ghosts = emptyList()
    }

    override fun close() = clear()
}

/** onion.py's `ramp`: [lo] at the oldest frame, [hi] at the newest. */
private fun onionRamp(index: Int, count: Int, lo: Float, hi: Float): Float =
    if (count < 2) hi else lo + (hi - lo) * (index / (count - 1f))

/** onion.py's tint: pull the frame toward the ramp colour, strongest on the oldest frame. */
private fun onionTint(index: Int, count: Int): ColorFilter {
    val t = if (count < 2) 1f else index / (count - 1f)
    val hue = androidx.compose.ui.graphics.lerp(ONION_COOL, ONION_WARM, t)
    return ColorFilter.tint(hue.copy(alpha = 0.6f - 0.5f * t), BlendMode.SrcAtop)
}

/**
 * The frame on screen and the one it replaced. The images are owned here and closed by hand: the
 * replaced one is retired a frame later, once the draw that used it has finished. Leaving them to
 * Skiko's cleaner races the render thread and crashes inside skiko when the size changes quickly
 * (a resize drag allocates and frees one every frame).
 */
private class RiveFrameSlots : AutoCloseable {
    private val images = arrayOfNulls<Image>(2)
    var frame: ImageBitmap? by mutableStateOf(null)
        private set

    fun show(image: Image) {
        images[1]?.close()
        images[1] = images[0]
        images[0] = image
        frame = image.toComposeImageBitmap()
    }

    override fun close() {
        images.forEach { it?.close() }
        images.fill(null)
    }
}

/**
 * One run of the frame loop. The size is pinned for the run: `size` is state and can change under
 * a frame callback before the effect restarts, and a buffer of one size in an ImageInfo of another
 * is a crash.
 */
private class RiveRenderTarget(
    private val scene: RiveDesktopScene,
    private val size: IntSize,
    private val slots: RiveFrameSlots,
) {
    private val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)

    fun raster(pixels: ByteArray): Image = Image.makeRaster(info, pixels, size.width * 4)

    /** One frame of the scene as it stands; whichever live surface shares it advances it. */
    fun renderStill() = slots.show(raster(scene.render(size.width, size.height)))

    /** Advance (once per frame even when several surfaces share the scene), render and show; returns the pixels. */
    fun renderFrame(now: Long, onFrameStats: ((nativeMs: Double) -> Unit)?): ByteArray {
        scene.advanceTo(now)
        val started = System.nanoTime()
        val pixels = scene.render(size.width, size.height)
        onFrameStats?.invoke((System.nanoTime() - started) / 1e6)
        slots.show(raster(pixels))
        return pixels
    }
}

/** Plays the scene until the effect is cancelled, feeding every [RiveOnionSkin.stride]th frame to [ring]. */
private suspend fun RiveRenderTarget.play(
    ring: RiveGhostRing?,
    onion: State<RiveOnionSkin?>,
    onFrameStats: ((nativeMs: Double) -> Unit)?,
) {
    // Ghosts are sized in pixels, so a resize invalidates every one of them.
    ring?.clear()
    var tick = 0L
    while (true) {
        withFrameNanos { now ->
            val pixels = renderFrame(now, onFrameStats)
            // A ghost gets its own image: the two-slot rotation closes its images a frame later,
            // which would pull the pixels out from under the ring.
            ring?.offer(tick, onion.value) { raster(pixels) }
            tick++
        }
    }
}

/** Push a ghost when the onion skin is on and [tick] lands on its stride. */
private fun RiveGhostRing.offer(tick: Long, skin: RiveOnionSkin?, image: () -> Image) {
    if (skin == null) return
    if (tick % skin.stride.coerceAtLeast(1) == 0L) push(image(), skin.frames)
}

private fun rivePointerKind(type: PointerEventType): RivePointer = when (type) {
    PointerEventType.Press -> RivePointer.DOWN
    PointerEventType.Release -> RivePointer.UP
    PointerEventType.Exit -> RivePointer.EXIT
    else -> RivePointer.MOVE
}

private fun Modifier.rivePointerInput(scene: RiveDesktopScene): Modifier = pointerInput(scene) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val p = event.changes.firstOrNull()?.position ?: continue
            scene.pointer(rivePointerKind(event.type), p.x, p.y)
        }
    }
}

/** Oldest first, under the live frame: the classic stack, faintest at the back. */
private fun DrawScope.drawOnionGhosts(ghosts: List<ImageBitmap>, onion: RiveOnionSkin?) {
    if (onion == null) return
    ghosts.forEachIndexed { i, ghost ->
        drawImage(
            image = ghost,
            topLeft = Offset.Zero,
            alpha = onionRamp(i, ghosts.size, onion.minAlpha, onion.maxAlpha),
            colorFilter = if (onion.tint) onionTint(i, ghosts.size) else null,
        )
    }
}

/**
 * Draws a [RiveDesktopScene] as an ordinary Compose node: native GPU render, pixel readback, Skia
 * bitmap. It sits in the layout like any other composable - no heavyweight window, no browser.
 *
 * Rendered at the node's physical pixel size, so a HiDPI display gets native resolution rather
 * than an upscaled texture.
 *
 * @param playing false renders the scene once as it stands and never advances it here - a still.
 * @param onion non-null turns on the bench's live onion skin; null keeps the production path
 * allocation-free.
 */
@Composable
fun RiveDesktopSurface(
    scene: RiveDesktopScene,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    onFrameStats: ((nativeMs: Double) -> Unit)? = null,
    onion: RiveOnionSkin? = null,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val slots = remember { RiveFrameSlots() }
    DisposableEffect(Unit) { onDispose { slots.close() } }

    // The ring exists only while the onion skin is on, and dies with it: turning the toggle off
    // frees every ghost image immediately rather than keeping a dormant buffer on the hot path.
    val ring = remember(onion != null) { onion?.let { RiveGhostRing() } }
    DisposableEffect(ring) { onDispose { ring?.close() } }
    // Read inside the frame loop so moving the N / stride sliders does not restart it.
    val currentOnion = rememberUpdatedState(onion)

    LaunchedEffect(scene, size, playing) {
        val pinned = size
        if (pinned.width <= 0 || pinned.height <= 0) return@LaunchedEffect
        val target = RiveRenderTarget(scene, pinned, slots)
        if (playing) target.play(ring, currentOnion, onFrameStats) else target.renderStill()
    }

    Canvas(modifier.onSizeChanged { size = it }.rivePointerInput(scene)) {
        drawOnionGhosts(ring?.ghosts.orEmpty(), onion)
        slots.frame?.let { drawImage(it) }
    }
}
