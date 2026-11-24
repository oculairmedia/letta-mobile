package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.surface.timeline.rows.rowCallbacks
import com.letta.mobile.ui.chat.surface.timeline.rows.rowContext
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-bglj6.1.20: the legacy TimelineKineticOverscrollTest, on the common effect. */
class TimelineElasticOverscrollTest {

    private fun fling(initial: Float, consumed: Float, maxOffsetPx: Float) =
        ElasticFling(Velocity(0f, initial), Velocity(0f, consumed), maxOffsetPx)

    @Test
    fun bounceKeepsOnlyTheResidualTowardAnAvailableEdge() {
        assertEquals(300f, elasticBounceVelocity(fling(1_000f, 700f, 40f), { true }))
        assertEquals(-300f, elasticBounceVelocity(fling(-1_000f, -700f, 40f), { true }))
        // Fully consumed, or under a pixel a second: nothing left to bounce with.
        assertEquals(0f, elasticBounceVelocity(fling(1_000f, 1_000f, 40f), { true }))
        assertEquals(0f, elasticBounceVelocity(fling(1_000f, 999.5f, 40f), { true }))
    }

    @Test
    fun aPageBoundaryIsNotAnEdge() {
        assertEquals(0f, elasticBounceVelocity(fling(1_000f, 0f, 40f), { positive -> !positive }))
        assertEquals(0f, elasticBounceVelocity(fling(-1_000f, 0f, 40f), { positive -> positive }))
    }

    @Test
    fun launchVelocityIsCappedSoThePeakStaysInsideTheBand() {
        val cap = 40f * sqrt(TimelineElasticOverscroll.SPRING_STIFFNESS) * TimelineElasticOverscroll.VELOCITY_CAP_FACTOR
        assertEquals(cap, elasticBounceVelocity(fling(1e6f, 0f, 40f), { true }), 0.01f)
        assertEquals(-cap, elasticBounceVelocity(fling(-1e6f, 0f, 40f), { true }), 0.01f)
        assertEquals(0f, elasticBounceVelocity(fling(Float.NaN, 0f, 40f), { true }))
        // Not measured yet: no band to stretch into.
        assertEquals(0f, elasticBounceVelocity(fling(1_000f, 0f, 0f), { true }))
    }

    @Test
    fun reducedMotionAndPinchBothStandTheBounceDown() {
        assertTrue(timelineElasticOverscrollEnabled(reducedMotion = false, pinching = false))
        assertFalse(timelineElasticOverscrollEnabled(reducedMotion = true, pinching = false))
        assertFalse(timelineElasticOverscrollEnabled(reducedMotion = false, pinching = true))
    }

    @Test
    fun residualFlingAtEitherEdgeMovesThenSettlesExactlyAtZero() = runTest {
        listOf(1f, -1f).forEach { direction ->
            val effect = effect()
            val frames = TestFrameClock()
            val fling = launch(frames) { effect.applyToFling(Velocity(0f, direction * 1_200f)) { Velocity.Zero } }

            frames.advanceUntil { effect.offsetPx != 0f }
            assertTrue(effect.offsetPx * direction > 0f)
            assertTrue(abs(effect.offsetPx) <= effect.maxOffsetPx)
            frames.finish(fling)

            assertEquals(0f, effect.offsetPx)
            assertFalse(fling.isCancelled)
        }
    }

    @Test
    fun consumedFlingAndDirectDragProduceNoOvershoot() = runTest {
        val effect = effect()
        val consumed = Offset(0f, 17f)
        assertEquals(consumed, effect.applyToScroll(consumed, NestedScrollSource.UserInput) { it })
        assertEquals(0f, effect.offsetPx)

        effect.applyToFling(Velocity(0f, 900f)) { it }
        assertEquals(0f, effect.offsetPx)
    }

