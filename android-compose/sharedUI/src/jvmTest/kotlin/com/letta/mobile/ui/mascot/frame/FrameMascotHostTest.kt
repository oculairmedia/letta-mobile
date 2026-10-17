package com.letta.mobile.ui.mascot.frame

import androidx.compose.ui.unit.IntSize
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveWrite
import com.letta.mobile.avatar.rive.RiveWriteSink
import com.letta.mobile.ui.mascot.MascotStillStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

class FrameMascotHostTest {
    private class RecordingScene : RiveFrameScene() {
        val writes = mutableListOf<RiveWrite>()
        var advanced = 0f
        var closed = false

        override val inputSink = RiveWriteSink { writes += it }

        override fun advance(step: RiveFrameStep) {
            advanced += step.seconds
        }

        override fun renderImage(size: IntSize): Image {
            val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            return Image.makeRaster(info, ByteArray(size.width * size.height * 4), size.width * 4)
        }

        override fun pointer(event: RivePointerEvent) = Unit

        override fun close() {
            closed = true
        }
    }

    /** Whether the fake renderer can bring a scene up. */
    private enum class SceneBringUp { WORKS, FAILS }

    private class FakeRenderer(private val bringUp: SceneBringUp = SceneBringUp.WORKS) : RiveFrameRenderer {
        val scenes = mutableListOf<RecordingScene>()
        override val available = true
        override val assetVersion = "test"
        override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        override val stillStore: MascotStillStore? = null

        override fun createScene(): RiveFrameScene {
            check(bringUp == SceneBringUp.WORKS) { "no renderer" }
            return RecordingScene().also { scenes += it }
        }

        override fun encodePng(image: Image): ByteArray = byteArrayOf(1, 2, 3)
    }

    @Test
    fun identityIsTheFirstWriteTheSceneSees() = runTest {
        val renderer = FakeRenderer()
        val entry = FrameMascotEntry(renderer, MascotIdentity.seeded("agent-a"))
        entry.ensureLoaded()
        val scene = assertNotNull(renderer.scenes.single())
        val shape = RiveWrite.Enum(RiveAvatarContract.INPUT_SHAPE, RiveAvatarContract.shapeKey(entry.identity.shape))
        assertEquals(shape, scene.writes.first())
        assertEquals(scene, entry.scene)
    }

    @Test
    fun aSceneThatCannotBeCreatedMarksTheEntryFailed() = runTest {
        val host = FrameMascotHost(FakeRenderer(SceneBringUp.FAILS))
        val entry = assertNotNull(host.entry("agent-a", MascotIdentity.seeded("agent-a")))
        entry.ensureLoaded()
        assertTrue((entry as FrameMascotEntry).failed)
        assertNull(host.entry("agent-a", MascotIdentity.seeded("agent-a")))
    }

    @Test
    fun advanceToStepsOncePerFrameAndCapsTheStep() {
        val scene = RecordingScene()
        scene.advanceTo(1_000_000_000L)
        scene.advanceTo(1_016_000_000L)
        scene.advanceTo(1_016_000_000L)
        assertEquals(0.016f, scene.advanced, 1e-6f)
        scene.advanceTo(61_016_000_000L)
        assertEquals(0.116f, scene.advanced, 1e-6f)
    }

    @Test
    fun stillsCaptureInOneWarmScene() = runTest {
        val renderer = FakeRenderer()
        assertNotNull(FrameMascotHost(renderer).stills)
        val capture = FrameStillCapture(renderer)
        assertNotNull(capture.capture(MascotIdentity.seeded("a")))
        assertNotNull(capture.capture(MascotIdentity.seeded("b")))
        assertEquals(1, renderer.scenes.size)
    }
}
