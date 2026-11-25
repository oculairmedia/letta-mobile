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
import kotlin.test.assertNotNull
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
 * A runtime failure while a live mascot draws must cost that mascot, never the screen. Whatever the
 * scene throws - a JS throw out of the browser's Rive binding, a frame that cannot allocate its
 * pixels, a scene the runtime already released - the surface ends its loop without the throw
 * reaching the composition, the mascot is failed, and its seat shows the orb stand-in. A cancelled
 * frame loop stays cancelled: a surface leaving composition is not a broken mascot.
 */
class RiveFrameFailureTest {
    private val identity = MascotIdentity(MascotShape.entries.first(), TEST_COLOR)

    /** Which scene call fails. */
    private enum class FailsOn { ADVANCE, RENDER, NEVER }

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

    // The window as the web and desktop hosts mount it: the mascot layer at the root (which brings
    // the renderer up and provides the host), then a seat that shows the real orb stand-in whenever
    // the host cannot draw the agent's mascot.
    @Composable
    private fun MascotWindow(
        renderer: RiveFrameRenderer,
        registry: MascotIdentityRegistry = MascotIdentityRegistry(),
    ) {
        ProvideFrameMascots(renderer, registry) {
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
        var failures = 0

        val outcome = runCatching {
            guardFrameWork(scene, Dispatchers.Unconfined, onFailure = { failures++ }) { scene.renderImage(FRAME_SIZE) }
        }

        assertIs<SceneFailure>(outcome.exceptionOrNull(), "the loop ends as a scene failure")
        assertEquals(1, failures, "the mascot is failed exactly once")
        assertTrue(scene.closed, "and its scene released")
    }

    // Leaving composition cancels the frame loop; that is not a broken mascot, so nothing falls back.
    @Test
    fun aCancelledFrameIsNotAFailure() = runTest {
        val scene = FailingScene(CancellationException("the surface left composition"), FailsOn.RENDER)
        var failures = 0

        val outcome = runCatching {
            guardFrameWork(scene, Dispatchers.Unconfined, onFailure = { failures++ }) { scene.renderImage(FRAME_SIZE) }
        }

        assertIs<CancellationException>(outcome.exceptionOrNull(), "the cancellation propagates")
        assertFalse(outcome.exceptionOrNull() is SceneFailure, "and is not turned into a scene failure")
        assertEquals(0, failures, "a cancelled frame fails nothing")
        assertFalse(scene.closed, "and its scene is left for the next surface")
    }

    // A surface that leaves composition cancels the load that was creating its scene. That is not a
    // broken mascot: the entry stays drawable for the next surface instead of being marked failed.
    @Test
    fun aCancelledLoadDoesNotFailTheEntry() = runTest {
        val cancelling = object : RiveFrameRenderer {
            override val available = true
            override val assetVersion = "test"
            override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
            override val stillStore: MascotStillStore? = null

            override fun createScene(): RiveFrameScene = throw CancellationException("the surface left")

            override fun encodePng(image: Image): ByteArray? = null
        }
        val entry = FrameMascotEntry(cancelling, identity)

        val escaped = runCatching { entry.ensureLoaded() }.exceptionOrNull()

        assertIs<CancellationException>(escaped, "the cancellation propagates out of the load")
        assertFalse(entry.failed, "and leaves the mascot drawable for the next surface")
    }

    // ...and the next surface still gets its scene: the cancelled attempt must not stand as "loaded".
    @Test
    fun aCancelledLoadStillLoadsForTheNextSurface() = runTest {
        var attempts = 0
        val flaky = object : RiveFrameRenderer {
            override val available = true
            override val assetVersion = "test"
            override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
            override val stillStore: MascotStillStore? = null

            override fun createScene(): RiveFrameScene {
                attempts++
                if (attempts == 1) throw CancellationException("the surface left")
                return FailingScene(IllegalStateException("never drawn"), FailsOn.RENDER)
            }

            override fun encodePng(image: Image): ByteArray? = null
        }
        val entry = FrameMascotEntry(flaky, identity)

        assertIs<CancellationException>(runCatching { entry.ensureLoaded() }.exceptionOrNull())
        entry.ensureLoaded()

        assertEquals(2, attempts, "the cancelled attempt does not stand as the entry's load")
        assertTrue(entry.scene != null, "so the next surface brings its scene up")
    }

    // A failed mascot costs one view, not the agent: the next view of that agent brings its own scene
    // up, so a broken scene is replaced rather than kept.
    @Test
    fun aFailedMascotIsReplacedByTheNextViewOfThatAgent() = runTest {
        val broken = FailingScene(IllegalStateException("the scene never comes up"), FailsOn.RENDER)
        val first = assertIs<FrameMascotEntry>(
            FrameMascotHost(SceneRenderer { broken }).entry(AGENT_ID, identity),
            "the first view is offered a mascot",
        )
        first.ensureLoaded()
        first.markFailed()
        assertTrue(first.failed, "the first view's scene broke")

        val scenes = mutableListOf<FailingScene>()
        val working = SceneRenderer {
            FailingScene(IllegalStateException("never drawn"), FailsOn.NEVER).also { scenes += it }
        }
        val fresh = assertIs<FrameMascotEntry>(
            FrameMascotHost(working).entry(AGENT_ID, identity),
            "a later view is offered a mascot",
        )
        fresh.ensureLoaded()

        assertNotNull(fresh.scene, "and brings its own scene up")
        assertEquals(1, scenes.size, "without touching the broken one")
    }

    // A failed mascot costs one view, not the agent: the seat that lost it shows the orb stand-in.
    // The registry names the agent, so the seat would draw its scene - only the failed entry turns
    // the seat to the orb, and the scene the window brought up proves that is what happened.
    @Test
    fun aFailedMascotLeavesItsSeatOnTheOrb() = runComposeUiTest {
        mainClock.autoAdvance = false
        val scenes = mutableListOf<FailingScene>()
        val renderer = SceneRenderer {
            FailingScene(IllegalStateException("the scene never draws"), FailsOn.RENDER).also { scenes += it }
        }
        setContent {
            MascotWindow(renderer, MascotIdentityRegistry().apply { update(mapOf(AGENT_ID to identity)) })
        }
        // The seat brings the scene up, then its surface's first rendered frame throws: offer the
        // loop vsyncs until the failure lands and the seat falls to its stand-in.
        repeat(10) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }

        assertTrue(scenes.isNotEmpty(), "the agent is named, so its seat brings a scene up before it fails")
        assertEquals(1, orbsDrawn(), "the seat that lost its mascot shows the orb stand-in, not the scene")
    }

