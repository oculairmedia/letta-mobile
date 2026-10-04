@file:OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)

package com.letta.mobile.ui.chat.surface.sendlift

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.timeline.ManualMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-86njl.1: the send choreography on a phone-sized Touch page, frame by frame. Every
 * invariant that fails on main today is pinned in a test of its own, ignored with the bead that
 * turns it green; the ones that hold are asserted live. Run an ignored test with its @Ignore
 * removed to see the failing values.
 *
 * Geometry runs on the page's legacy list over a [SendLiftPort] (deterministic: the prompt lands in
 * the frame after the tap). The canonical route's list ([PagedSendLiftRig]) is used where the route
 * matters: scroll-on-send and what recomposes. It needs Paging's Main dispatcher ([ManualMainDispatcher]).
 */
class SendLiftFramesTest {
    private val main = ManualMainDispatcher()

    @BeforeTest
    fun installMain() = Dispatchers.setMain(main)

    @AfterTest
    fun removeMain() = Dispatchers.resetMain()

    @Test
    fun shortPromptFullScreen() {
        val frames = framesOf(shortCase())
        assertNoViolations("the list ends on the newest edge", listEndsAtNewestEdge(frames))
        assertNoViolations("the prompt is fully visible", promptFullyVisibleWithin(frames, SETTLED_MILLIS))
        assertNoViolations("no chevron to blink", chevronNeverBlinks(frames))
        assertNoViolations("no departing text yet", departingTextGoneWithin(frames, DEPART_MILLIS))
    }

    @Test
    fun longPromptFullScreenShowsTheChevronWithoutAJump() {
        val frames = framesOf(longCase())
        assertTrue(frames.any { it.chevronCount > 0 }, "a six line prompt clamps and shows its chevron")
        assertNoViolations("the list ends on the newest edge", listEndsAtNewestEdge(frames))
        assertNoViolations("the prompt is fully visible", promptFullyVisibleWithin(frames, SETTLED_MILLIS))
    }

    @Test
    fun fourLineDraftDoesNotJumpTheList() {
        val frames = framesOf(fourLineCase())
        assertTrue(frames.first().composerField.height > ONE_LINE_PX * 3, "the draft wraps to four lines in the bar")
        assertNoViolations("the list ends on the newest edge", listEndsAtNewestEdge(frames))
    }

    @Test
    fun promptRenamedMidLiftKeepsItsRow() {
        val frames = framesOf(renameCase())
        assertTrue(frames.any { it.chevronCount > 0 }, "the long prompt shows its chevron")
        assertTrue(frames.last().promptSlot != null, "the renamed prompt keeps its list item")
        assertNoViolations("the list ends on the newest edge", listEndsAtNewestEdge(frames))
    }

    @Test
    fun sendWhileScrolledUpLandsAtTheNewestEdge() {
        val frames = framesOf(scrolledUpCase())
        assertTrue(frames.first().firstVisibleItemIndex > 0, "the list starts scrolled away from the newest edge")
        assertNoViolations("the list ends on the newest edge", listEndsAtNewestEdge(frames))
        assertNoViolations("the prompt is fully visible", promptFullyVisibleWithin(frames, SETTLED_MILLIS))
    }

    @Test
    fun sendWithTheImeUp() {
        val frames = framesOf(imeCase())
        assertNoViolations("the list ends on the newest edge", listEndsAtNewestEdge(frames))
        assertNoViolations("the prompt is fully visible", promptFullyVisibleWithin(frames, SETTLED_MILLIS))
    }

    @Test
    fun canvasDockedSendRecordsTheSendFromTheBar() {
        val frames = framesOf(canvasCase())
        assertTrue(frames.first().ghostCount == 0, "nothing flies before the tap")
        assertTrue(frames.any { it.ghostCount > 0 }, "the tap launches the flight from the docked bar")
    }

    @Ignore("red until letta-mobile-86njl.6")
    @Test
    fun canvasDockedSendShowsTheSentTextAfterTheFlight() =
        assertTrue(run(canvasCase()).textShownAtEnd, "the sent text is on screen once the flight is over")

    @Ignore("red until letta-mobile-86njl.6")
    @Test
    fun canvasDockedSendLeavesNothingFlyingAfterTheArrival() =
        assertNoViolations("flight", noFlightLongerThan(framesOf(canvasCase()), ARRIVE_MILLIS))

