package com.letta.mobile.ui.chat.surface.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-bglj6.1: when the scroll-to-latest button shows, and the stream's snap cadence. */
class TimelineScrollToLatestVisibilityTest {
    private val minShowPx = 200f

    @Test
    fun aTallViewportShowsAtFortyPercentAndHidesAtAThirdOfThat() {
        val thresholds = ScrollToLatestThresholds.of(viewportPx = 1_000, minShowPx = minShowPx)
        assertEquals(400f, thresholds.showPx)
        assertEquals(400f / 3f, thresholds.hidePx, absoluteTolerance = 0.01f)
    }

    @Test
    fun aShortViewportNeverShowsUnderTheFloor() {
        val thresholds = ScrollToLatestThresholds.of(viewportPx = 300, minShowPx = minShowPx)
        assertEquals(minShowPx, thresholds.showPx)
    }

    @Test
    fun aNudgeDoesNotShowTheButton() {
        val thresholds = ScrollToLatestThresholds.of(viewportPx = 1_000, minShowPx = minShowPx)
        assertFalse(nextScrollToLatestVisible(visible = false, eligible = true, distancePx = 60f, thresholds = thresholds))
        assertFalse(nextScrollToLatestVisible(visible = false, eligible = true, distancePx = 399f, thresholds = thresholds))
        assertTrue(nextScrollToLatestVisible(visible = false, eligible = true, distancePx = 400f, thresholds = thresholds))
    }

    @Test
    fun onceShownItStaysUntilTheReaderIsBackNearTheNewest() {
        val thresholds = ScrollToLatestThresholds.of(viewportPx = 1_000, minShowPx = minShowPx)
        // Between the two thresholds the button keeps whatever it was: no flicker at either edge.
        assertTrue(nextScrollToLatestVisible(visible = true, eligible = true, distancePx = 200f, thresholds = thresholds))
        assertFalse(nextScrollToLatestVisible(visible = false, eligible = true, distancePx = 200f, thresholds = thresholds))
        assertFalse(nextScrollToLatestVisible(visible = true, eligible = true, distancePx = 133f, thresholds = thresholds))
    }

    @Test
    fun aFollowingReaderIsNeverOfferedIt() {
        val thresholds = ScrollToLatestThresholds.of(viewportPx = 1_000, minShowPx = minShowPx)
        assertFalse(nextScrollToLatestVisible(visible = true, eligible = false, distancePx = 5_000f, thresholds = thresholds))
        assertFalse(nextScrollToLatestVisible(visible = false, eligible = false, distancePx = Float.POSITIVE_INFINITY, thresholds = thresholds))
    }

    @Test
    fun newerRowsThatAreNotResidentAlwaysCountAsFar() {
        val thresholds = ScrollToLatestThresholds.of(viewportPx = 1_000, minShowPx = minShowPx)
        assertTrue(nextScrollToLatestVisible(visible = false, eligible = true, distancePx = Float.POSITIVE_INFINITY, thresholds = thresholds))
    }

    /** The first tick of a stream snaps at once: the old Long.MIN_VALUE sentinel overflowed and never snapped. */
    @Test
    fun theFirstStreamTickSnapsAtOnce() {
        assertEquals(0L, streamSnapDelayMs(nowMs = 0L, lastSnapAtMs = null))
        assertEquals(0L, streamSnapDelayMs(nowMs = 5_000L, lastSnapAtMs = null))
    }

    @Test
    fun aTickInsideTheIntervalWaitsOutTheRestRatherThanBeingDropped() {
        assertEquals(STREAM_SNAP_INTERVAL_MS - 30L, streamSnapDelayMs(nowMs = 1_030L, lastSnapAtMs = 1_000L))
        assertEquals(0L, streamSnapDelayMs(nowMs = 1_000L + STREAM_SNAP_INTERVAL_MS, lastSnapAtMs = 1_000L))
        assertEquals(0L, streamSnapDelayMs(nowMs = 9_000L, lastSnapAtMs = 1_000L))
    }
}
