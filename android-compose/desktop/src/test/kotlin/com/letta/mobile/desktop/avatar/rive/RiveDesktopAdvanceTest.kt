package com.letta.mobile.desktop.avatar.rive

import com.letta.mobile.util.textOrNull

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.ui.mascot.frame.RiveFrameStep
import com.letta.mobile.util.Telemetry
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.letta.mobile.ui.mascot.frame.RivePointer
import com.letta.mobile.ui.mascot.frame.RivePointerEvent
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RiveDesktopAdvanceTest {
    private var ownedTelemetryHistory: AutoCloseable? = null

    @kotlin.test.BeforeTest
    fun installOwnedTelemetryHistory() {
        ownedTelemetryHistory = com.letta.mobile.util.Telemetry.installRecorder(com.letta.mobile.util.TelemetryRecorder())
    }

    @kotlin.test.AfterTest
    fun detachOwnedTelemetryHistory() {
        ownedTelemetryHistory?.close()
    }

    private class NativeFixture {
        var activityResult = 1
        var createFails = false
        var renderResult = 0
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
                "rive_bridge_create" -> {
                    val name = (if (createFails) "test create failure" else "test adapter").encodeToByteArray()
                    name.copyInto(args[0] as ByteArray)
                    if (createFails) null else Pointer(12)
                }
                "riveBridgeAdvanceIsActive" -> {
                    activityCalls.incrementAndGet()
                    beforeActivityResult()
                    activityResult
                }
                "rive_bridge_advance" -> { legacyCalls.incrementAndGet(); null }
                "rive_bridge_render" -> {
                    lastBuffer = args[4] as Memory
                    renderCalls.incrementAndGet()
                    lastBuffer?.clear()
                    renderResult
                }
                "rive_bridge_pointer" -> { pointerCalls.incrementAndGet(); null }
                "rive_bridge_destroy" -> { destroyCalls.incrementAndGet(); null }
                else -> error("Unexpected native call ${method.name}")
            }
        } as RiveBridgeNative
    }

    @Test
    fun rendererEvidenceFollowsSuccessfulReadbackOnceAcrossScenesAndRecreation() = RiveThread.call {
        val wasReported = RiveDesktopScene.rendererActiveReported
        RiveDesktopScene.rendererActiveReported = false
        val native = NativeFixture()
        val path = "/packaged resources/librive_desktop_bridge.so"
        val scenes = mutableListOf<RiveDesktopScene>()
        fun evidence() = Telemetry.events.value.filter { it.name == "[mascot] Rive native renderer active:" }
        val before = evidence().size
        try {
            native.createFails = true
            assertFailsWith<IllegalStateException> { RiveDesktopScene.create(native.bridge, RiveLibraryPath(path), true) }
            assertEquals(before, evidence().size, "failed creation cannot claim a render")
            native.createFails = false
            val first = RiveDesktopScene.create(native.bridge, RiveLibraryPath(path), true).also { scenes.add(it) }
            val second = RiveDesktopScene.create(native.bridge, RiveLibraryPath(path), true).also { scenes.add(it) }
            assertEquals(before, evidence().size, "creation alone is not render evidence")
            native.renderResult = 1
            assertFailsWith<IllegalStateException> { first.render(IntSize(2, 2)) }
            assertEquals(before, evidence().size, "failed rendering cannot claim readback")
            native.renderResult = 0
            assertEquals(ByteArray(16).toList(), first.render(IntSize(2, 2)).toList())
            assertEquals(before + 1, evidence().size)
            first.render(IntSize(3, 3))
            second.render(IntSize(2, 2))
            scenes.forEach { it.close() }
            RiveDesktopScene.create(native.bridge, RiveLibraryPath(path), true).also { scenes.add(it) }.render(IntSize(1, 1))
            val events = evidence()
            assertEquals(before + 1, events.size, "all scenes share one process-wide report")
            assertEquals(Telemetry.Level.INFO, events.first().level)
            assertEquals("test adapter", events.first().attrs["adapter"]?.textOrNull)
            assertEquals(path, events.first().attrs["lib"]?.textOrNull)
            assertEquals(setOf("rive-render"), native.threads.toSet())
        } finally {
            scenes.forEach { it.close() }
            RiveDesktopScene.rendererActiveReported = wasReported
        }
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
            scene.render(IntSize(8, 8))
            val first = checkNotNull(native.lastBuffer)
            assertTrue(first.valid())
            scene.render(IntSize(16, 12))
            assertFalse(first.valid(), "viewport replacement closes the previous JNA allocation")
            val resized = checkNotNull(native.lastBuffer)
            scene.close()
            assertFalse(resized.valid(), "close releases the current JNA allocation before returning")
            RiveThread.call { kotlin.test.assertFailsWith<IllegalStateException> { scene.render(IntSize(8, 8)) } }
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
