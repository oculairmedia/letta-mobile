package com.letta.mobile.ui.mascot.frame

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.RiveWriteSink
import com.letta.mobile.ui.mascot.MascotPlatformFacts
import com.letta.mobile.ui.mascot.MascotStillStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Image

/**
 * letta-mobile-hv54v.14: a live surface asks for a frame only while one is worth drawing. A scene
 * whose runtime reports it has settled draws nothing more until something wakes it, and a surface
 * out of sight draws nothing until it is back.
 */
class RiveFramePacingTest {
    /** Settles on any advance while [holdsStill]; records how far it was advanced. */
    private class SettlingScene : RiveFrameScene() {
        var holdsStill = false
        val steps = mutableListOf<Float>()

        override val inputSink = RiveWriteSink { }

        override fun advance(step: RiveFrameStep) {
            steps += step.seconds
            if (holdsStill) settle()
        }

        override fun renderImage(size: IntSize): Image = error("not drawn in this test")

        override fun pointer(event: RivePointerEvent) = Unit

        override fun close() = Unit
    }

    private class SettlingRenderer : RiveFrameRenderer {
        override val available = true
        override val assetVersion = "test"
        override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        override val stillStore: MascotStillStore? = null

        override fun createScene(): RiveFrameScene = SettlingScene()

        override fun encodePng(image: Image): ByteArray? = null
    }

    private class Loop(val scene: SettlingScene, val clock: BroadcastFrameClock) {
        val visible = mutableStateOf(true)
        var frames = 0
        private var now = 0L

        /** Offers the loop one vsync, 16 ms after the last. */
        fun TestScope.vsync() {
            now += FRAME_NANOS
            clock.sendFrame(now)
            runCurrent()
        }
    }

    private fun TestScope.startLoop(scene: SettlingScene = SettlingScene()): Loop {
        val loop = Loop(scene, BroadcastFrameClock())
        backgroundScope.launch(loop.clock) {
            scene.playFrames(loop.visible) { now ->
                scene.advanceTo(now)
                loop.frames++
            }
        }
        runCurrent()
        return loop
    }

    @Test
    fun aSettledSceneAsksForNoFrameUntilAnInputWakesIt() = runTest {
        val entry = FrameMascotEntry(SettlingRenderer(), MascotIdentity.seeded("agent-a"))
        entry.ensureLoaded()
        val loop = startLoop(assertIs<SettlingScene>(entry.scene))
        with(loop) {
            vsync()
            vsync()
            assertEquals(2, frames)

            scene.holdsStill = true
            vsync()
            assertTrue(scene.settled)
            assertEquals(3, frames)
            repeat(5) { vsync() }
            assertEquals(3, frames, "a settled scene draws nothing more")
            assertFalse(clock.hasAwaiters, "and asks the clock for nothing")

            // A write through the entry (here the reduced-motion input) wakes it.
            scene.holdsStill = false
            entry.setPlatformFacts(MascotPlatformFacts(reduceMotion = true))
            assertFalse(scene.settled)
            runCurrent()
            vsync()
            vsync()
            assertEquals(5, frames, "frames resume once the scene is woken")
            assertEquals(ONE_FRAME_SECONDS, scene.steps[scene.steps.size - 2], 1e-6f, "picking up one frame on, not the idle gap")
        }
    }

    @Test
    fun aSurfaceOutOfSightAsksForNoFrameUntilItIsBack() = runTest {
        val loop = startLoop()
        with(loop) {
            vsync()
            visible.value = false
            Snapshot.sendApplyNotifications()
            vsync()
            runCurrent()
            val hidden = frames
            repeat(5) { vsync() }
            assertEquals(hidden, frames, "nothing drawn while out of sight")

            visible.value = true
            Snapshot.sendApplyNotifications()
            runCurrent()
            vsync()
            assertEquals(hidden + 1, frames, "back in sight, it draws again")
        }
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
        const val ONE_FRAME_SECONDS = 1f / 60f
    }
}
