package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.SCROLL_TO_BOTTOM_FAB_TAG
import com.letta.mobile.ui.components.ScrollToBottomFab
import ir.farsroidx.overscroll.ElasticOverscrollEffect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TimelineKineticOverscrollTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `real reverse list direct drag never creates stretch before release`() {
        lateinit var effect: TimelineKineticOverscroll
        compose.setContent {
            effect = rememberTimelineKineticOverscroll(
                enabled = true,
                canFlingPastPositiveEdge = { true },
                canFlingPastNegativeEdge = { true },
            )
            LazyColumn(
                modifier = Modifier.height(240.dp),
                overscrollEffect = effect,
                reverseLayout = true,
            ) {
                items((0..20).toList()) { Text("row-$it", Modifier.height(48.dp)) }
            }
        }

        compose.onRoot().performTouchInput {
            down(center)
            moveTo(center.copy(y = center.y + 80f), delayMillis = 300)
            assertEquals(0f, effect.renderEffect.mOverscrollValue)
            up()
        }
        compose.runOnIdle { assertEquals(0f, effect.renderEffect.mOverscrollValue) }
    }

    @Test
    fun `real reverse list flings from interior expose both live edge mappings`() {
        compose.mainClock.autoAdvance = false
        lateinit var harness: RealListHarness
        compose.setContent { harness = RealListHarness.remember(initialIndex = 10) }
        // Settle the FAB's entrance animation before freezing the gesture observations.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("row-10").assertIsDisplayed()
        compose.onNodeWithTag(SCROLL_TO_BOTTOM_FAB_TAG).assertIsDisplayed()

        flingAndObserve(harness, towardPositiveEdge = false)
        compose.onNodeWithTag(SCROLL_TO_BOTTOM_FAB_TAG).assertDoesNotExist()
        compose.runOnIdle { harness.state.requestScrollToItem(10) }
        compose.waitForIdle()
        flingAndObserve(harness, towardPositiveEdge = true)

        compose.mainClock.autoAdvance = true
    }

    @Test
    fun `real reverse list temporary boundary cannot move render node`() {
        compose.mainClock.autoAdvance = false
        lateinit var harness: RealListHarness
        compose.setContent {
            harness = RealListHarness.remember(
                initialIndex = 10,
                positivePagingComplete = false,
            )
        }
        compose.waitForIdle()

        compose.onRoot().performTouchInput {
            swipeWithVelocity(topCenter, bottomCenter, endVelocity = 8_000f)
        }
        repeat(180) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals(0f, harness.effect.renderEffect.mOverscrollValue) }
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun `residual fling at either true edge moves then settles exactly at zero`() = runTest {
        listOf(1f, -1f).forEach { direction ->
            val effect = effect(positiveEdge = true, negativeEdge = true)
            val frames = TestFrameClock()
            val fling = launch(frames) {
                effect.applyToFling(Velocity(0f, direction * 1_200f)) { Velocity.Zero }
            }

            frames.advanceUntil { effect.renderEffect.mOverscrollValue != 0f }
            assertTrue(effect.renderEffect.mOverscrollValue * direction > 0f)
            assertTrue(kotlin.math.abs(effect.renderEffect.mOverscrollValue) <= effect.renderEffect.maxOverscroll)
            frames.finish(fling)

            assertEquals(0f, effect.renderEffect.mOverscrollValue)
            assertFalse(fling.isCancelled)
        }
    }

    @Test
    fun `consumed fling and direct drag produce no overshoot`() = runTest {
        val effect = effect()
        val consumed = Offset(0f, 17f)

        assertEquals(
            consumed,
            effect.applyToScroll(Offset(0f, 17f), NestedScrollSource.UserInput) { consumed },
        )
        assertEquals(0f, effect.renderEffect.mOverscrollValue)

        effect.applyToFling(Velocity(0f, 900f)) { it }
        assertEquals(0f, effect.renderEffect.mOverscrollValue)
    }

    @Test
    fun `paging locks temporary boundaries in both directions`() = runTest {
        val effect = effect(positiveEdge = false, negativeEdge = false)

        effect.applyToFling(Velocity(0f, 900f)) { Velocity.Zero }
        effect.applyToFling(Velocity(0f, -900f)) { Velocity.Zero }

        assertEquals(0f, effect.renderEffect.mOverscrollValue)
    }

    @Test
    fun `disable during fling clears offset and reenable cannot resurrect it`() = runTest {
        val effect = effect()
        val frames = TestFrameClock()
        val fling = launch(frames) {
            effect.applyToFling(Velocity(0f, 1_200f)) { Velocity.Zero }
        }
        frames.advanceUntil { effect.renderEffect.mOverscrollValue != 0f }

        effect.update(false, { true }, { true })
        runCurrent()
        assertEquals(0f, effect.renderEffect.mOverscrollValue)
        assertTrue(fling.isCancelled)

        // Pinch and reduced-motion both use the same disable path; reenabling must start clean.
        effect.update(true, { true }, { true })
        repeat(4) { frames.advance() }
        assertEquals(0f, effect.renderEffect.mOverscrollValue)
    }

    @Test
    fun `second fling prevents stale writes from interrupted fling`() = runTest {
        val effect = effect()
        val firstFrames = TestFrameClock()
        val first = launch(firstFrames) {
            effect.applyToFling(Velocity(0f, 1_200f)) { Velocity.Zero }
        }
        firstFrames.advanceUntil { effect.renderEffect.mOverscrollValue > 0f }

        val secondFrames = TestFrameClock()
        val second = launch(secondFrames) {
            effect.applyToFling(Velocity(0f, -1_200f)) { Velocity.Zero }
        }
        secondFrames.advanceUntil { effect.renderEffect.mOverscrollValue < 0f }
        val secondValue = effect.renderEffect.mOverscrollValue
        repeat(4) { firstFrames.advance() }
        assertEquals(secondValue, effect.renderEffect.mOverscrollValue)

        secondFrames.finish(second)
        assertEquals(0f, effect.renderEffect.mOverscrollValue)
        assertTrue(first.isCancelled)
    }

    @Test
    fun `newest edge keeps affordance absent while genuine distance shows it`() {
        assertFalse(shouldShowNewestAffordance(isAnchoredAwayFromTail = false, canScrollTowardNewest = false))
        assertTrue(shouldShowNewestAffordance(isAnchoredAwayFromTail = false, canScrollTowardNewest = true))
        assertTrue(shouldShowNewestAffordance(isAnchoredAwayFromTail = true, canScrollTowardNewest = false))
    }

    private fun effect(
        positiveEdge: Boolean = true,
        negativeEdge: Boolean = true,
    ): TimelineKineticOverscroll = TimelineKineticOverscroll(
        renderEffect = ElasticOverscrollEffect(
            maxOverscroll = 40f,
            maxStretchRatio = 8,
            orientation = Orientation.Vertical,
        ),
        enabled = true,
        canFlingPastPositiveEdge = { positiveEdge },
        canFlingPastNegativeEdge = { negativeEdge },
    )

    private fun flingAndObserve(harness: RealListHarness, towardPositiveEdge: Boolean) {
        val observed = mutableListOf<Float>()
        val initialIndex = harness.state.firstVisibleItemIndex
        compose.onRoot().performTouchInput {
            if (towardPositiveEdge) {
                swipeWithVelocity(topCenter, bottomCenter, endVelocity = 8_000f)
            } else {
                swipeWithVelocity(bottomCenter, topCenter, endVelocity = 8_000f)
            }
        }
        repeat(240) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { observed += harness.effect.renderEffect.mOverscrollValue }
        }
        assertTrue("Fling must move from interior item $initialIndex; ended at ${harness.state.firstVisibleItemIndex}", harness.state.firstVisibleItemIndex != initialIndex)
        assertTrue("Expected signed overshoot, observed range ${observed.minOrNull()}..${observed.maxOrNull()}", observed.any { if (towardPositiveEdge) it > 0f else it < 0f })
        repeat(240) {
            if (observed.last() == 0f) return@repeat
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { observed += harness.effect.renderEffect.mOverscrollValue }
        }
        assertEquals(0f, observed.last())
    }

    private class RealListHarness(
        val state: LazyListState,
        val effect: TimelineKineticOverscroll,
    ) {
        companion object {
            @androidx.compose.runtime.Composable
            fun remember(
                initialIndex: Int = 0,
                positivePagingComplete: Boolean = true,
            ): RealListHarness {
                val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                val effect = rememberTimelineKineticOverscroll(
                    enabled = true,
                    canFlingPastPositiveEdge = {
                        !state.canScrollForward && positivePagingComplete
                    },
                    canFlingPastNegativeEdge = {
                        !state.canScrollBackward
                    },
                )
                Box(Modifier.fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        state = state,
                        overscrollEffect = effect,
                        reverseLayout = true,
                    ) {
                        items((0..20).toList()) { Text("row-$it", Modifier.height(48.dp)) }
                    }
                    ScrollToBottomFab(
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd),
                        visible = shouldShowNewestAffordance(false, state.canScrollBackward),
                        onClick = {},
                    )
                }
                return remember(state, effect) { RealListHarness(state, effect) }
            }
        }
    }

    private suspend fun TestFrameClock.advanceUntil(predicate: () -> Boolean) {
        repeat(20) {
            advance()
            if (predicate()) return
        }
        error("Animation did not reach expected intermediate state")
    }

    private suspend fun TestFrameClock.finish(job: kotlinx.coroutines.Job) {
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
            kotlinx.coroutines.yield()
        }
    }
}
