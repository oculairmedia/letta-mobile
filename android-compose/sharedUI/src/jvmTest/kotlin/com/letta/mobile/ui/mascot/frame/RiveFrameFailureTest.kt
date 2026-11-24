@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.mascot.frame

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.model.MascotIdentity
import com.letta.mobile.avatar.core.model.MascotShape
import com.letta.mobile.avatar.rive.RiveWriteSink
import com.letta.mobile.ui.chat.AgentSphere
import com.letta.mobile.ui.mascot.MascotIdentityRegistry
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotSeatVacancy
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.MascotStillStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * letta-mobile-ckmub.17.20: a runtime failure while a live mascot draws must cost that mascot, never
 * the screen. Whatever the scene throws - a JS throw out of the browser's Rive binding, a frame
 * that cannot allocate its pixels, a scene the runtime already released - the mascot is failed and
 * its seat shows the orb stand-in; composition and every other mascot keep running. A cancelled
 * frame loop stays cancelled: a surface leaving composition is not a broken mascot.
 */
class RiveFrameFailureTest {
    private val identity = MascotIdentity(MascotShape.entries.first(), TEST_COLOR)

    /** Which scene call fails. */
    private enum class FailsOn { ADVANCE, RENDER }

    /** A scene whose frame work fails the way a broken Rive runtime does. */
    private class FailingScene(
        private val failure: Throwable,
        private val failsOn: FailsOn,
    ) : RiveFrameScene() {
        var closed = false

        override val inputSink = RiveWriteSink { }

        override fun advance(step: RiveFrameStep) = failIf(FailsOn.ADVANCE)

        override fun renderImage(size: IntSize): Image {
            failIf(FailsOn.RENDER)
            return blankImage(size)
        }

        private fun failIf(failing: FailsOn) {
            if (failsOn == failing) throw failure
        }

        override fun pointer(event: RivePointerEvent) = Unit

        override fun close() {
            closed = true
        }
    }

    /** A renderer that draws scenes and can bring scenes up, as the desktop's native renderer does. */
    private class SceneRenderer(private val scene: () -> RiveFrameScene) : RiveFrameRenderer {
        override val available = true
        override val assetVersion = "test"
        override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        override val stillStore: MascotStillStore? = null

        override fun createScene(): RiveFrameScene = scene()

        override fun encodePng(image: Image): ByteArray? = null
    }

    /** A renderer whose bring-up fails with [failure], as a browser whose Rive never came up does. */
    private class UnbringableRenderer(private val failure: Throwable) : RiveFrameRenderer {
        override val available: Boolean get() = false
        override val assetVersion: String get() = error("no asset version before the renderer is up")
        override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        override val stillStore: MascotStillStore? = null

        override suspend fun prepare() = throw failure

        override fun createScene(): RiveFrameScene = error("no scene before the renderer is up")

        override fun encodePng(image: Image): ByteArray? = null
    }

    /**
     * The window as the web and desktop hosts mount it: the mascot layer at the root (which brings
     * the renderer up and provides the host), then a seat that shows the real orb stand-in whenever
     * the host cannot draw the agent's mascot.
     */
    @Composable
    private fun MascotWindow(host: FrameMascotHost) {
        ProvideFrameMascots(host.riveRenderer, MascotIdentityRegistry()) {
            MascotSeat(AGENT_ID, MascotStage.COMPOSER_COMPANION, MASCOT_SIZE, empty = ::seatStandIn)
        }
    }

    @Composable
    private fun seatStandIn(vacancy: MascotSeatVacancy) {
        if (vacancy == MascotSeatVacancy.NO_MASCOT) AgentSphere(MASCOT_SIZE, Modifier.testTag(ORB_TAG))
    }

    private fun ComposeUiTest.orbsDrawn(): Int = onAllNodesWithTag(ORB_TAG).fetchSemanticsNodes().size

    // One frame of work that throws fails this mascot once and ends the loop as a failure; the throw
    // never reaches the composition, and the scene is released.
    @Test
    fun aFrameThatThrowsFailsTheMascotOnceAndEndsTheLoop() = runTest {
        val scene = FailingScene(IllegalStateException("a JS throw"), FailsOn.RENDER)
        val entry = FrameMascotEntry(SceneRenderer { scene }, identity)
        entry.ensureLoaded()
        var failures = 0

        val outcome = runCatching {
            guardFrameWork(scene, "drawing", onFailure = { failures++; entry.markFailed() }) { scene.renderImage(FRAME_SIZE) }
        }

        assertIs<SceneFailure>(outcome.exceptionOrNull(), "the loop ends as a scene failure")
        assertEquals(1, failures, "the mascot is failed exactly once")
        assertTrue(scene.closed, "and its scene released")
        assertTrue(entry.failed, "the entry stops being offered")
    }

