package com.letta.mobile.avatar.rive

import android.graphics.Bitmap
import app.rive.Fit
import app.rive.RenderBuffer
import app.rive.RiveFile
import app.rive.core.RiveWorker
import com.letta.mobile.avatar.core.MascotIdentity
import kotlin.time.Duration.Companion.seconds

/**
 * Renders mascot stills offscreen, one identity at a time, in a single scene kept for the
 * renderer's life: a view-model instance, an artboard and state machine bound to it, and a
 * [RenderBuffer] of [sizePx]. Setting all that up per still costs far more than the still itself
 * (on desktop, ~450 ms against a few ms warm), so it is set up once.
 *
 * A capture writes the identity, advances the state machine [settleFrames] simulated frames of
 * [frameSeconds] so the identity's bindings take effect (a paused first frame shows the file's
 * default look), and reads one frame back. Nothing is on screen.
 *
 * Use from the thread that drives [worker] (the main thread for a Compose-owned worker).
 */
class MascotStillRenderer(
    private val file: RiveFile,
    private val worker: RiveWorker,
    private val sizePx: Int,
    private val settleFrames: Int,
    private val frameSeconds: Float,
) {
    private var scene: AndroidMascotScene? = null
    private var bound: AndroidMascotSurfaceScene? = null
    private var buffer: RenderBuffer? = null

    /** [identity] as a [sizePx]-square bitmap, or null when the file or worker cannot produce it. */
    fun render(identity: MascotIdentity): Bitmap? = runCatching {
        val (scene, bound, buffer) = prepared(identity) ?: return null
        RiveAvatarContract.applyIdentity(scene.sink, identity)
        repeat(settleFrames) { bound.stateMachine.advance(frameSeconds.toDouble().seconds) }
        buffer.snapshot(bound.artboard, bound.stateMachine, Fit.Contain(), 0).toBitmap()
    }.onFailure { close() }.getOrNull()

    private fun prepared(identity: MascotIdentity): Triple<AndroidMascotScene, AndroidMascotSurfaceScene, RenderBuffer>? {
        val s = scene ?: AndroidMascotScene.create(file, identity)?.also { scene = it } ?: return null
        val b = bound ?: AndroidMascotSurfaceScene.bind(s)?.also {
            worker.bindViewModelInstance(it.stateMachine.stateMachineHandle, s.viewModelInstance.instanceHandle)
            bound = it
        } ?: return null
        val r = buffer ?: RenderBuffer(sizePx, sizePx, worker).also { buffer = it }
        return Triple(s, b, r)
    }

    /** Releases the scene; the next [render] sets it up again. */
    fun close() {
        runCatching { buffer?.close() }
        bound?.close()
        scene?.close()
        buffer = null
        bound = null
        scene = null
    }
}
