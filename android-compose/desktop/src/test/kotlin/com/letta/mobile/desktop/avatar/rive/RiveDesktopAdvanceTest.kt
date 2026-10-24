package com.letta.mobile.desktop.avatar.rive

import androidx.compose.ui.unit.IntSize
import com.letta.mobile.ui.mascot.frame.RiveFrameStep
import com.sun.jna.Pointer
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiveDesktopAdvanceTest {
    private class NativeFixture {
        var activityResult = 1
        var beforeActivityResult: () -> Unit = {}
        val activityCalls = AtomicInteger()
        val legacyCalls = AtomicInteger()
        val renderCalls = AtomicInteger()
        val destroyCalls = AtomicInteger()
        val bridge = Proxy.newProxyInstance(
            RiveBridgeNative::class.java.classLoader,
            arrayOf(RiveBridgeNative::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "riveBridgeAdvanceIsActive" -> {
                    activityCalls.incrementAndGet()
                    beforeActivityResult()
                    activityResult
                }
                "rive_bridge_advance" -> { legacyCalls.incrementAndGet(); null }
                "rive_bridge_render" -> { renderCalls.incrementAndGet(); 0 }
                "rive_bridge_destroy" -> { destroyCalls.incrementAndGet(); null }
                else -> error("Unexpected native call ${method.name}")
            }
        } as RiveBridgeNative
    }

    @Test
    fun activityExportSettlesAfterPaintingAndCannotOverwriteANewerWake() {
        val native = NativeFixture()
        lateinit var scene: RiveDesktopScene
        scene = RiveDesktopScene(native.bridge, Pointer(1), "test", advanceActivity = true)
        try {
            scene.advance(RiveFrameStep(1f / 60f))
            RiveThread.call { Unit }
            assertFalse(scene.settled, "active Rive state keeps requesting frames")

            native.activityResult = 0
            scene.advance(RiveFrameStep(1f / 60f))
            RiveThread.call { Unit }
            assertTrue(scene.settled, "a false activity result settles the scene")

            scene.renderImage(IntSize(1, 1)).close()
            assertEquals(1, native.renderCalls.get(), "the settled result is followed by a final render")

            native.beforeActivityResult = { scene.wake() }
            scene.advance(RiveFrameStep(1f / 60f))
            RiveThread.call { Unit }
            assertFalse(scene.settled, "a wake during native advance fences its stale false result")

            native.beforeActivityResult = {}
            scene.advance(RiveFrameStep(1f / 60f))
            RiveThread.call { Unit }
            assertTrue(scene.settled)
        } finally {
            scene.close()
            RiveThread.call { Unit }
        }
    }

    @Test
    fun oldDllFallbackAdvancesWithoutClaimingThatTheSceneSettled() {
        val native = NativeFixture().apply { activityResult = 0 }
        val scene = RiveDesktopScene(native.bridge, Pointer(2), "old-dll", advanceActivity = false)
        try {
            scene.advance(RiveFrameStep(1f / 60f))
            RiveThread.call { Unit }

            assertEquals(1, native.legacyCalls.get())
            assertEquals(0, native.activityCalls.get())
            assertFalse(scene.settled, "the legacy void export cannot prove settlement")
        } finally {
            scene.close()
            RiveThread.call { Unit }
        }
    }

    @Test
    fun queuedAdvanceAfterDisposalDoesNotTouchTheDestroyedHandle() {
        val native = NativeFixture()
        val scene = RiveDesktopScene(native.bridge, Pointer(3), "disposed", advanceActivity = true)
        scene.close()
        scene.advance(RiveFrameStep(1f / 60f))
        RiveThread.call { Unit }

        assertEquals(1, native.destroyCalls.get())
        assertEquals(0, native.activityCalls.get())
        assertEquals(0, native.legacyCalls.get())
    }
}