    // Leaving composition cancels the frame loop; that is not a broken mascot, so nothing falls back.
    @Test
    fun aCancelledFrameIsNotAFailure() = runTest {
        val scene = FailingScene(CancellationException("the surface left composition"), FailsOn.RENDER)
        val entry = FrameMascotEntry(SceneRenderer { scene }, identity)
        entry.ensureLoaded()
        var failures = 0

        val outcome = runCatching {
            guardFrameWork(scene, "drawing", onFailure = { failures++; entry.markFailed() }) { scene.renderImage(FRAME_SIZE) }
        }

        assertIs<CancellationException>(outcome.exceptionOrNull(), "the cancellation propagates")
        assertFalse(outcome.exceptionOrNull() is SceneFailure, "and is not turned into a scene failure")
        assertEquals(0, failures, "a cancelled frame fails nothing")
        assertFalse(entry.failed, "so the mascot is still drawable")
        assertFalse(scene.closed, "and its scene is left for the next surface")
    }

    // prepare() is the browser's bring-up: a renderer that cannot come up leaves every seat on its orb.
    @Test
    fun aRendererThatCannotComeUpDrawsTheOrbInsteadOfCrashingTheWindow() = runComposeUiTest {
        listOf(IllegalStateException("the runtime did not load"), AssertionError("the wasm runtime trapped"))
            .forEach { failure ->
                setContent { MascotWindow(FrameMascotHost(UnbringableRenderer(failure))) }
                waitForIdle()
                assertEquals(1, orbsDrawn(), "a bring-up that fails with $failure keeps the seat on its orb")
            }
    }

    // The shared bring-up every window runs, whatever a platform's prepare() does: absorb every
    // failure but cancellation, so composition survives a broken renderer.
    @Test
    fun bringUpAbsorbsEveryFailureButCancellation() = runTest {
        listOf(IllegalStateException("no runtime"), AssertionError("wasm trap"), Error("out of memory"))
            .forEach { failure ->
                val renderer = UnbringableRenderer(failure)
                renderer.let { bringUpSafely(it) }
                assertFalse(renderer.available, "a bring-up that fails with $failure leaves the renderer unavailable")
            }

        val cancelled = UnbringableRenderer(CancellationException("the effect left composition"))
        val escaped = runCatching { bringUpSafely(cancelled) }.exceptionOrNull()
        assertIs<CancellationException>(escaped, "cancellation propagates out of the bring-up")
    }

    // The same guarantee one level down: the web's renderer extends PreparedFrameRenderer, which
    // absorbs its own bring-up failures so nothing reaches the effect that called it.
    @Test
    fun aPreparedRendererAbsorbsItsBringUpFailure() = runTest {
        var broughtUp = 0
        val trapped = object : PreparedFrameRenderer() {
            override val available = false
            override val assetVersion: String get() = error("never available")
            override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
            override val stillStore: MascotStillStore? = null
            override suspend fun bringUp() {
                broughtUp++
                throw AssertionError("wasm trap")
            }
            override fun createScene(): RiveFrameScene = error("never available")
            override fun encodePng(image: Image): ByteArray? = null
        }
        trapped.prepare()

        assertEquals(1, broughtUp, "the platform's bring-up ran")
        assertFalse(trapped.available, "a bring-up that throws leaves the renderer unavailable")

        val cancelled = object : PreparedFrameRenderer() {
            override val available = true
            override val assetVersion: String get() = "test"
            override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
            override val stillStore: MascotStillStore? = null
            override suspend fun bringUp() = throw CancellationException("left composition")
            override fun createScene(): RiveFrameScene = error("not in this test")
            override fun encodePng(image: Image): ByteArray? = null
        }
        assertIs<CancellationException>(runCatching { cancelled.prepare() }.exceptionOrNull(), "a cancelled bring-up propagates")
    }

    // A failed mascot is no longer offered: the next surface to ask gets a new entry (and so a new
    // scene) rather than a dead one, which is how a mascot recovers on its next mount.
    @Test
    fun aFailedMascotIsNoLongerOfferedAndTheNextMountGetsAFreshEntry() = runTest {
        val broken = FailingScene(IllegalStateException("broken"), FailsOn.RENDER)
        val fresh = FailingScene(IllegalStateException("never thrown"), FailsOn.RENDER)
        val scenes = ArrayDeque(listOf<RiveFrameScene>(broken, fresh))
        val host = FrameMascotHost(SceneRenderer { scenes.removeFirst() })

        val first = assertIs<FrameMascotEntry>(host.entry(AGENT_ID, identity))
        first.ensureLoaded()
        val ended = runCatching {
            guardFrameWork(broken, "drawing", onFailure = first::markFailed) { broken.renderImage(FRAME_SIZE) }
        }
        assertIs<SceneFailure>(ended.exceptionOrNull(), "the throw ended the frame loop")

        assertTrue(first.failed, "the mascot is failed")
        assertTrue(broken.closed, "and its scene released")
        assertNull(host.entry(AGENT_ID, identity), "the failed mascot is no longer offered to any seat")
        assertFalse(host.available.not(), "the renderer itself is still up: only this mascot failed")
    }

    private companion object {
        const val AGENT_ID = "agent-a"
        const val TEST_COLOR = 0xFF00AA88.toInt()
        const val ORB_TAG = "mascot-fallback-orb"
        val MASCOT_SIZE: Dp = 40.dp
        const val FRAMES = 4
        val FRAME_SIZE: IntSize = IntSize(2, 2)

        fun blankImage(size: IntSize): Image {
            val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            return Image.makeRaster(info, ByteArray(size.width * size.height * 4), size.width * 4)
        }
    }
}