    @Ignore("red until letta-mobile-86njl.2")
    @Test
    fun shortPromptBubbleNeverResizes() = assertNoViolations("bubble size jumps", bubbleSizeJumps(framesOf(shortCase())))

    @Ignore("red until letta-mobile-86njl.2")
    @Test
    fun shortPromptBubbleBoundsAreConstantFromItsFirstFrame() =
        assertNoViolations("bubble bounds", bubbleBoundsConstantFromFirstFrame(framesOf(shortCase())))

    @Ignore("red until letta-mobile-86njl.2")
    @Test
    fun shortPromptOlderRowEasesUpWithoutAStep() =
        assertNoViolations("previous row", olderRowStepJumps(framesOf(shortCase()), MAX_ROW_STEP_PX))

    @Ignore("red until letta-mobile-86njl.2")
    @Test
    fun shortPromptLeavesNothingFlyingAfterTheArrival() =
        assertNoViolations("flight", noFlightLongerThan(framesOf(shortCase()), ARRIVE_MILLIS))

    @Ignore("red until letta-mobile-86njl.4")
    @Test
    fun shortPromptOpensTheCompanionRowWithTheSend() =
        assertNoViolations("companion row", companionRowOpensWithTheSend(framesOf(shortCase())))

    @Ignore("red until letta-mobile-86njl.2")
    @Test
    fun longPromptBubbleNeverResizes() = assertNoViolations("bubble size jumps", bubbleSizeJumps(framesOf(longCase())))

    @Ignore("red until letta-mobile-86njl.2")
    @Test
    fun longPromptOlderRowEasesUpWithoutAStep() =
        assertNoViolations("previous row", olderRowStepJumps(framesOf(longCase()), MAX_ROW_STEP_PX))

    @Ignore("red until letta-mobile-86njl.4")
    @Test
    fun fourLineDraftOlderRowEasesUpWithoutAStep() =
        assertNoViolations("previous row", olderRowStepJumps(framesOf(fourLineCase()), MAX_ROW_STEP_PX))

    @Ignore("red until letta-mobile-86njl.2")
    @Test
    fun renamedPromptChevronNeverBlinks() = assertNoViolations("chevron", chevronNeverBlinks(framesOf(renameCase())))

    @Ignore("red until letta-mobile-86njl.3")
    @Test
    fun pagedSendWhileScrolledUpLandsAtTheNewestEdge() {
        val frames = framesOf(pagedScrolledUpCase())
        assertTrue(frames.first().firstVisibleItemIndex > 0, "the list starts scrolled away from the newest edge")
        assertNoViolations("the list ends on the newest edge", listEndsAtNewestEdge(frames))
    }

    @Ignore("red until letta-mobile-86njl.2")
    @Test
    fun pagedSendRecomposesOnlyTheSentRow() =
        assertNoViolations("row compositions", rowRecompositionsDuringArrival(framesOf(pagedCase())))

    private fun shortCase() = SendLiftCase(label = "short-full", draft = SHORT, after = { port ->
        writeFrameImage("short-t0")
        advance(3)
        writeFrameImage("short-t48")
        advance(FRAMES_TO_FIRST_REPLY - 3)
        port.typing(true)
        advance(1)
        writeFrameImage("short-t112")
        advance(8)
        writeFrameImage("short-t240")
        advance(11)
        writeFrameImage("short-t416")
        advance(7)
        writeFrameImage("short-t528")
        advance(10)
    })

    private fun longCase() = SendLiftCase(label = "long-full", draft = SIX_LINES)

    private fun fourLineCase() = SendLiftCase(label = "four-line-full", draft = FOUR_LINES)

    private fun renameCase() = SendLiftCase(label = "long-rename-full", draft = SIX_LINES, after = { port ->
        advance(RENAME_FRAME)
        port.rename()
        advance(FRAMES_AFTER)
    })

    private fun scrolledUpCase() = SendLiftCase(label = "scrolled-up-full", draft = SHORT, history = LONG_HISTORY, before = {
        scrollListAwayFromTheEdge()
    })

