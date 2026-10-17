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

    private fun tap(millis: Long, x: Float = 10f, y: Float = 10f): MascotGesture {
        classifier.down(at(millis), GesturePoint(x, y))
        return classifier.up(at(millis + TAP_MILLIS))
    }

    @Test
    fun aQuickTapIsATap() {
        assertEquals(MascotGesture.TAP, tap(0L))
    }

    @Test
    fun aPressThatWandersPastTheSlopIsNoTap() {
        classifier.down(at(0L), GesturePoint(0f, 0f))
        classifier.move(at(20L), GesturePoint(30f, 0f))
        assertEquals(MascotGesture.NONE, classifier.up(at(40L)))
    }

    @Test
    fun aPressTheSeatsDragTakesIsNoTap() {
        classifier.down(at(0L), GesturePoint(0f, 0f))
        classifier.cancel(at(16L))
        assertEquals(MascotGesture.NONE, classifier.up(at(40L)))
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
        classifier.down(at(0L), GesturePoint(0f, 0f))
        assertEquals(at(400L), classifier.deadline())
        assertEquals(MascotGesture.NONE, classifier.tick(at(399L)))
        assertEquals(MascotGesture.PET_START, classifier.tick(at(400L)))
        assertTrue(classifier.petting)
        assertEquals(MascotGesture.NONE, classifier.up(at(2_000L)), "lifting is not a tap")
        assertEquals(at(2_600L), classifier.deadline())
        assertEquals(MascotGesture.NONE, classifier.tick(at(2_599L)))
        assertEquals(MascotGesture.PET_END, classifier.tick(at(2_600L)))
        assertFalse(classifier.petting)
        assertEquals(PointerTime.NEVER, classifier.deadline())
    }

    @Test
    fun aSlowStrokeAcrossTheBodyPets() {
        classifier.down(at(0L), GesturePoint(0f, 0f))
        // 60 dp in 500 ms: 0.12 dp/ms, well under the 0.3 limit.
        var gesture = MascotGesture.NONE
        for (step in 1..10) {
            val g = classifier.move(at(step * 50L), GesturePoint(step * 6f, 0f))
            if (g != MascotGesture.NONE) gesture = g
        }
        assertEquals(MascotGesture.PET_START, gesture)
    }

    @Test
    fun aFastSwipeNeverPets() {
        classifier.down(at(0L), GesturePoint(0f, 0f))
        classifier.move(at(16L), GesturePoint(40f, 0f))
        classifier.move(at(500L), GesturePoint(41f, 0f))
        assertEquals(MascotGesture.NONE, classifier.tick(at(600L)))
        assertFalse(classifier.petting)
        assertEquals(PointerTime.NEVER, classifier.deadline())
    }

    @Test
    fun aStrokeThatResumesBeforeTheReleaseKeepsPetting() {
        classifier.down(at(0L), GesturePoint(0f, 0f))
        classifier.tick(at(400L))
        classifier.up(at(800L))
        classifier.down(at(1_000L), GesturePoint(0f, 0f))
        assertEquals(MascotGesture.NONE, classifier.tick(at(1_400L)), "still the same petting")
        assertTrue(classifier.petting)
        assertEquals(PointerTime.NEVER, classifier.deadline(), "held: nothing to time")
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
        classifier.down(at(0L), GesturePoint(0f, 0f))
        // ~300 dp of path in 350 ms, never more than 3 dp from where it went down.
        for (step in 1..50) classifier.move(at(step * 7L), GesturePoint(if (step % 2 == 0) 3f else -3f, 0f))
        assertEquals(MascotGesture.NONE, classifier.tick(at(400L)))
        assertFalse(classifier.petting)
        assertEquals(PointerTime.NEVER, classifier.deadline())
    }

    private fun at(millis: Long) = PointerTime(millis)

    private companion object {
        const val TAP_MILLIS = 60L
        const val DOUBLE_GAP = 200L
        const val TRICK_SPACING = 2_000L
    }
}
