@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.width
import com.letta.mobile.ui.chat.surface.composer.ComposerCompanionTags
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineTags
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.MascotTransport
import com.letta.mobile.ui.theme.ChatMascotDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: "the chat is a bit janky as it expands and collapses, I'm seeing
 * flickering". The page used to swap its resting panel and page for a separate morph layer at
 * both ends of every transition, so what the person was looking at was disposed and composed
 * again: markdown came back blank for a frame, the mascot's seat left the transport for a frame
 * (its scene torn down and brought back up), the companion slot closed and reopened, and
 * minimising dropped the whole dock to nothing before fading the new layout in.
 *
 * These drive the page frame by frame (16 ms) over a canvas with a mascot under a transport
 * layer, and check that nothing on screen is recreated, the mascot's seat and scene live
 * through, the prompt keeps focus, and no frame's brightness jumps off the eased curve and back.
 */
class ChatSurfaceContinuityTest {
    @Test
    fun expandingAndCollapsingRecreateNothingOnScreen() = runComposeUiTest {
        val rig = ChatSurfaceFlickerRig("panel")
        with(rig) {
            mount()
            focus(ComposerTestTags.DOCKED_INPUT)
            capture("rest", 2)

            raise(ChatSurfaceIntent.Expand)
            capture("expand", MORPH_FRAMES)
            // The page came up once, at the start of the morph, and stays.
            assertEquals(1, timeline.created, "timeline $timeline")
            assertEquals(0, timeline.disposed, "timeline $timeline")
            // The panel's composer went only once covered; the page's came up once.
            assertEquals(Lifetimes(created = 2, disposed = 1), composer.snapshot(), "composer $composer")
            onNodeWithTag(ComposerTestTags.INPUT).assertIsFocused()

            raise(ChatSurfaceIntent.Collapse)
            capture("collapse", MORPH_FRAMES)
            assertEquals(1, timeline.created, "the page is never recomposed on the way back: $timeline")
            assertEquals(1, timeline.disposed, "it goes once the panel covers it again: $timeline")
            assertEquals(Lifetimes(created = 3, disposed = 2), composer.snapshot(), "composer $composer")
            onNodeWithTag(ComposerTestTags.DOCKED_INPUT).assertIsFocused()
        }
        rig.writeReport()
        assertMascotLivedThrough(rig)
        assertTrue(rig.frames.all { it.composerLive >= 1 }, "a frame without a composer:\n${rig.report()}")
        assertTrue(rig.frames.filter { it.label == "expand" }.all { it.timelineLive == 1 }, rig.report())
        assertNoBlinks(rig, "expand", "collapse")
        assertBackAtRest(rig, "collapse")
    }

