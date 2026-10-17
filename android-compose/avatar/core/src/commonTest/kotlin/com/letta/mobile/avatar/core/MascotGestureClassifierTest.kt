package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.4: the host's reading of pointer steps on the mascot - the same table on
 * Android, desktop and web, walked with explicit times (ms) and positions (dp).
 */
class MascotGestureClassifierTest {
    private val classifier = MascotGestureClassifier()

    private fun tap(at: Long, x: Float = 10f, y: Float = 10f): MascotGesture {
        classifier.down(at, x, y)
        return classifier.up(at + TAP_MILLIS)
    }

    @Test
    fun aQuickTapIsATap() {
        assertEquals(MascotGesture.TAP, tap(0L))
    }

    @Test
    fun aPressThatWandersPastTheSlopIsNoTap() {
        classifier.down(0L, 0f, 0f)
        classifier.move(20L, 30f, 0f)
        assertEquals(MascotGesture.NONE, classifier.up(40L))
    }

    @Test
    fun aPressTheSeatsDragTakesIsNoTap() {
        classifier.down(0L, 0f, 0f)
        classifier.cancel(16L)
        assertEquals(MascotGesture.NONE, classifier.up(40L))
    }

    @Test
    fun aDoubleTapPlaysATrickAndTricksCycleThroughAllFour() {
        val tricks = mutableListOf<Int>()
        var at = 0L
        repeat(5) {
            assertEquals(MascotGesture.TAP, tap(at))
            assertEquals(MascotGesture.TRICK, tap(at + DOUBLE_GAP))
            tricks += classifier.trick
            at += TRICK_SPACING
        }
        assertEquals(listOf(0, 1, 2, 3, 0), tricks)
    }

    @Test
    fun aDoubleTapWhileATrickCoolsDownIsANudgeNotAClick() {
        tap(0L)
        assertEquals(MascotGesture.TRICK, tap(DOUBLE_GAP))
        assertEquals(MascotGesture.TAP, tap(500L))
        assertEquals(MascotGesture.NUDGE, tap(500L + DOUBLE_GAP), "a second trick inside 1.5 s")
        assertEquals(0, classifier.trick, "a cooled-down trick does not skip an id")
    }

    @Test
    fun twoTapsFarApartAreTwoTaps() {
        assertEquals(MascotGesture.TAP, tap(0L))
        assertEquals(MascotGesture.TAP, tap(DOUBLE_GAP, x = 200f))
        assertEquals(MascotGesture.TAP, tap(1_000L))
    }

    @Test
    fun aStillLongPressPetsAndPettingReleasesAfterTheLift() {
        classifier.down(0L, 0f, 0f)
        assertEquals(400L, classifier.deadline())
        assertEquals(MascotGesture.NONE, classifier.tick(399L))
        assertEquals(MascotGesture.PET_START, classifier.tick(400L))
        assertTrue(classifier.petting)
        assertEquals(MascotGesture.NONE, classifier.up(2_000L), "lifting is not a tap")
        assertEquals(2_600L, classifier.deadline())
        assertEquals(MascotGesture.NONE, classifier.tick(2_599L))
        assertEquals(MascotGesture.PET_END, classifier.tick(2_600L))
        assertFalse(classifier.petting)
        assertEquals(MascotGestureClassifier.NEVER, classifier.deadline())
    }

    @Test
    fun aSlowStrokeAcrossTheBodyPets() {
        classifier.down(0L, 0f, 0f)
        // 60 dp in 500 ms: 0.12 dp/ms, well under the 0.3 limit.
        var gesture = MascotGesture.NONE
        for (step in 1..10) {
            val g = classifier.move(step * 50L, step * 6f, 0f)
            if (g != MascotGesture.NONE) gesture = g
        }
        assertEquals(MascotGesture.PET_START, gesture)
    }

    @Test
    fun aFastSwipeNeverPets() {
        classifier.down(0L, 0f, 0f)
        classifier.move(16L, 40f, 0f)
        classifier.move(500L, 41f, 0f)
        assertEquals(MascotGesture.NONE, classifier.tick(600L))
        assertFalse(classifier.petting)
        assertEquals(MascotGestureClassifier.NEVER, classifier.deadline())
    }

    @Test
    fun aStrokeThatResumesBeforeTheReleaseKeepsPetting() {
        classifier.down(0L, 0f, 0f)
        classifier.tick(400L)
        classifier.up(800L)
        classifier.down(1_000L, 0f, 0f)
        assertEquals(MascotGesture.NONE, classifier.tick(1_400L), "still the same petting")
        assertTrue(classifier.petting)
        assertEquals(MascotGestureClassifier.NEVER, classifier.deadline(), "held: nothing to time")
    }

    @Test
    fun hoverReportsOnlyChanges() {
        assertEquals(MascotGesture.HOVER_IN, classifier.hover(true))
        assertEquals(MascotGesture.NONE, classifier.hover(true))
        assertEquals(MascotGesture.HOVER_OUT, classifier.hover(false))
        assertEquals(MascotGesture.NONE, classifier.hover(false))
    }

    @Test
    fun aStillPressTimedOutWithoutPettingAsksForNoMoreTicks() {
        // Jitter inside the slop but too fast to be a stroke: the long-press moment passes once.
        classifier.down(0L, 0f, 0f)
        // ~300 dp of path in 350 ms, never more than 3 dp from where it went down.
        for (step in 1..50) classifier.move(step * 7L, if (step % 2 == 0) 3f else -3f, 0f)
        assertEquals(MascotGesture.NONE, classifier.tick(400L))
        assertFalse(classifier.petting)
        assertEquals(MascotGestureClassifier.NEVER, classifier.deadline())
    }

    private companion object {
        const val TAP_MILLIS = 60L
        const val DOUBLE_GAP = 200L
        const val TRICK_SPACING = 2_000L
    }
}