    // prepare() is the browser's bring-up: a renderer that cannot come up leaves every seat on its orb.
    @Test
    fun aRendererThatCannotComeUpDrawsTheOrbInsteadOfCrashingTheWindow() = runComposeUiTest {
        listOf(IllegalStateException("the runtime did not load"), AssertionError("the wasm runtime trapped"))
            .forEach { failure ->
                setContent { MascotWindow(UnbringableRenderer(failure)) }
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
                bringUpSafely(renderer)
                assertFalse(renderer.available, "a bring-up that fails with $failure leaves the renderer unavailable")
            }

        val cancelled = UnbringableRenderer(CancellationException("the effect left composition"))
        val escaped = runCatching { bringUpSafely(cancelled) }.exceptionOrNull()
        assertIs<CancellationException>(escaped, "cancellation propagates out of the bring-up")
    }

    // What the host binds as the surface's onFailure: the real frame loop's guard ends that loop and
    // reports it exactly once, so nothing reaches the composition. The surface that mounts the loop
    // is covered end to end by aRendererThatCannotComeUpDrawsTheOrbInsteadOfCrashingTheWindow.
    @Test
    fun aFailingFrameEndsTheSurfaceLoopInsteadOfTheComposition() = runTest {
        val broken = FailingScene(Error("a wasm trap"), FailsOn.RENDER)
        val renderer = SceneRenderer { broken }
        val host = FrameMascotHost(renderer)
        val entry = assertIs<FrameMascotEntry>(host.entry(AGENT_ID, identity))
        entry.ensureLoaded()
        val drawing = entry.scene ?: error("the entry brought no scene up")
        var reported = 0

        // A frame's own scene work, as RiveFrameSurface runs it: advance and draw the scene.
        val outward = runCatching {
            guardFrameWork(drawing, renderer.dispatcher, onFailure = { reported++ }) { drawing.renderImage(FRAME_SIZE) }
        }.exceptionOrNull()?.takeUnless { it is SceneFailure }

        assertNull(outward, "the frame loop ends the failure; nothing reaches the composition: $outward")
        assertEquals(1, reported, "the surface reports the failed mascot once")
        assertTrue(broken.closed, "and releases the scene it cannot draw")
    }

    private companion object {
        const val AGENT_ID = "agent-a"
        const val TEST_COLOR = 0xFF00AA88.toInt()
        const val ORB_TAG = "mascot-fallback-orb"
        val MASCOT_SIZE: Dp = 40.dp
        val FRAME_SIZE: IntSize = IntSize(2, 2)

        fun blankImage(size: IntSize): Image {
            val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            return Image.makeRaster(info, ByteArray(size.width * size.height * 4), size.width * 4)
        }
    }
}
