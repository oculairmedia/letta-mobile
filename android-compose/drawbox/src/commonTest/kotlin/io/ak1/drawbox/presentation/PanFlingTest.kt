package io.ak1.drawbox.presentation

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanFlingTest {
    /** A 60 Hz frame clock on the test's virtual time, so a coast runs to its end. */
    private class TestFrameClock(private val test: TestScope) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            return onFrame(test.testScheduler.currentTime * 1_000_000)
        }
    }

    private fun TestScope.fling(onPan: (Offset) -> Unit): PanFling =
        PanFling(CoroutineScope(StandardTestDispatcher(testScheduler) + TestFrameClock(this)), onPan)

    /** A quick swipe: 100 px every 16 ms, ending at [endMillis]. */
    private fun PanFling.swipe(endMillis: Long) {
        begin(endMillis - 64, Offset.Zero)
        for (i in 1..4) track(endMillis - 64 + i * 16L, Offset(i * 100f, 0f))
    }

    @Test
    fun aFingerLiftedMidSwipeThrowsTheBoard() = runTest {
        var panned = 0f
        val fling = fling { panned += it.x }
        fling.swipe(endMillis = 1_000)
        fling.release(liftMillis = 1_008)
        advanceUntilIdle()
        assertTrue(panned > 100f, "coasted $panned px")
    }

    @Test
    fun aFingerThatStoppedBeforeLiftingThrowsNothing() = runTest {
        var panned = 0f
        val fling = fling { panned += it.x }
        fling.swipe(endMillis = 1_000)
        fling.release(liftMillis = 1_400)
        advanceUntilIdle()
        assertEquals(0f, panned)
    }

    @Test
    fun aPressDuringTheIdleWaitCancelsTheCoastItWouldStart() = runTest {
        var panned = 0f
        val fling = fling { panned += it.x }
        fling.swipe(endMillis = 1_000)
        fling.releaseWhenIdle(60, coast = true)
        assertTrue(fling.awaitingIdle)
        fling.stop()
        advanceUntilIdle()
        assertFalse(fling.awaitingIdle)
        assertEquals(0f, panned, "a press caught the board before it coasted")
    }
}
