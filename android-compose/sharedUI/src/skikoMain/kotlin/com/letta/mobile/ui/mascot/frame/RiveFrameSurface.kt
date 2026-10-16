package com.letta.mobile.ui.mascot.frame

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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

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
private class RiveGhostRing {
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
}

/** onion.py's `ramp`: [lo] at the oldest frame, [hi] at the newest. */
private fun onionRamp(index: Int, count: Int, lo: Float, hi: Float): Float =
    if (count < 2) hi else lo + (hi - lo) * (index / (count - 1f))

/** onion.py's tint: pull the frame toward the ramp colour, strongest on the oldest frame. */
private fun onionTint(index: Int, count: Int): ColorFilter {
    val t = if (count < 2) 1f else index / (count - 1f)
    val hue = lerp(ONION_COOL, ONION_WARM, t)
    return ColorFilter.tint(hue.copy(alpha = 0.6f - 0.5f * t), BlendMode.SrcAtop)
}

/**
 * The frame on screen and the one it replaced. The images are owned here and closed by hand: the
 * replaced one is retired a frame later, once the draw that used it has finished. Leaving them to
 * Skiko's cleaner races the render thread and crashes inside skiko when the size changes quickly
 * (a resize drag allocates and frees one every frame).
 */
private class RiveFrameSlots {
    private val images = arrayOfNulls<Image>(2)
    var frame: ImageBitmap? by mutableStateOf(null)
        private set

    fun show(image: Image) {
        images[1]?.close()
        images[1] = images[0]
        images[0] = image
        frame = image.toComposeImageBitmap()
    }

    fun close() {
        images.forEach { it?.close() }
        images.fill(null)
    }
}

/**
 * One run of the frame loop. The size is pinned for the run: `size` is state and can change under
 * a frame callback before the effect restarts, and a frame of one size shown at another is wrong.
 */
private class RiveRenderTarget(
    private val scene: RiveFrameScene,
    private val dispatcher: CoroutineDispatcher,
    private val size: IntSize,
    private val slots: RiveFrameSlots,
) {
    /** One frame of the scene as it stands; whichever live surface shares it advances it. */
    suspend fun renderStill() {
        slots.show(withContext(dispatcher) { scene.renderImage(size.width, size.height) })
    }

    /**
     * Advance (once per frame even when several surfaces share the scene) and render on the
     * renderer's dispatcher, then show on this one. On desktop the GPU round trip and its blocking
     * readback never run on the UI thread; a frame that takes longer than the vsync simply delays
     * the next one.
     */
    suspend fun renderFrame(now: Long, onFrameStats: ((nativeMs: Double) -> Unit)?, ghost: Boolean): Image? {
        val (image, ghostImage) = withContext(dispatcher) {
            scene.advanceTo(now)
            val started = TimeSource.Monotonic.markNow()
            val frame = scene.renderImage(size.width, size.height)
            onFrameStats?.invoke(started.elapsedNow().inWholeNanoseconds / 1e6)
            // A ghost gets its own image - the two-slot rotation closes its images a frame later.
            frame to (if (ghost) scene.renderImage(size.width, size.height) else null)
        }
        slots.show(image)
        return ghostImage
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
        val now = withFrameNanos { it }
        val skin = onion.value
        val wantGhost = ring != null && skin != null && tick % skin.stride.coerceAtLeast(1) == 0L
        val ghost = renderFrame(now, onFrameStats, ghost = wantGhost)
        if (ghost != null && skin != null) ring?.push(ghost, skin.frames)
        tick++
    }
}

private fun rivePointerKind(type: PointerEventType): RivePointer = when (type) {
    PointerEventType.Press -> RivePointer.DOWN
    PointerEventType.Release -> RivePointer.UP
    PointerEventType.Exit -> RivePointer.EXIT
    else -> RivePointer.MOVE
}

/** Pointer events arrive in node pixels; the scene hit-tests in render pixels, so they are scaled by the cap. */
private fun Modifier.rivePointerInput(scene: RiveFrameScene, renderSize: () -> IntSize): Modifier {
    return pointerInput(scene) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val p = event.changes.firstOrNull()?.position ?: continue
                val render = renderSize()
                val sx = if (size.width > 0) render.width.toFloat() / size.width else 1f
                val sy = if (size.height > 0) render.height.toFloat() / size.height else 1f
                scene.pointer(rivePointerKind(event.type), p.x * sx, p.y * sy)
            }
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
 * Draws a [RiveFrameScene] as an ordinary Compose node: the platform renders a frame offscreen,
 * the frame is copied into a Skia image, and the image is drawn here. It sits in the layout like
 * any other composable - no heavyweight window, no DOM canvas over the page - so z-order with
 * popups and drawers, clipping and pointer routing are Compose's.
 *
 * Rendered at the node's physical pixel size, so a HiDPI display gets native resolution rather
 * than an upscaled texture.
 *
 * @param dispatcher the renderer's dispatcher, where every scene call runs.
 * @param playing false renders the scene once as it stands and never advances it here - a still.
 * @param onion non-null turns on the bench's live onion skin; null keeps the production path
 * allocation-free.
 */
@Composable
fun RiveFrameSurface(
    scene: RiveFrameScene,
    dispatcher: CoroutineDispatcher,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    onFrameStats: ((nativeMs: Double) -> Unit)? = null,
    onion: RiveOnionSkin? = null,
) {
    var nodeSize by remember { mutableStateOf(IntSize.Zero) }
    // Rendered at the node's physical size up to [MAX_RENDER_PX] on the long side: the cost of a
    // frame is its pixels (render, readback, copy, upload), and a soft-shaped character upscaled
    // from that is indistinguishable at hero sizes.
    val size = remember(nodeSize) { nodeSize.cappedTo(MAX_RENDER_PX) }
    val slots = remember { RiveFrameSlots() }
    DisposableEffect(Unit) { onDispose { slots.close() } }

    // The ring exists only while the onion skin is on, and dies with it: turning the toggle off
    // frees every ghost image immediately rather than keeping a dormant buffer on the hot path.
    val ring = remember(onion != null) { onion?.let { RiveGhostRing() } }
    DisposableEffect(ring) { onDispose { ring?.clear() } }
    // Read inside the frame loop so moving the N / stride sliders does not restart it.
    val currentOnion = rememberUpdatedState(onion)

    LaunchedEffect(scene, size, playing) {
        val pinned = size
        if (pinned.width <= 0 || pinned.height <= 0) return@LaunchedEffect
        val target = RiveRenderTarget(scene, dispatcher, pinned, slots)
        if (playing) target.play(ring, currentOnion, onFrameStats) else target.renderStill()
    }

    Canvas(modifier.onSizeChanged { nodeSize = it }.rivePointerInput(scene) { size }) {
        drawOnionGhosts(ring?.ghosts.orEmpty(), onion)
        slots.frame?.let { frame ->
            drawImage(
                image = frame,
                srcSize = IntSize(frame.width, frame.height),
                dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
            )
        }
    }
}

/** The long side of a render, in physical pixels. */
private const val MAX_RENDER_PX = 256

private fun IntSize.cappedTo(maxPx: Int): IntSize {
    val long = maxOf(width, height)
    if (long <= maxPx || long == 0) return this
    val scale = maxPx.toFloat() / long
    return IntSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
}