    private fun imeCase() = SendLiftCase(label = "ime-full", draft = SHORT, mount = SendLiftMount(keyboard = IME_DP.dp))

    private fun canvasCase() = SendLiftCase(
        label = "canvas-docked",
        draft = SHORT,
        mount = SendLiftMount(presentation = ChatSurfacePresentation.CanvasFirst),
        after = { advance(CANVAS_FRAMES) },
    )

    private fun pagedCase() = SendLiftCase(label = "paged-full", draft = SHORT, history = PAGED_HISTORY, paged = true)

    private fun pagedScrolledUpCase() = SendLiftCase(
        label = "paged-scrolled-up",
        draft = SHORT,
        history = PAGED_HISTORY,
        paged = true,
        before = { scrollListAwayFromTheEdge() },
    )

    private fun framesOf(case: SendLiftCase): List<SendFrame> = run(case).frames

    private fun run(case: SendLiftCase): SendLiftRun {
        var recorded = SendLiftRun(emptyList(), false)
        runDesktopComposeUiTest(width = SendLiftFrameRecorder.WIDTH, height = SendLiftFrameRecorder.HEIGHT) {
            val rig = if (case.paged) PagedSendLiftRig.open(main, case.history) else null
            try {
                val port = SendLiftPort(if (rig == null) case.history else 0, case.draft, rig?.let { it::send })
                SendLiftFrameRecorder(this, port, rig?.let { it::pump } ?: {}).use { recorder ->
                    recorded = play(recorder, case, rig)
                }
            } finally {
                rig?.close()
            }
        }
        return recorded
    }

    private fun play(recorder: SendLiftFrameRecorder, case: SendLiftCase, rig: PagedSendLiftRig?): SendLiftRun {
        recorder.mount(case.mount.copy(paged = rig?.presentation))
        if (rig != null) recorder.step(PAGED_OPEN_FRAMES)
        case.before(recorder)
        recorder.tapSend(case.draft)
        case.after(recorder, recorder.port)
        recorder.writeFrameLog(case.label)
        return SendLiftRun(recorder.frames, recorder.textOnScreen(case.draft))
    }

    private fun assertNoViolations(what: String, violations: List<String>) =
        assertTrue(violations.isEmpty(), "$what:\n" + violations.joinToString("\n"))

    private companion object {
        const val SHORT = "Plan a taco night for six"
        const val FOUR_LINES = "Plan a taco night for six friends with a vegetarian option and something for the kids to build themselves"
        const val SIX_LINES = "Plan a taco night for six friends: two of them are vegetarian, one avoids gluten, and the kids " +
            "want to build their own tacos at the table. List what to buy, what to prep the day before, what to " +
            "cook in what order so everything is warm at seven, and how to keep the toppings fresh and the table tidy."
        const val ONE_LINE_PX = 24f
        const val IME_DP = 300
        const val LONG_HISTORY = 30
        const val PAGED_HISTORY = 12
        const val PAGED_OPEN_FRAMES = 30
        const val RENAME_FRAME = 8
        const val FRAMES_TO_FIRST_REPLY = 6
        const val FRAMES_AFTER = 34
        const val CANVAS_FRAMES = 100
        const val SETTLED_MILLIS = 600
        const val DEPART_MILLIS = 160

        /** The arrival the redesign settles on (letta-mobile-86njl.2), as a literal so this harness outlives the old tokens. */
        const val ARRIVE_MILLIS = 240
        const val MAX_ROW_STEP_PX = 10f
    }
}

/** What a send left behind: its frames, and whether the sent text was on screen at the end. */
internal data class SendLiftRun(val frames: List<SendFrame>, val textShownAtEnd: Boolean)

/** One send to run: the draft, the page it is sent from, and what happens before and after the tap. */
internal data class SendLiftCase(
    val label: String,
    val draft: String,
    val history: Int = DEFAULT_HISTORY,
    val mount: SendLiftMount = SendLiftMount(),
    val paged: Boolean = false,
    val before: SendLiftFrameRecorder.() -> Unit = {},
    val after: SendLiftFrameRecorder.(SendLiftPort) -> Unit = { advance(DEFAULT_FRAMES) },
) {
    companion object {
        const val DEFAULT_HISTORY = 4
        const val DEFAULT_FRAMES = 40
    }
}