    @Test
    fun disableDuringFlingClearsAndReenableCannotResurrectIt() = runTest {
        val effect = effect()
        val frames = TestFrameClock()
        val fling = launch(frames) { effect.applyToFling(Velocity(0f, 1_200f)) { Velocity.Zero } }
        frames.advanceUntil { effect.offsetPx != 0f }

        effect.update(false, { true }, { true })
        runCurrent()
        assertEquals(0f, effect.offsetPx)
        assertTrue(fling.isCancelled)

        effect.update(true, { true }, { true })
        repeat(4) { frames.advance() }
        assertEquals(0f, effect.offsetPx)
    }

    @Test
    fun aSecondFlingSilencesTheInterruptedOne() = runTest {
        val effect = effect()
        val firstFrames = TestFrameClock()
        val first = launch(firstFrames) { effect.applyToFling(Velocity(0f, 1_200f)) { Velocity.Zero } }
        firstFrames.advanceUntil { effect.offsetPx > 0f }

        val secondFrames = TestFrameClock()
        val second = launch(secondFrames) { effect.applyToFling(Velocity(0f, -1_200f)) { Velocity.Zero } }
        secondFrames.advanceUntil { effect.offsetPx < 0f }
        val secondValue = effect.offsetPx
        repeat(4) { firstFrames.advance() }
        assertEquals(secondValue, effect.offsetPx)

        secondFrames.finish(second)
        assertEquals(0f, effect.offsetPx)
        assertTrue(first.isCancelled)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aFlingIntoTheOldestEdgeOfAReversedListBouncesAndSettles() = runComposeUiTest {
        val harness = ListHarness()
        mainClock.autoAdvance = false
        setContent { harness.Content(reducedMotion = false) }
        waitForIdle()

        val observed = harness.flingTowardOldest(this)
        assertTrue(observed.any { it > 0f }, "Expected a downward bounce, saw ${observed.minOrNull()}..${observed.maxOrNull()}")
        assertTrue(observed.all { it <= harness.effect.maxOffsetPx })
        assertEquals(0f, observed.last())
        mainClock.autoAdvance = true
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun reducedMotionFlingsWithoutABounce() = runComposeUiTest {
        val harness = ListHarness()
        mainClock.autoAdvance = false
        setContent { harness.Content(reducedMotion = true) }
        waitForIdle()

        val observed = harness.flingTowardOldest(this)
        assertTrue(observed.all { it == 0f })
        mainClock.autoAdvance = true
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aTouchMidBounceTakesOverImmediately() = runTest {
        val effect = effect()
        val frames = TestFrameClock()
        val fling = launch(frames) { effect.applyToFling(Velocity(0f, -1_200f)) { Velocity.Zero } }
        frames.advanceUntil { effect.offsetPx < 0f }

        effect.applyToScroll(Offset(0f, -6f), NestedScrollSource.UserInput) { it }
        runCurrent()
        assertEquals(0f, effect.offsetPx)
        assertTrue(fling.isCancelled)
        frames.finish(fling)
    }

    /**
     * The v0.29 fling judder: while the newest edge's bounce settles, a streamed row lands and the
     * list's reading position leaves the newest edge (canScrollBackward flips true). That must not
     * snap the rows back to rest in one frame: the overshoot keeps settling smoothly, then exactly
     * back at rest. Observed frame-by-frame off the drawn marker row, through the list frame wired
     * exactly as production wires it.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aStreamedRowLandingMidBounceDoesNotSnapTheOvershootBack() = runComposeUiTest {
        val harness = StreamFrameHarness()
        mainClock.autoAdvance = false
        setContent { harness.Content() }
        waitForIdle()

        // The list opens on its newest edge: the marker row rests just above the bottom edge.
        val restY = markerTopY(this)
        onNodeWithTag(STREAM_FRAME).performTouchInput {
            swipeWithVelocity(bottomCenter, topCenter, endVelocity = 8_000f)
        }
        val trace = mutableListOf(restY)
        while (restY - trace.last() < MIN_DISPLACED_PX && trace.size < 300) {
            mainClock.advanceTimeByFrame()
            runOnIdle { trace += markerTopY(this) }
        }
        assertTrue(
            restY - trace.last() >= MIN_DISPLACED_PX,
            "Expected a bounce past the newest edge, saw $trace",
        )
        assertFalse(harness.listState.canScrollBackward, "The fling never left the newest edge")

        // The stream lands mid-bounce: the reader's position leaves the newest edge.
        runOnIdle { harness.prependStreamedRow() }
        repeat(4) {
            mainClock.advanceTimeByFrame()
            runOnIdle { trace += markerTopY(this) }
        }
        assertTrue(harness.listState.canScrollBackward, "The streamed row must move the reader off the newest edge")
        assertTrue(
            trace.takeLast(4).all { restY - it > 0 },
            "The bounce must settle gradually, not snap back in one frame: $trace",
        )

        var framesLeft = 240
        while (abs(restY - trace.last()) > 1 && framesLeft-- > 0) {
            mainClock.advanceTimeByFrame()
            runOnIdle { trace += markerTopY(this) }
        }
        assertTrue(abs(restY - trace.last()) <= 1, "The overshoot must settle exactly back at rest: $trace")
        mainClock.autoAdvance = true
    }

    /**
     * A bounce past a revealed page boundary stands down mid-flight: when Paging reports that the
     * newest edge is a page still to load, not the conversation's confirmed end, the overshoot
     * snaps back at once, exactly as a disable does.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aBoundaryOpeningMidBounceStandsTheOvershootDownAtOnce() = runComposeUiTest {
        val harness = StreamFrameHarness()
        mainClock.autoAdvance = false
        setContent { harness.Content() }
        waitForIdle()

        val restY = markerTopY(this)
        onNodeWithTag(STREAM_FRAME).performTouchInput {
            swipeWithVelocity(bottomCenter, topCenter, endVelocity = 8_000f)
        }
        val trace = mutableListOf(restY)
        while (restY - trace.last() < MIN_DISPLACED_PX && trace.size < 300) {
            mainClock.advanceTimeByFrame()
            runOnIdle { trace += markerTopY(this) }
        }
        assertTrue(restY - trace.last() >= MIN_DISPLACED_PX, "Expected a bounce past the newest edge: $trace")

        runOnIdle { harness.revealNewestPageBoundary() }
        mainClock.advanceTimeByFrame()
        runOnIdle { trace += markerTopY(this) }
        assertTrue(
            abs(restY - trace.last()) <= 1,
            "A revealed page boundary must cancel the bounce at once: $trace",
        )
        mainClock.autoAdvance = true
    }

    /**
     * The list frame with the frame's own wiring, a marker row at the newest edge, and the newest
     * history's confirmed end as the frame holds it: [revealNewestPageBoundary] flips it to play
     * Paging revealing a boundary there mid-bounce.
     */
    private class StreamFrameHarness {
        private var rowKeys by mutableStateOf(listOf(MARKER_KEY) + (0..19).map { "row-$it" })
        private var newestIsAConfirmedEnd by mutableStateOf(true)
        lateinit var listState: LazyListState

        /** Landing one streamed row at the head, as a live run's delta does. */
        fun prependStreamedRow() {
            rowKeys = listOf("streamed-${rowKeys.size}") + rowKeys
        }

        /** Paging reveals the newest edge is a page still to load, not the conversation's end. */
        fun revealNewestPageBoundary() {
            newestIsAConfirmedEnd = false
        }

        @androidx.compose.runtime.Composable
        fun Content() {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                listState = rememberLazyListState()
                TimelineListFrame(
                    listState = listState,
                    bindings = TimelineRowBindings(
                        contexts = TimelineRowContexts(base = rowContext(), streaming = null),
                        callbacks = rowCallbacks(),
                    ),
                    overlays = TimelineFrameOverlays(
                        pinnedPrompt = rememberPinnedPrompt(
                            listState = listState,
                            itemCount = rowKeys.size,
                            topReserve = 0.dp,
                            itemAt = { null },
                        ),
                        showScrollToLatest = false,
                        onScrollToLatest = {},
                        glide = rememberNewestEdgeGlide(listState),
                        bottomReserve = 0.dp,
                    ),
                    modifier = Modifier.width(200.dp).height(240.dp).testTag(STREAM_FRAME),
                    olderHistoryComplete = { true },
                    newerHistoryComplete = { newestIsAConfirmedEnd },
                    content = {
                        items(rowKeys.size, key = { rowKeys[it] }) { index ->
                            if (rowKeys[index] == MARKER_KEY) {
                                Box(Modifier.fillMaxWidth().height(12.dp).background(MARKER_COLOR))
                            } else {
                                Text(rowKeys[index], Modifier.height(48.dp))
                            }
                        }
                    },
                )
            }
        }
    }

    /** The marker row's drawn top edge, in px from the frame's top: the rows' live displacement. */
    @OptIn(ExperimentalTestApi::class)
    private fun markerTopY(test: androidx.compose.ui.test.ComposeUiTest): Int {
        val frame = test.onNodeWithTag(STREAM_FRAME).captureToImage().toPixelMap()
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val color = frame[x, y]
                if (color.red > 0.47f && color.blue > 0.47f && color.green < 0.39f) return y
            }
        }
        val colours = mutableMapOf<Int, Int>()
        for (y in 0 until frame.height) for (x in 0 until frame.width) colours.merge(frame[x, y].hashCode(), 1, Int::plus)
        error(
            "The marker row never drew: no $MARKER_COLOR pixel found in ${frame.width}x${frame.height}; " +
                "top colours: " + colours.entries.sortedByDescending { it.value }.take(6).toList(),
        )
    }

