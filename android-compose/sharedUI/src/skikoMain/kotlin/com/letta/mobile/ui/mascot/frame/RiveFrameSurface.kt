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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

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

/** How a ghost frame fades: onion.py's `ramp` and its tint, both strongest on the oldest frame. */
private object OnionFade {
    /** onion.py's `ramp`: [lo] at the oldest frame, [hi] at the newest. */
    fun ramp(index: Int, count: Int, lo: Float, hi: Float): Float =
        if (count < 2) hi else lo + (hi - lo) * (index / (count - 1f))

    /** onion.py's tint: pull the frame toward the ramp colour, strongest on the oldest frame. */
    fun tint(index: Int, count: Int): ColorFilter {
        val t = if (count < 2) 1f else index / (count - 1f)
        val hue = lerp(ONION_COOL, ONION_WARM, t)
        return ColorFilter.tint(hue.copy(alpha = 0.6f - 0.5f * t), BlendMode.SrcAtop)
    }
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
 *
 * A scene call that throws - the browser's Rive binding throwing out of an interop call, a frame
 * that cannot allocate its pixels, a scene the runtime already released - marks the mascot failed
 * instead of escaping the surface's effect and taking the window down with it; a cancelled loop
 * rethrows, because the surface left composition and that is not a failure.
 */
private class RiveRenderTarget(
    val scene: RiveFrameScene,
    private val dispatcher: CoroutineDispatcher,
    private val size: IntSize,
    private val slots: RiveFrameSlots,
    private val markFailed: () -> Unit,
) {
    /** One frame of the scene as it stands; whichever live surface shares it advances it. */
    suspend fun renderStill() = guardFrameWork(scene, markFailed) {
        slots.show(withContext(dispatcher) { scene.renderImage(size) })
    }

    /**
     * Advance (once per frame even when several surfaces share the scene) and render on the
     * renderer's dispatcher, then show on this one. On desktop the GPU round trip and its blocking
     * readback never run on the UI thread; a frame that takes longer than the vsync simply delays
     * the next one.
     */
    suspend fun renderFrame(now: Long, onFrameStats: ((nativeMs: Double) -> Unit)?, ghost: Boolean) = guardFrameWork(scene, markFailed) {
        val (image, ghostImage) = withContext(dispatcher) {
            scene.advanceTo(now)
            val started = TimeSource.Monotonic.markNow()
            val frame = scene.renderImage(size)
            onFrameStats?.invoke(started.elapsedNow().inWholeNanoseconds / 1e6)
            // A ghost gets its own image - the two-slot rotation closes its images a frame later.
            frame to (if (ghost) scene.renderImage(size) else null)
        }
        slots.show(image)
        ghostImage
    }
}

/**
 * The frame loop's pacing: a frame is asked for only while one is worth drawing. Nothing while the
 * surface is out of sight ([visible] false: scrolled or clipped away; a hidden browser tab or
 * minimised window stops the frame clock itself), and nothing while the scene is
 * [RiveFrameScene.settled] - its last advance left nothing to play - until an input write or a
 * pointer event [RiveFrameScene.wake]s it. The last frame stays on screen meanwhile.
 */
internal suspend fun RiveFrameScene.playFrames(visible: State<Boolean>, frame: suspend (nowNanos: Long) -> Unit) {
    resume()
    while (true) {
        if (!visible.value) {
            snapshotFlow { visible.value }.first { it }
            resume()
        }
        if (settled) awaitMotion()
        frame(withFrameNanos { it })
    }
}

/** Plays the scene until the effect is cancelled, feeding every [RiveOnionSkin.stride]th frame to [ring]. */
private suspend fun RiveRenderTarget.play(
    ring: RiveGhostRing?,
    onion: State<RiveOnionSkin?>,
    onFrameStats: ((nativeMs: Double) -> Unit)?,
    visible: State<Boolean>,
) {
    // Ghosts are sized in pixels, so a resize invalidates every one of them.
    ring?.clear()
    var tick = 0L
    scene.playFrames(visible) { now ->
        val skin = onion.value
        val wantGhost = ring != null && skin != null && tick % skin.stride.coerceAtLeast(1) == 0L
        val ghost = renderFrame(now, onFrameStats, ghost = wantGhost)
        if (ghost != null && skin != null) ring?.push(ghost, skin.frames)
        tick++
    }
}

/** The scene's pointer kind for a Compose pointer type; a hover or a move is both [RivePointer.MOVE]. */
private val POINTER_KINDS = mapOf(
    PointerEventType.Press to RivePointer.DOWN,
    PointerEventType.Release to RivePointer.UP,
    PointerEventType.Exit to RivePointer.EXIT,
)

private fun rivePointerKind(type: PointerEventType): RivePointer = POINTER_KINDS[type] ?: RivePointer.MOVE

/**
 * Runs one piece of frame work on [scene]; if it fails with anything but a cancellation the mascot
 * is failed ([onFailure] - the host stops offering the entry, so the seat draws its fallback) and
 * this throws [SceneFailure] to end the frame loop.
 *
 * The failure never reaches the composition: an exception out of a [LaunchedEffect] fails it, and
 * one dead mascot must not cost the screen.
 */
internal suspend fun <T> guardFrameWork(scene: RiveFrameScene, onFailure: () -> Unit, block: suspend () -> T): T =
    runCatchingCancellable(block).getOrElse { failure ->
        println("[mascot] Rive scene failed while drawing, drawing the fallback: $failure")
        releaseFailedScene(scene)
        onFailure()
        throw SceneFailure(failure)
    }

/**
 * Releases the scene that failed. Closing is best effort: a runtime that already trapped throws
 * again, and that must not replace the failure the caller is about to handle.
 */
private suspend fun releaseFailedScene(scene: RiveFrameScene) {
    runCatchingCancellable { scene.close() }
        .onFailure { println("[mascot] releasing the failed scene also threw: $it") }
}

/**
 * The end of a frame loop whose scene failed: the mascot is already marked failed and the scene
 * released, so this ends the loop (and any other surface drawing the same scene) without reaching
 * the composition's exception handler.
 */
internal class SceneFailure(val failure: Throwable) : CancellationException("this Rive scene cannot be drawn: $failure")

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
                scene.pointer(RivePointerEvent(rivePointerKind(event.type), Offset(p.x * sx, p.y * sy)))
                // A hover or a press can start a listener's animation in a settled scene.
                scene.wake()
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
            alpha = OnionFade.ramp(i, ghosts.size, onion.minAlpha, onion.maxAlpha),
            colorFilter = if (onion.tint) OnionFade.tint(i, ghosts.size) else null,
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
 * @param onFailure called once when a scene call throws (never for cancellation), after the scene
 * is released: the mascot cannot be drawn, so the host stops offering it and the seat falls back to
 * its stand-in. Composition and every other surface are untouched.
 */
@Composable
fun RiveFrameSurface(
    scene: RiveFrameScene,
    dispatcher: CoroutineDispatcher,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    onFrameStats: ((nativeMs: Double) -> Unit)? = null,
    onion: RiveOnionSkin? = null,
    onFailure: () -> Unit = {},
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
    // Read inside the frame loop, so scrolling out of sight pauses it rather than restarting it.
    val visible = remember { mutableStateOf(true) }
    val currentOnFailure = rememberUpdatedState(onFailure)

    LaunchedEffect(scene, size, playing) {
        val pinned = size
        if (pinned.width <= 0 || pinned.height <= 0) return@LaunchedEffect
        val target = RiveRenderTarget(scene, dispatcher, pinned, slots) { currentOnFailure.value() }
        try {
            if (playing) target.play(ring, currentOnion, onFrameStats, visible) else target.renderStill()
        } catch (_: SceneFailure) {
            // Marked failed and released already; this surface has nothing left to draw and simply
            // ends. Guarded here so nothing reaches the composition's exception handler.
        }
    }

    val node = modifier
        .onSizeChanged { nodeSize = it }
        .onGloballyPositioned { visible.value = !it.boundsInWindow().isEmpty }
        .rivePointerInput(scene) { size }
    Canvas(node) {
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