    @Test
    fun minimisingAndRestoringKeepTheBarAndTheMascot() = runComposeUiTest {
        val rig = ChatSurfaceFlickerRig("fold")
        with(rig) {
            mount()
            focus(ComposerTestTags.DOCKED_INPUT)
            capture("rest", 2)
            val bar = onNodeWithTag(ComposerTestTags.DOCKED_BAR).getBoundsInRoot()
            val barProbe = { assertBarStill(bar) }

            dockAction("Minimise the chat to its bar")
            capture("minimise", FOLD_FRAMES, probe = barProbe)
            onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).assertExists()
            onNodeWithTag(DOCKED_REPLY_TAG).assertDoesNotExist()
            onNodeWithTag(ComposerTestTags.DOCKED_INPUT).assertIsFocused()

            dockAction("Show the conversation")
            capture("restore", FOLD_FRAMES, probe = barProbe)
            onNodeWithTag(DOCKED_REPLY_TAG).assertExists()
            onNodeWithTag(ComposerTestTags.DOCKED_INPUT).assertIsFocused()
            // The bar is the same bar the whole way: never disposed, never recreated.
            assertEquals(Lifetimes(created = 1, disposed = 0), composer.snapshot(), "composer $composer")
        }
        rig.writeReport()
        assertMascotLivedThrough(rig)
        assertNoBlinks(rig, "minimise", "restore")
        assertBackAtRest(rig, "restore")
    }

    @Test
    fun expandingFromTheMinimisedDockKeepsTheMascot() = runComposeUiTest {
        val rig = ChatSurfaceFlickerRig("minimised")
        rig.geometry = rig.geometry.copy(collapsed = true)
        with(rig) {
            mount()
            capture("rest", 2)
            raise(ChatSurfaceIntent.Expand)
            capture("expand", MORPH_FRAMES)
            raise(ChatSurfaceIntent.Collapse)
            capture("collapse", MORPH_FRAMES)
            assertEquals(1, timeline.created, "timeline $timeline")
        }
        rig.writeReport()
        assertMascotLivedThrough(rig)
        assertNoBlinks(rig, "expand", "collapse")
        assertBackAtRest(rig, "collapse")
    }

    @Test
    fun theOpenPanelWearsTheMascotAsATopCentreBadgeAndTheBarTakesItsWidth() = runComposeUiTest {
        val rig = ChatSurfaceFlickerRig("badge")
        with(rig) {
            mount()
            val panel = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
            val bar = onNodeWithTag(ComposerTestTags.DOCKED_BAR).getBoundsInRoot()
            // No companion slot beside the bar: it spans the panel less the composer's side inset.
            onNodeWithTag(ComposerCompanionTags.SLOT).assertDoesNotExist()
            assertEquals((panel.left + LettaDimens.Space.lg).value, bar.left.value, DP_TOLERANCE, "bar $bar in $panel")
            assertEquals((panel.right - LettaDimens.Space.lg).value, bar.right.value, DP_TOLERANCE, "bar $bar in $panel")

            // The seat stands in the badge, centred on the panel's top edge, half of it above.
            val badge = onNodeWithTag(DOCK_BADGE_TAG).getBoundsInRoot()
            assertEquals(ChatMascotDimens.dockBadge.value, badge.width.value, DP_TOLERANCE)
            assertEquals(panel.top.value, ((badge.top + badge.bottom) / 2).value, DP_TOLERANCE, "badge $badge on $panel")
            val seat = assertNotNull(shell.transport.seat(MascotTransport.SeatKey(ChatSurfaceFlickerRig.AGENT, MascotStage.COMPOSER_COMPANION)))
            val seatDp = with(density) { seat.bounds.let { DpRect(it.left.toDp(), it.top.toDp(), it.right.toDp(), it.bottom.toDp()) } }
            assertEquals(ChatMascotDimens.dockBadgeSeat.value, seatDp.width.value, DP_TOLERANCE, "seat $seatDp")
            assertEquals(((panel.left + panel.right) / 2).value, ((seatDp.left + seatDp.right) / 2).value, DP_TOLERANCE, "seat $seatDp")
            assertEquals(panel.top.value, ((seatDp.top + seatDp.bottom) / 2).value, DP_TOLERANCE, "seat $seatDp")
            assertTrue(seatDp.top < panel.top, "the seat rises above the panel's edge: $seatDp over $panel")
            // The layer still draws the character at the seat's own size, scaled to the badge.
            assertEquals(with(density) { ChatMascotDimens.composerCompanion.toPx() }, seat.drawWidth, 1f)
        }
        assertEquals(1, rig.seatRegistrations)
    }

    @Test
    fun theFullPageKeepsItsScrollPositionThroughTheMorph() = runComposeUiTest {
        val rig = ChatSurfaceFlickerRig("scroll")
        with(rig) {
            mount()
            raise(ChatSurfaceIntent.Expand)
            frame(MORPH_FRAMES)
            onNodeWithTag(ChatTimelineTags.LIST).performScrollToIndex(SCROLLED_INDEX)
            frame(2)
            val reading = scrollPosition()
            raise(ChatSurfaceIntent.Collapse)
            frame(MORPH_FRAMES)
            raise(ChatSurfaceIntent.Expand)
            frame(MORPH_FRAMES)
            assertEquals(reading, scrollPosition(), "the page reopens where the person was reading")
            onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).assertExists()
        }
    }

    private data class Lifetimes(val created: Int, val disposed: Int)

    private fun ChatSurfaceFlickerRig.Lifetimes.snapshot() = Lifetimes(created, disposed)

    private fun ComposeUiTest.focus(tag: String) {
        onNodeWithTag(tag).performClick()
        mainClock.advanceTimeBy(ChatSurfaceFlickerRig.FRAME_MILLIS * 2)
        onNodeWithTag(tag).assertIsFocused()
    }

    /** Runs one of the dock's accessibility actions: no pointer, so the prompt keeps its focus. */
    private fun ComposeUiTest.dockAction(label: String) {
        val actions = onNodeWithTag(DOCK_PANEL_TAG).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        runOnUiThread { actions.single { it.label == label }.action() }
    }

    private fun ComposeUiTest.scrollPosition(): Float =
        onNodeWithTag(ChatTimelineTags.LIST).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    /**
     * The bar stays exactly where it is while the dock folds above it: no companion slot beside it
     * open (the mascot is in the panel's badge) or minimised (it stands above the bar), so not even
     * its width changes.
     */
    private fun ComposeUiTest.assertBarStill(before: androidx.compose.ui.unit.DpRect) {
        val now = onNodeWithTag(ComposerTestTags.DOCKED_BAR).getBoundsInRoot()
        assertEquals(before.top, now.top, "bar top: $before -> $now")
        assertEquals(before.bottom, now.bottom, "bar bottom: $before -> $now")
        assertEquals(before.left, now.left, "bar left: $before -> $now")
        assertEquals(before.right, now.right, "bar right: $before -> $now")
    }

    private fun assertMascotLivedThrough(rig: ChatSurfaceFlickerRig) {
        assertEquals(1, rig.seatRegistrations, "one companion seat for the whole run:\n${rig.report()}")
        assertTrue(rig.frames.all { it.seated }, "the seat left the transport:\n${rig.report()}")
        assertEquals(1, rig.mascotScene.created, "the mascot's scene was brought up again: ${rig.mascotScene}")
        assertEquals(0, rig.mascotScene.disposed, "the mascot's scene was torn down: ${rig.mascotScene}")
        assertEquals(1, rig.canvas.created, "the canvas was recomposed: ${rig.canvas}")
        assertEquals(0, rig.canvas.disposed, "the canvas was disposed: ${rig.canvas}")
    }

    private fun assertNoBlinks(rig: ChatSurfaceFlickerRig, vararg labels: String) {
        labels.forEach { label ->
            assertTrue(rig.blinks(label).isEmpty(), "blink frames in $label: ${rig.blinks(label)}\n${rig.report()}")
        }
    }

    /** After the round trip the page looks exactly as it did before it. */
    private fun assertBackAtRest(rig: ChatSurfaceFlickerRig, label: String) {
        val rest = rig.frames.first { it.label == "rest" }.luminance
        val end = rig.frames.last { it.label == label }.luminance
        assertTrue(abs(rest - end) < REST_TOLERANCE, "rest $rest, after $label $end\n${rig.report()}")
    }

    private companion object {
        /** The morph is 340 ms: 30 frames see it through and settle. */
        const val MORPH_FRAMES = 30

        /** The fold is 180 ms; the companion's slot settles a little later. */
        const val FOLD_FRAMES = 40
        const val SCROLLED_INDEX = 6
        const val REST_TOLERANCE = 0.0005
        const val DP_TOLERANCE = 1f
    }
}
