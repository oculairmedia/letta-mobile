package com.letta.mobile.ui.mascot.frame

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.avatar.rive.RiveInputSink
import kotlin.jvm.JvmInline
import com.letta.mobile.ui.mascot.MascotStillStore
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Image

/**
 * A platform's Rive renderer for the frame pipeline: it brings up scenes of the shipped mascot and
 * renders them offscreen; [RiveFrameSurface] copies each frame into the Compose layout. Desktop
 * implements it over the native bridge DLL, the web over `@rive-app/canvas-advanced` in an
 * OffscreenCanvas. Presence, time, gaze, sizing and stills are shared ([FrameMascotHost]).
 */
interface RiveFrameRenderer {
    /**
     * Whether scenes can be created. Cheap and read in composition; a renderer that loads
     * asynchronously backs it with Compose state so every mascot recomposes when it turns true.
     */
    val available: Boolean

    /** A short fingerprint of the mascot asset: a new file means new stills. Read once [available]. */
    val assetVersion: String

    /**
     * Where every scene call runs: desktop's one Rive thread (a D3D11 immediate context is
     * single-threaded), the browser's only thread on the web.
     */
    val dispatcher: CoroutineDispatcher

    /** Where captured stills are kept between launches; null keeps them in memory only. */
    val stillStore: MascotStillStore?

    /**
     * Brings the renderer up when that is asynchronous (the browser fetches its runtime and the
     * mascot); [available] turns true once it has. A renderer that cannot come up stays
     * unavailable and every mascot draws its fallback. Called once by [ProvideFrameMascots].
     */
    suspend fun prepare() = Unit

    /**
     * A new scene of the shipped mascot: the file loaded, its state machine and view model bound.
     * Called on [dispatcher]; throws when the renderer cannot create one.
     */
    fun createScene(): RiveFrameScene

    /** PNG bytes of a rendered still (premultiplied or not, as the image says). Off the render thread. */
    fun encodePng(image: Image): ByteArray?
}

/** Pointer events a scene hit-tests, in render pixels. */
enum class RivePointer { MOVE, DOWN, UP, EXIT }

/** One pointer event for a scene: what happened, and where in render pixels. */
data class RivePointerEvent(val kind: RivePointer, val position: Offset)

/** How far one advance moves a scene's state machine, in seconds of animation time. */
@JvmInline
value class RiveFrameStep(val seconds: Float)

/**
 * One offscreen Rive scene: a loaded file, its state machine and its view model. Every call but
 * [pointer] and the [inputSink] writes runs on the renderer's dispatcher; those two may be called
 * from the UI thread and the platform orders them behind the render work.
 */
abstract class RiveFrameScene {
    /** The view-model path [com.letta.mobile.avatar.rive.RiveAvatarRuntime] writes through. */
    abstract val inputSink: RiveInputSink

    /** Advances the state machine by [step] and applies it to the artboard. */
    abstract fun advance(step: RiveFrameStep)

    /** Renders the scene as it stands at [size] pixels: a new image the caller owns and closes. */
    abstract fun renderImage(size: IntSize): Image

    /** A pointer event, in render pixels. */
    abstract fun pointer(event: RivePointerEvent)

    /** Releases the scene; nothing touches it afterwards. */
    abstract fun close()

    private var lastFrameNanos = 0L

    /**
     * Advances to the frame clock's [nowNanos] once per frame: a scene shared by several surfaces
     * (the sidebar and the hero draw the same agent) would otherwise be advanced by each of them
     * and run at a multiple of real time. On the renderer's dispatcher.
     */
    fun advanceTo(nowNanos: Long) {
        if (nowNanos == lastFrameNanos) return
        val dt = if (lastFrameNanos == 0L) 0f else ((nowNanos - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_STEP_SECONDS)
        lastFrameNanos = nowNanos
        if (dt > 0f) advance(RiveFrameStep(dt))
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9f

        /** A tab or window that stopped drawing for a while comes back with one step of motion. */
        const val MAX_STEP_SECONDS = 0.1f
    }
}