    private class ListHarness {
        lateinit var state: LazyListState
        lateinit var effect: TimelineElasticOverscroll

        @androidx.compose.runtime.Composable
        fun Content(reducedMotion: Boolean) {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                state = rememberLazyListState(initialFirstVisibleItemIndex = 10)
                effect = rememberTimelineElasticOverscroll(
                    pinching = false,
                    canBouncePastPositiveEdge = { !state.canScrollForward },
                    canBouncePastNegativeEdge = { !state.canScrollBackward },
                )
                LazyColumn(
                    modifier = Modifier.height(240.dp).testTag(LIST),
                    state = state,
                    overscrollEffect = effect,
                    reverseLayout = true,
                ) {
                    items((0..20).toList()) { Text("row-$it", Modifier.height(48.dp)) }
                }
            }
        }

        @OptIn(ExperimentalTestApi::class)
        fun flingTowardOldest(test: androidx.compose.ui.test.ComposeUiTest): List<Float> {
            val observed = mutableListOf<Float>()
            test.onNodeWithTag(LIST).performTouchInput {
                swipeWithVelocity(topCenter, bottomCenter, endVelocity = 8_000f)
            }
            repeat(480) {
                test.mainClock.advanceTimeByFrame()
                test.runOnIdle { observed += effect.offsetPx }
            }
            assertFalse(state.canScrollForward, "The fling must reach the oldest edge")
            return observed
        }
    }

    private fun effect() = TimelineElasticOverscroll().apply { maxOffsetPx = 40f }

    private suspend fun TestFrameClock.advanceUntil(predicate: () -> Boolean) {
        repeat(20) {
            advance()
            if (predicate()) return
        }
        error("Animation did not reach expected intermediate state")
    }

    private suspend fun TestFrameClock.finish(job: Job) {
        repeat(240) {
            if (job.isCompleted) return
            advance()
        }
        error("Animation did not settle in finite time")
    }

    private class TestFrameClock : MonotonicFrameClock {
        private val frames = Channel<Long>(Channel.UNLIMITED)
        private var timeNanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())

        suspend fun advance() {
            timeNanos += 16_000_000L
            frames.send(timeNanos)
            yield()
        }
    }

    private companion object {
        const val LIST = "elastic-list"
        const val STREAM_FRAME = "elastic-stream-frame"
        const val MARKER_KEY = "__stream_marker__"
        const val MIN_DISPLACED_PX = 2
        val MARKER_COLOR = Color(0xFFFF00FF)
    }
}
