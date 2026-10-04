package com.letta.mobile.ui.chat.surface.sendlift

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-86njl.1: the invariants themselves, on frames written by hand. Each has a frame
 * sequence that satisfies it and one that breaks it, so a harness whose invariant never fires
 * (or always does) fails here, not in the redesign beads that rely on it.
 */
class SendLiftFrameInvariantsTest {
    private val viewport = Rect(0f, 0f, 412f, 700f)

    private fun frame(
        t: Int,
        bubble: Rect? = null,
        ghost: Rect? = null,
        older: Rect? = null,
        edge: Boolean = true,
        composed: Int = 0,
        chevrons: Int = 0,
        companion: Float = 0f,
        departing: Int = 0,
    ) = SendFrame(
        t = t,
        promptBubble = bubble,
        ghost = ghost,
        promptSlot = null,
        olderRow = older,
        composerField = Rect(78f, 704f, 334f, 728f),
        viewport = viewport,
        companionRowHeight = companion,
        firstVisibleItemIndex = if (edge) 0 else 5,
        scrollOffset = 0,
        scrollCommands = 0,
        rowCompositions = composed,
        chevronCount = chevrons,
        departingTextCount = departing,
    )

    private fun rect(top: Float, width: Float = 170f, height: Float = 58f) = Rect(230f, top, 230f + width, top + height)

    private fun row(top: Float) = Rect(0f, top, 412f, top + 82f)

    @Test
    fun bubbleSizeJumpsFiresOnAResizingGhostAndNotOnAFixedBubble() {
        val resizing = listOf(frame(0, ghost = rect(600f, 256f, 24f)), frame(16, ghost = rect(600f, 255f, 24f)))
        val fixed = listOf(frame(0, bubble = rect(600f)), frame(16, bubble = rect(590f)))
        assertEquals(1, bubbleSizeJumps(resizing).size)
        assertTrue(bubbleSizeJumps(fixed).isEmpty())
    }

    @Test
    fun olderRowStepJumpsFiresOnAStepAndOnAReversalAndNotOnASmoothRise() {
        val step = listOf(frame(-16, older = row(448f)), frame(0, older = row(412f)))
        val reversal = listOf(frame(0, older = row(412f)), frame(16, older = row(440f)))
        val smooth = listOf(frame(0, older = row(448f)), frame(16, older = row(444f)), frame(32, older = row(436f)))
        assertEquals(1, olderRowStepJumps(step, maxPxPerFrame = 10f).size)
        assertEquals(1, olderRowStepJumps(reversal, maxPxPerFrame = 10f).size)
        assertTrue(olderRowStepJumps(smooth, maxPxPerFrame = 10f).isEmpty())
    }

    @Test
    fun bubbleBoundsConstantFiresOnAGrowingBubbleAndOnAMismatchedHandOff() {
        val growing = listOf(frame(0, bubble = rect(637f, height = 27f)), frame(16, bubble = rect(620f, height = 44f)))
        val steady = listOf(frame(0, bubble = rect(598f)), frame(16, bubble = rect(598f)))
        val mismatch = listOf(frame(0, ghost = rect(598f), bubble = rect(598f)), frame(16, bubble = rect(598f, width = 190f)))
        assertTrue(bubbleBoundsConstantFromFirstFrame(growing).isNotEmpty())
        assertTrue(bubbleBoundsConstantFromFirstFrame(steady).isEmpty())
        assertTrue(bubbleBoundsConstantFromFirstFrame(mismatch).isNotEmpty())
    }

    @Test
    fun rowRecompositionsCountsFromTheSendFrameOnly() {
        val quiet = listOf(frame(-16, composed = 30), frame(0, composed = 1), frame(16, composed = 1))
        val noisy = listOf(frame(0, composed = 9), frame(16))
        assertTrue(rowRecompositionsDuringArrival(quiet).isEmpty())
        assertEquals(1, rowRecompositionsDuringArrival(noisy).size)
    }

    @Test
    fun listEndsAtNewestEdgeLooksAtTheLastFrame() {
        assertTrue(listEndsAtNewestEdge(listOf(frame(0, edge = false), frame(16))).isEmpty())
        assertEquals(1, listEndsAtNewestEdge(listOf(frame(0), frame(16, edge = false))).size)
    }

    @Test
    fun promptFullyVisibleWithinNeedsTheWholeBubbleInsideTheViewportInTime() {
        val inside = listOf(frame(0), frame(16, bubble = rect(500f)))
        val below = listOf(frame(0), frame(16, bubble = rect(680f)))
        val late = listOf(frame(0), frame(160, bubble = rect(500f)))
        assertTrue(promptFullyVisibleWithin(inside, millis = 100).isEmpty())
        assertEquals(1, promptFullyVisibleWithin(below, millis = 100).size)
        assertEquals(1, promptFullyVisibleWithin(late, millis = 100).size)
    }

    @Test
    fun noFlightLongerThanAllowsOneFrameOfSlack() {
        val flying = listOf(frame(240, ghost = rect(598f)), frame(256, ghost = rect(598f)), frame(272, ghost = rect(598f)))
        assertEquals(1, noFlightLongerThan(flying, millis = 240).size)
        assertTrue(noFlightLongerThan(flying.take(2), millis = 240).isEmpty())
        assertEquals(1, noFlightLongerThan(listOf(frame(400, departing = 1)), millis = 240).size)
    }

    @Test
    fun departingTextGoneWithinFiresOnlyOnLingeringText() {
        assertEquals(1, departingTextGoneWithin(listOf(frame(200, departing = 1)), millis = 160).size)
        assertTrue(departingTextGoneWithin(listOf(frame(160, departing = 1), frame(176)), millis = 160).isEmpty())
    }

    @Test
    fun companionRowOpensWithTheSendMeansWithinTwoFrames() {
        val withSend = listOf(frame(0), frame(16, companion = 4f))
        val later = listOf(frame(0), frame(16), frame(32), frame(144, companion = 4f))
        assertTrue(companionRowOpensWithTheSend(withSend).isEmpty())
        assertEquals(listOf("the companion row first opens at t=144"), companionRowOpensWithTheSend(later))
    }

    @Test
    fun chevronNeverBlinksFiresOnADroppedFrame() {
        val blink = listOf(frame(0, chevrons = 1), frame(16, chevrons = 0), frame(32, chevrons = 1))
        assertEquals(1, chevronNeverBlinks(blink).size)
        assertTrue(chevronNeverBlinks(listOf(frame(0), frame(16, chevrons = 1), frame(32, chevrons = 1))).isEmpty())
    }
}
