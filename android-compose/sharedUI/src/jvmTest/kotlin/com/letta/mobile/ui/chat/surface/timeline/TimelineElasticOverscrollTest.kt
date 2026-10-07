package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
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

    @Test
    fun bounceKeepsOnlyTheResidualTowardAnAvailableEdge() {
        assertEquals(300f, elasticBounceVelocity(1_000f, 700f, 40f, { true }, { true }))
        assertEquals(-300f, elasticBounceVelocity(-1_000f, -700f, 40f, { true }, { true }))
        // Fully consumed, or under a pixel a second: nothing left to bounce with.
        assertEquals(0f, elasticBounceVelocity(1_000f, 1_000f, 40f, { true }, { true }))
        assertEquals(0f, elasticBounceVelocity(1_000f, 999.5f, 40f, { true }, { true }))
    }

    @Test
    fun aPageBoundaryIsNotAnEdge() {
        assertEquals(0f, elasticBounceVelocity(1_000f, 0f, 40f, { false }, { true }))
        assertEquals(0f, elasticBounceVelocity(-1_000f, 0f, 40f, { true }, { false }))
    }

    @Test
    fun launchVelocityIsCappedSoThePeakStaysInsideTheBand() {
        val cap = 40f * sqrt(TimelineElasticOverscroll.SPRING_STIFFNESS) * TimelineElasticOverscroll.VELOCITY_CAP_FACTOR
        assertEquals(cap, elasticBounceVelocity(1e6f, 0f, 40f, { true }, { true }), 0.01f)
        assertEquals(-cap, elasticBounceVelocity(-1e6f, 0f, 40f, { true }, { true }), 0.01f)
        assertEquals(0f, elasticBounceVelocity(Float.NaN, 0f, 40f, { true }, { true }))
        // Not measured yet: no band to stretch into.
        assertEquals(0f, elasticBounceVelocity(1_000f, 0f, 0f, { true }, { true }))
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
    }
}
