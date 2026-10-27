package com.letta.mobile.desktop.avatar.rive

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.ui.mascot.frame.RiveFrameStep
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.letta.mobile.ui.mascot.frame.RivePointer
import com.letta.mobile.ui.mascot.frame.RivePointerEvent
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
        val pointerCalls = AtomicInteger()
        var lastBuffer: Memory? = null
        val threads = mutableListOf<String>()
        val bridge = Proxy.newProxyInstance(
            RiveBridgeNative::class.java.classLoader,
            arrayOf(RiveBridgeNative::class.java),
        ) { _, method, args ->
            threads.add(Thread.currentThread().name)
            when (method.name) {
                "riveBridgeAdvanceIsActive" -> {
                    activityCalls.incrementAndGet()
                    beforeActivityResult()
                    activityResult
                }
                "rive_bridge_advance" -> { legacyCalls.incrementAndGet(); null }
                "rive_bridge_render" -> {
                    lastBuffer = args[4] as Memory
                    renderCalls.incrementAndGet()
                    0
                }
                "rive_bridge_pointer" -> { pointerCalls.incrementAndGet(); null }
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
    fun resizeAndCloseReleaseNativeBuffersBeforeReturning() {
        val native = NativeFixture()
        val scene = RiveDesktopScene(native.bridge, Pointer(4), "linux-lifetime", advanceActivity = true)
        try {
            scene.render(8, 8)
            val first = checkNotNull(native.lastBuffer)
            assertTrue(first.valid())
            scene.render(16, 12)
            assertFalse(first.valid(), "viewport replacement closes the previous JNA allocation")
            val resized = checkNotNull(native.lastBuffer)
            scene.close()
            assertFalse(resized.valid(), "close releases the current JNA allocation before returning")
            RiveThread.call { kotlin.test.assertFailsWith<IllegalStateException> { scene.render(8, 8) } }
            scene.pointer(RivePointerEvent(RivePointer.MOVE, Offset.Zero))
            RiveThread.call { Unit }
            assertEquals(0, native.pointerCalls.get())
            assertEquals(setOf("rive-render"), native.threads.toSet())
        } finally {
            scene.close()
        }
        assertEquals(1, native.destroyCalls.get())
    }

    @Test
    fun concurrentCloseDestroysTheSceneExactlyOnce() {
        val native = NativeFixture()
        val scene = RiveDesktopScene(native.bridge, Pointer(5), "linux-close", advanceActivity = true)
        val callers = List(8) { Thread { scene.close() }.apply { start() } }
        callers.forEach { it.join(5_000) }
        assertTrue(callers.none { it.isAlive })
        assertEquals(1, native.destroyCalls.get())
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
